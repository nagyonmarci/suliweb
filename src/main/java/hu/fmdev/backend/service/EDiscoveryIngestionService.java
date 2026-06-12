package hu.fmdev.backend.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import hu.fmdev.backend.domain.Attachment;
import hu.fmdev.backend.domain.Email;
import hu.fmdev.backend.domain.FailedConversion;
import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.repository.AttachmentRepository;
import hu.fmdev.backend.repository.EmailRepository;
import hu.fmdev.backend.repository.FailedConversionRepository;
import hu.fmdev.backend.service.rag.TextExtractionService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.*;

@Service
public class EDiscoveryIngestionService {

    private static final int PAGE_SIZE = 50;
    private static final int BULK_SIZE = 100;
    private static final Semaphore PYTHON_SEMAPHORE = new Semaphore(8);

    private final EmailRepository emailRepository;
    private final AttachmentRepository attachmentRepository;
    private final ElasticsearchClient esClient;
    private final WebClient pythonClient;
    private final TextExtractionService textExtractionService;
    private final ProgressTracker progressTracker;
    private final FailedConversionRepository failedConversionRepository;

    private volatile boolean running = false;
    private final AtomicLong totalEmails   = new AtomicLong();
    private final AtomicLong indexedCount  = new AtomicLong();
    private final AtomicLong skippedCount  = new AtomicLong();
    private final AtomicLong attFailures   = new AtomicLong();

    public EDiscoveryIngestionService(EmailRepository emailRepository,
                                      AttachmentRepository attachmentRepository,
                                      ElasticsearchClient esClient,
                                      @Qualifier("pythonProcessorClient") WebClient pythonClient,
                                      TextExtractionService textExtractionService,
                                      ProgressTracker progressTracker,
                                      FailedConversionRepository failedConversionRepository) {
        this.emailRepository = emailRepository;
        this.attachmentRepository = attachmentRepository;
        this.esClient = esClient;
        this.pythonClient = pythonClient;
        this.textExtractionService = textExtractionService;
        this.progressTracker = progressTracker;
        this.failedConversionRepository = failedConversionRepository;
    }

    public boolean isRunning() { return running; }

    public IngestionStats getStats() {
        return new IngestionStats(totalEmails.get(), indexedCount.get(),
                skippedCount.get(), attFailures.get());
    }

    public void ingestAll() {
        if (running) {
            CentralLogger.logWarn("e-Discovery ingestion already running");
            return;
        }
        running = true;
        totalEmails.set(0); indexedCount.set(0); skippedCount.set(0); attFailures.set(0);
        try {
            long total = emailRepository.count();
            totalEmails.set(total);
            progressTracker.startOperation("e-Discovery indexelés", (int) total);
            CentralLogger.logInfo("e-Discovery ingestion start. Emails: " + total);

            int page = 0;
            ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
            try {
                while (true) {
                    var emailPage = emailRepository.findAll(PageRequest.of(page, PAGE_SIZE));
                    if (emailPage.isEmpty()) break;

                    // Collect and process page into bulk operations in parallel
                    List<Callable<List<BulkOperation>>> tasks = emailPage.stream()
                            .map(email -> (Callable<List<BulkOperation>>) () -> buildOps(email))
                            .toList();

                    List<BulkOperation> ops = new ArrayList<>();
                    try {
                        for (Future<List<BulkOperation>> f : exec.invokeAll(tasks)) {
                            try { ops.addAll(f.get()); }
                            catch (ExecutionException e) {
                                CentralLogger.logError("Email feldolgozás hiba", e.getCause());
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        CentralLogger.logError("e-Discovery ingestion megszakítva", e);
                        break;
                    }

                    if (!ops.isEmpty()) flushBulk(ops);

                    if (!emailPage.hasNext()) break;
                    page++;
                }
            } finally {
                exec.shutdown();
            }

            progressTracker.stopOperation();
            CentralLogger.logInfo("e-Discovery ingestion done. Indexed: " + indexedCount
                    + " Skipped: " + skippedCount + " AttFail: " + attFailures);
        } finally {
            running = false;
        }
    }

    public void reIngest(String mongoEmailId) {
        Email email = emailRepository.findById(mongoEmailId).orElse(null);
        if (email == null) return;
        try {
            // Delete existing doc first, then re-index
            esClient.delete(d -> d.index("email_archive").id(esId(email)));
        } catch (IOException e) {
            CentralLogger.logWarn("Korábbi ES doc törlése sikertelen: " + mongoEmailId);
        }
        List<BulkOperation> ops = buildOps(email);
        if (!ops.isEmpty()) flushBulk(ops);
    }

    public void retryFailed(String failedConversionId) {
        FailedConversion fc = failedConversionRepository.findById(failedConversionId).orElse(null);
        if (fc == null || fc.isResolved()) return;
        reIngest(fc.getMongoEmailId());
        fc.markResolved();
        failedConversionRepository.save(fc);
    }

    public int retryAllFailed() {
        List<FailedConversion> pending = failedConversionRepository.findByResolved(false);
        Set<String> emailIds = pending.stream()
                .map(FailedConversion::getMongoEmailId)
                .collect(Collectors.toSet());
        emailIds.forEach(this::reIngest);
        pending.forEach(fc -> {
            fc.markResolved();
            failedConversionRepository.save(fc);
        });
        return emailIds.size();
    }

    public List<FailedConversion> getFailedConversions(FailedConversion.FailureType failureType, Boolean resolved) {
        if (failureType != null && resolved != null) {
            return failedConversionRepository.findByFailureTypeAndResolved(failureType, resolved);
        }
        if (resolved != null) {
            return failedConversionRepository.findByResolved(resolved);
        }
        return failedConversionRepository.findAll();
    }

    public long getFailedCount() {
        return failedConversionRepository.findByResolved(false).size();
    }

    // -------------------------------------------------------------------------

    private List<BulkOperation> buildOps(Email email) {
        progressTracker.increment();

        String id = esId(email);
        String rawBody = textExtractionService.getEmailTextContent(email.getBody(), email.getHtmlContent());
        String bodyDelta = stripReply(rawBody, email.getId(), id);

        // Attachment conversion: dedup by SHA-256 within this call
        List<Map<String, Object>> attachmentDocs = new ArrayList<>();
        Map<String, String> hashToMarkdown = new HashMap<>();
        List<Attachment> atts = attachmentRepository.findByEmailId(email.getId());
        for (Attachment att : atts) {
            String sha = att.getHash();
            if (sha == null || sha.isBlank() || att.getLocalPath() == null) continue;
            if (!hashToMarkdown.containsKey(sha)) {
                String md = convertAttachment(att.getLocalPath(), att.getFilename(), email.getId(), id, sha);
                hashToMarkdown.put(sha, md);
            }
            attachmentDocs.add(Map.of(
                    "filename", att.getFilename() != null ? att.getFilename() : "",
                    "sha256",   sha,
                    "markdownContent", hashToMarkdown.get(sha)));
        }

        Map<String, Object> doc = buildDoc(id, email, bodyDelta, attachmentDocs);

        // Use create op_type: ES returns 409 for duplicates (counted as skip, not error)
        BulkOperation op = BulkOperation.of(b -> b.create(c -> c
                .index("email_archive")
                .id(id)
                .document(doc)));
        return List.of(op);
    }

    private void flushBulk(List<BulkOperation> ops) {
        for (int i = 0; i < ops.size(); i += BULK_SIZE) {
            List<BulkOperation> batch = ops.subList(i, Math.min(i + BULK_SIZE, ops.size()));
            try {
                BulkResponse resp = esClient.bulk(BulkRequest.of(b -> b.operations(batch)));
                if (resp.errors()) {
                    for (var item : resp.items()) {
                        var err = item.error();
                        if (err != null) {
                            if ("version_conflict_engine_exception".equals(Objects.toString(err.type(), ""))) {
                                skippedCount.incrementAndGet();
                            } else {
                                CentralLogger.logWarn("ES bulk error [" + item.id() + "]: "
                                        + Objects.toString(err.reason(), ""));
                            }
                        } else {
                            indexedCount.incrementAndGet();
                        }
                    }
                } else {
                    indexedCount.addAndGet(batch.size());
                }
            } catch (IOException e) {
                CentralLogger.logError("ES bulk flush hiba", e);
            }
        }
    }

    private Map<String, Object> buildDoc(String id, Email email, String bodyDelta,
                                          List<Map<String, Object>> attachments) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("messageId",   id);
        doc.put("mongoEmailId", email.getId());
        doc.put("subject",     email.getSubject());
        doc.put("bodyDelta",   bodyDelta);
        doc.put("sender",      email.getSenderEmailAddress());
        doc.put("senderName",  email.getSenderName());
        doc.put("recipients",  email.getRecipients() != null ? email.getRecipients() : List.of());
        if (email.getReceivedTime() != null) {
            doc.put("date", email.getReceivedTime().toInstant(ZoneOffset.UTC).toEpochMilli());
        }
        doc.put("pstFileName", email.getPstFileName());
        doc.put("pstOwner",    pstOwner(email.getPstFileName()));
        doc.put("threadId",    email.getConversationId());
        doc.put("attachments", attachments);
        return doc;
    }

    private String esId(Email email) {
        String mid = email.getInternetMessageId();
        if (mid != null && !mid.isBlank()) {
            return mid.trim().toLowerCase().replaceAll("[<>\\s]", "");
        }
        return email.getUniqueEntryId() != null ? email.getUniqueEntryId() : email.getId();
    }

    private String pstOwner(String pstFileName) {
        if (pstFileName == null) return "";
        int dot = pstFileName.lastIndexOf('.');
        return dot > 0 ? pstFileName.substring(0, dot) : pstFileName;
    }

    // -------------------------------------------------------------------------
    // Python sidecar calls (soft-fail)
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private String stripReply(String body, String mongoEmailId, String messageId) {
        if (body == null || body.isBlank()) return "";
        try {
            PYTHON_SEMAPHORE.acquire();
            try {
                Map<String, Object> resp = pythonClient.post()
                        .uri("/strip-reply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("body", body))
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();
                if (resp != null && resp.get("stripped") instanceof String s && !s.isBlank()) {
                    return s;
                }
                recordFailure(FailedConversion.replyStrip(mongoEmailId, messageId, "sidecar returned empty result"));
            } finally {
                PYTHON_SEMAPHORE.release();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            CentralLogger.logWarn("strip-reply call failed: " + e.getMessage());
            recordFailure(FailedConversion.replyStrip(mongoEmailId, messageId, e.getMessage()));
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private String convertAttachment(String localPath, String filename,
                                     String mongoEmailId, String messageId, String hash) {
        try {
            byte[] bytes = Files.readAllBytes(Path.of(localPath));
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("file", new ByteArrayResource(bytes) {
                @Override public String getFilename() { return filename != null ? filename : "file"; }
            });
            form.add("filename", filename != null ? filename : "");

            PYTHON_SEMAPHORE.acquire();
            try {
                Map<String, Object> resp = pythonClient.post()
                        .uri("/convert-attachment")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .bodyValue(form)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();
                if (resp != null && resp.get("markdown") instanceof String md) {
                    return md;
                }
                recordFailure(FailedConversion.attachmentConvert(mongoEmailId, messageId, hash, filename,
                        "sidecar returned no markdown"));
            } finally {
                PYTHON_SEMAPHORE.release();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            attFailures.incrementAndGet();
            CentralLogger.logWarn("convert-attachment failed [" + filename + "]: " + e.getMessage());
            recordFailure(FailedConversion.attachmentConvert(mongoEmailId, messageId, hash, filename,
                    e.getMessage()));
        }
        return "";
    }

    private void recordFailure(FailedConversion failure) {
        try {
            failedConversionRepository.save(failure);
        } catch (Exception e) {
            CentralLogger.logWarn("FailedConversion mentése sikertelen: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------

    public record IngestionStats(long totalEmails, long indexed, long skipped, long attFailures) {}
}

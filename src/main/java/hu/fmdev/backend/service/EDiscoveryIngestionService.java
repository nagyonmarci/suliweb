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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

@Service
public class EDiscoveryIngestionService {

    private final EmailRepository emailRepository;
    private final AttachmentRepository attachmentRepository;
    private final ElasticsearchClient esClient;
    private final WebClient pythonClient;
    private final TextExtractionService textExtractionService;
    private final ProgressTracker progressTracker;
    private final FailedConversionRepository failedConversionRepository;
    private final int batchSize;
    private final int bulkSize;
    private final Semaphore pythonSemaphore;

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
                                      FailedConversionRepository failedConversionRepository,
                                      @Value("${ediscovery.ingestion.batch-size:200}") int batchSize,
                                      @Value("${ediscovery.ingestion.bulk-size:200}") int bulkSize,
                                      @Value("${ediscovery.ingestion.python-concurrency:24}") int pythonConcurrency) {
        this.emailRepository = emailRepository;
        this.attachmentRepository = attachmentRepository;
        this.esClient = esClient;
        this.pythonClient = pythonClient;
        this.textExtractionService = textExtractionService;
        this.progressTracker = progressTracker;
        this.failedConversionRepository = failedConversionRepository;
        this.batchSize = batchSize;
        this.bulkSize = bulkSize;
        this.pythonSemaphore = new Semaphore(Math.max(1, pythonConcurrency));
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
        ConcurrentHashMap<String, String> hashToMarkdown = new ConcurrentHashMap<>();
        try {
            long total = emailRepository.count();
            totalEmails.set(total);
            progressTracker.startOperation("e-Discovery indexelés", (int) total);
            CentralLogger.logInfo("e-Discovery ingestion start. Emails: " + total
                    + " batchSize=" + batchSize
                    + " pythonConcurrency=" + pythonSemaphore.availablePermits());

            int page = 0;
            ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
            try {
                while (true) {
                    var emailPage = emailRepository.findAll(PageRequest.of(page, batchSize));
                    if (emailPage.isEmpty()) break;

                    List<String> emailIds = emailPage.getContent().stream()
                            .map(Email::getId)
                            .toList();
                    Map<String, List<Attachment>> attachmentsByEmail = attachmentRepository
                            .findByEmailIdIn(emailIds)
                            .stream()
                            .collect(Collectors.groupingBy(Attachment::getEmailId));

                    List<Callable<List<BulkOperation>>> tasks = emailPage.stream()
                            .map(email -> (Callable<List<BulkOperation>>) () -> buildOps(
                                    email,
                                    attachmentsByEmail.getOrDefault(email.getId(), List.of()),
                                    hashToMarkdown))
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
                    + " Skipped: " + skippedCount + " AttFail: " + attFailures
                    + " UniqueAttachments: " + hashToMarkdown.size());
        } finally {
            running = false;
        }
    }

    public void reIngest(String mongoEmailId) {
        Email email = emailRepository.findById(mongoEmailId).orElse(null);
        if (email == null) return;
        try {
            esClient.delete(d -> d.index("email_archive").id(esId(email)));
        } catch (IOException e) {
            CentralLogger.logWarn("Korábbi ES doc törlése sikertelen: " + mongoEmailId);
        }
        List<BulkOperation> ops = buildOps(email, attachmentRepository.findByEmailId(email.getId()), new ConcurrentHashMap<>());
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

    private List<BulkOperation> buildOps(Email email,
                                           List<Attachment> attachments,
                                           ConcurrentHashMap<String, String> hashToMarkdown) {
        progressTracker.increment();

        String id = esId(email);
        String bodyDelta = textExtractionService.getEmailTextContent(email.getBody(), email.getHtmlContent());

        List<Map<String, Object>> attachmentDocs = new ArrayList<>();
        for (Attachment att : attachments) {
            String sha = att.getHash();
            if (sha == null || sha.isBlank() || att.getLocalPath() == null) continue;
            String markdown = hashToMarkdown.computeIfAbsent(sha, h ->
                    convertAttachment(att.getLocalPath(), att.getFilename(), email.getId(), id, sha));
            attachmentDocs.add(Map.of(
                    "filename", att.getFilename() != null ? att.getFilename() : "",
                    "sha256",   sha,
                    "markdownContent", markdown != null ? markdown : ""));
        }

        Map<String, Object> doc = buildDoc(id, email, bodyDelta, attachmentDocs);

        BulkOperation op = BulkOperation.of(b -> b.index(i -> i
                .index("email_archive")
                .id(id)
                .document(doc)));
        return List.of(op);
    }

    private void flushBulk(List<BulkOperation> ops) {
        for (int i = 0; i < ops.size(); i += bulkSize) {
            List<BulkOperation> batch = ops.subList(i, Math.min(i + bulkSize, ops.size()));
            try {
                BulkResponse resp = esClient.bulk(BulkRequest.of(b -> b.operations(batch)));
                if (resp.errors()) {
                    for (var item : resp.items()) {
                        var err = item.error();
                        if (err != null) {
                            CentralLogger.logWarn("ES bulk error [" + item.id() + "]: "
                                    + Objects.toString(err.reason(), ""));
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
    private String convertAttachment(String localPath, String filename,
                                     String mongoEmailId, String messageId, String hash) {
        try {
            byte[] bytes = Files.readAllBytes(Path.of(localPath));
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("file", new ByteArrayResource(bytes) {
                @Override public String getFilename() { return filename != null ? filename : "file"; }
            });
            form.add("filename", filename != null ? filename : "");

            pythonSemaphore.acquire();
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
                pythonSemaphore.release();
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
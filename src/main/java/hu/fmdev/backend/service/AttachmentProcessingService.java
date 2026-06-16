package hu.fmdev.backend.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import hu.fmdev.backend.domain.Attachment;
import hu.fmdev.backend.domain.FailedConversion;
import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.repository.AttachmentRepository;
import hu.fmdev.backend.repository.FailedConversionRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class AttachmentProcessingService {

    private final AttachmentRepository attachmentRepository;
    private final ElasticsearchClient esClient;
    private final WebClient pythonClient;
    private final FailedConversionRepository failedConversionRepository;
    private final ProgressTracker progressTracker;
    private final int bulkSize;
    private final Semaphore pythonSemaphore;

    private volatile boolean running = false;
    private final AtomicLong totalAttachments = new AtomicLong();
    private final AtomicLong indexedCount     = new AtomicLong();
    private final AtomicLong skippedCount     = new AtomicLong();
    private final AtomicLong failedCount      = new AtomicLong();

    public AttachmentProcessingService(AttachmentRepository attachmentRepository,
                                       ElasticsearchClient esClient,
                                       @Qualifier("pythonProcessorClient") WebClient pythonClient,
                                       FailedConversionRepository failedConversionRepository,
                                       ProgressTracker progressTracker,
                                       @Value("${attachment.processing.bulk-size:200}") int bulkSize,
                                       @Value("${attachment.processing.python-concurrency:24}") int pythonConcurrency) {
        this.attachmentRepository = attachmentRepository;
        this.esClient = esClient;
        this.pythonClient = pythonClient;
        this.failedConversionRepository = failedConversionRepository;
        this.progressTracker = progressTracker;
        this.bulkSize = bulkSize;
        this.pythonSemaphore = new Semaphore(Math.max(1, pythonConcurrency));
    }

    public boolean isRunning() { return running; }

    public ProcessingStats getStats() {
        return new ProcessingStats(totalAttachments.get(), indexedCount.get(),
                skippedCount.get(), failedCount.get());
    }

    public void processAll() {
        if (running) {
            CentralLogger.logWarn("Csatolmány feldolgozás már folyamatban");
            return;
        }
        running = true;
        totalAttachments.set(0); indexedCount.set(0); skippedCount.set(0); failedCount.set(0);
        try {
            Map<String, List<Attachment>> byHash = attachmentRepository.findAll().stream()
                    .filter(a -> a.getHash() != null && !a.getHash().isBlank() && a.getLocalPath() != null)
                    .collect(Collectors.groupingBy(Attachment::getHash));

            totalAttachments.set(byHash.size());
            progressTracker.startOperation("Csatolmányok feldolgozása", byHash.size());
            CentralLogger.logInfo("Csatolmány feldolgozás start. Egyedi fájlok: " + byHash.size());

            List<List<Map.Entry<String, List<Attachment>>>> batches =
                    chunk(new ArrayList<>(byHash.entrySet()), bulkSize);

            ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
            try {
                for (var batch : batches) {
                    List<Callable<BulkOperation>> tasks = batch.stream()
                            .<Callable<BulkOperation>>map(entry -> () -> processGroup(entry.getKey(), entry.getValue()))
                            .toList();

                    List<BulkOperation> ops = new ArrayList<>();
                    try {
                        for (Future<BulkOperation> f : exec.invokeAll(tasks)) {
                            try {
                                BulkOperation op = f.get();
                                if (op != null) ops.add(op);
                            } catch (ExecutionException e) {
                                CentralLogger.logError("Csatolmány feldolgozás hiba", e.getCause());
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        CentralLogger.logError("Csatolmány feldolgozás megszakítva", e);
                        break;
                    }

                    if (!ops.isEmpty()) flushBulk(ops);
                }
            } finally {
                exec.shutdown();
            }

            progressTracker.stopOperation();
            CentralLogger.logInfo("Csatolmány feldolgozás kész. Indexelt: " + indexedCount
                    + " Átugrott: " + skippedCount + " Hiba: " + failedCount);
        } finally {
            running = false;
        }
    }

    public void retryFailed(String failedConversionId) {
        FailedConversion fc = failedConversionRepository.findById(failedConversionId).orElse(null);
        if (fc == null || fc.isResolved()) return;
        List<Attachment> group = attachmentRepository.findAll().stream()
                .filter(a -> fc.getAttachmentHash().equals(a.getHash()))
                .toList();
        if (!group.isEmpty()) {
            BulkOperation op = processGroup(fc.getAttachmentHash(), group);
            if (op != null) flushBulk(List.of(op));
        }
        fc.markResolved();
        failedConversionRepository.save(fc);
    }

    public int retryAllFailed() {
        List<FailedConversion> pending = failedConversionRepository.findByFailureTypeAndResolved(
                FailedConversion.FailureType.ATTACHMENT_CONVERT, false);
        pending.forEach(fc -> retryFailed(fc.getId()));
        return pending.size();
    }

    public List<FailedConversion> getFailedConversions(Boolean resolved) {
        if (resolved != null) {
            return failedConversionRepository.findByFailureTypeAndResolved(
                    FailedConversion.FailureType.ATTACHMENT_CONVERT, resolved);
        }
        return failedConversionRepository.findByFailureTypeAndResolved(
                FailedConversion.FailureType.ATTACHMENT_CONVERT, false);
    }

    public long getFailedCount() {
        return failedConversionRepository.findByFailureTypeAndResolved(
                FailedConversion.FailureType.ATTACHMENT_CONVERT, false).size();
    }

    // -------------------------------------------------------------------------

    private BulkOperation processGroup(String hash, List<Attachment> group) {
        progressTracker.increment();

        try {
            boolean exists = esClient.exists(e -> e.index("attachment_archive").id(hash)).value();
            if (exists) {
                skippedCount.incrementAndGet();
                return null;
            }
        } catch (IOException e) {
            CentralLogger.logWarn("ES exists check sikertelen [" + hash + "]: " + e.getMessage());
        }

        Attachment representative = group.get(0);
        String markdown = convertAttachment(representative.getLocalPath(), representative.getFilename(),
                representative.getEmailId(), hash);
        if (markdown == null) return null;

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("hash", hash);
        doc.put("filename", representative.getFilename());
        doc.put("contentType", representative.getContentType());
        doc.put("pstFileName", representative.getPstFileName());
        doc.put("emailIds", group.stream().map(Attachment::getEmailId).distinct().toList());
        doc.put("markdownContent", markdown);

        indexedCount.incrementAndGet();
        return BulkOperation.of(b -> b.index(i -> i
                .index("attachment_archive")
                .id(hash)
                .document(doc)));
    }

    private void flushBulk(List<BulkOperation> ops) {
        try {
            BulkResponse resp = esClient.bulk(BulkRequest.of(b -> b.operations(ops)));
            if (resp.errors()) {
                for (var item : resp.items()) {
                    var err = item.error();
                    if (err != null) {
                        CentralLogger.logWarn("ES bulk error [" + item.id() + "]: "
                                + Objects.toString(err.reason(), ""));
                    }
                }
            }
        } catch (IOException e) {
            CentralLogger.logError("ES bulk flush hiba", e);
        }
    }

    @SuppressWarnings("unchecked")
    private String convertAttachment(String localPath, String filename, String mongoEmailId, String hash) {
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
                recordFailure(mongoEmailId, hash, filename, "sidecar returned no markdown");
            } finally {
                pythonSemaphore.release();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            CentralLogger.logWarn("convert-attachment failed [" + filename + "]: " + e.getMessage());
            recordFailure(mongoEmailId, hash, filename, e.getMessage());
        }
        return null;
    }

    private void recordFailure(String mongoEmailId, String hash, String filename, String errorMessage) {
        failedCount.incrementAndGet();
        try {
            failedConversionRepository.save(FailedConversion.attachmentConvert(
                    mongoEmailId, null, hash, filename, errorMessage));
        } catch (Exception e) {
            CentralLogger.logWarn("FailedConversion mentése sikertelen: " + e.getMessage());
        }
    }

    private static <T> List<List<T>> chunk(List<T> list, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return chunks;
    }

    // -------------------------------------------------------------------------

    public record ProcessingStats(long totalAttachments, long indexed, long skipped, long failed) {}
}

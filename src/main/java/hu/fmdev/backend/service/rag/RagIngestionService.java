package hu.fmdev.backend.service.rag;

import hu.fmdev.backend.config.RagConfig;
import hu.fmdev.backend.domain.Attachment;
import hu.fmdev.backend.domain.DocumentChunk;
import hu.fmdev.backend.domain.Email;
import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.repository.AttachmentRepository;
import hu.fmdev.backend.repository.DocumentChunkRepository;
import hu.fmdev.backend.repository.EmailRepository;
import hu.fmdev.backend.service.ProgressTracker;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@Service
public class RagIngestionService {

    private final EmailRepository emailRepository;
    private final DocumentChunkRepository chunkRepository;
    private final TextExtractionService textExtractionService;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final QdrantService qdrantService;
    private final ProgressTracker progressTracker;
    private final RagConfig ragConfig;
    private final AttachmentRepository attachmentRepository;

    private volatile boolean running = false;

    public RagIngestionService(EmailRepository emailRepository,
                               DocumentChunkRepository chunkRepository,
                               TextExtractionService textExtractionService,
                               ChunkingService chunkingService,
                               EmbeddingService embeddingService,
                               QdrantService qdrantService,
                               ProgressTracker progressTracker,
                               RagConfig ragConfig,
                               AttachmentRepository attachmentRepository) {
        this.emailRepository = emailRepository;
        this.chunkRepository = chunkRepository;
        this.textExtractionService = textExtractionService;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.qdrantService = qdrantService;
        this.progressTracker = progressTracker;
        this.ragConfig = ragConfig;
        this.attachmentRepository = attachmentRepository;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Ingests all emails that don't have chunks yet.
     * Creates chunks from email body + attachments, then generates embeddings.
     */
    public void ingestAllEmails() {
        if (running) {
            CentralLogger.logWarn("RAG ingestion already running");
            return;
        }

        running = true;
        try {
            // Count how many emails still need processing (no email_body chunk yet)
            long totalEmails = emailRepository.count();
            CentralLogger.logInfo("RAG ingestion starting. Total emails in DB: " + totalEmails);
            progressTracker.startOperation("RAG indexelés", (int) totalEmails);

            // Load a small batch to prevent OOM when the DB returns massive base64 HTML emails
            int pageSize = 20;
            int page = 0;
            int processed = 0;

            ExecutorService executor = Executors.newFixedThreadPool(ragConfig.getIngestionThreads());

            while (true) {
                // Load one page at a time to avoid heap exhaustion
                var pageRequest = org.springframework.data.domain.PageRequest.of(page, pageSize);
                var emailPage = emailRepository.findAll(pageRequest);
                if (emailPage.isEmpty()) break;

                List<Email> toProcess = emailPage.stream()
                        .filter(email -> !chunkRepository.existsByEmailIdAndSourceTypeAndChunkIndex(
                                email.getId(), "email_body", 0))
                        .toList();

                if (!toProcess.isEmpty()) {
                    List<Callable<Void>> tasks = toProcess.stream()
                            .map(email -> (Callable<Void>) () -> {
                                try {
                                    ingestEmail(email);
                                } catch (Exception e) {
                                    CentralLogger.logError("RAG ingestion failed for email: " + email.getId(), e);
                                } finally {
                                    progressTracker.increment();
                                }
                                return null;
                            })
                            .toList();

                    try {
                        executor.invokeAll(tasks);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        CentralLogger.logError("RAG ingestion interrupted", e);
                        break;
                    }

                    // Embed the chunks generated from this page before moving to the next
                    // This prevents the 'pending' list from growing too large in the DB/memory
                    embedPendingChunks();
                } else {
                    // All emails on this page already processed, still advance progress
                    emailPage.forEach(e -> progressTracker.increment());
                }

                processed += emailPage.getNumberOfElements();
                CentralLogger.logInfo("RAG ingestion progress: " + processed + " / " + totalEmails);

                if (!emailPage.hasNext()) break;
                page++;
            }

            executor.shutdown();
            progressTracker.stopOperation();

            CentralLogger.logInfo("RAG ingestion completed");
        } finally {
            running = false;
        }
    }

    /**
     * Ingests a single email: extracts text, chunks it, stores chunks.
     * Optionally also processes attachments if ragConfig.isIncludeAttachments() is true.
     */
    public void ingestEmail(Email email) {
        List<DocumentChunk> chunks = new ArrayList<>();

        // 1. Chunk email body
        String bodyText = textExtractionService.getEmailTextContent(email.getBody(), email.getHtmlContent());
        if (!bodyText.isBlank()) {
            List<String> bodyChunks = chunkingService.chunkText(bodyText);
            for (int i = 0; i < bodyChunks.size(); i++) {
                chunks.add(createChunk(email, "email_body", null, null, i, bodyChunks.get(i)));
            }
        }

        // 2. Chunk subject as a separate searchable unit
        if (email.getSubject() != null && !email.getSubject().isBlank()) {
            chunks.add(createChunk(email, "email_subject", null, null, 0, email.getSubject()));
        }

        // 3. Optionally extract and chunk attachment text (PDF, DOCX, XLSX, …)
        if (ragConfig.isIncludeAttachments()) {
            List<Attachment> attachments = attachmentRepository.findByEmailId(email.getId());
            for (Attachment att : attachments) {
                if (att.getLocalPath() == null || att.getLocalPath().isBlank()) continue;
                try {
                    String attText = textExtractionService.extractTextFromFile(att.getLocalPath());
                    if (attText == null || attText.isBlank()) continue;
                    // Cap per-attachment to avoid bloating the index with giant binaries
                    if (attText.length() > 50_000) attText = attText.substring(0, 50_000);
                    List<String> attChunks = chunkingService.chunkText(attText);
                    for (int i = 0; i < attChunks.size(); i++) {
                        chunks.add(createChunk(email, "attachment_file", att.getLocalPath(), att.getFilename(), i, attChunks.get(i)));
                    }
                } catch (Exception e) {
                    CentralLogger.logWarn("Attachment text extraction failed for: " + att.getFilename() + " – " + e.getMessage());
                }
            }
        }

        if (!chunks.isEmpty()) {
            chunkRepository.saveAll(chunks);
        }
    }

    /**
     * Generates embeddings for all chunks that don't have one yet.
     * Uses batch API calls and parallel processing for performance.
     */
    public void embedPendingChunks() {
        List<DocumentChunk> pending = chunkRepository.findByIngestionStatus("pending");
        if (pending.isEmpty()) {
            CentralLogger.logInfo("No pending chunks to embed");
            return;
        }

        int batchSize = ragConfig.getIngestionBatchSize();
        List<List<DocumentChunk>> batches = partitionList(pending, batchSize);

        CentralLogger.logInfo("Embedding " + pending.size() + " pending chunks in "
                + batches.size() + " batches (batch size: " + batchSize + ")");
        progressTracker.startOperation("Embedding generálás", pending.size());

        ExecutorService executor = Executors.newFixedThreadPool(ragConfig.getEmbeddingThreads());
        try {
            List<Callable<Void>> tasks = batches.stream()
                    .map(batch -> (Callable<Void>) () -> {
                        embedBatch(batch);
                        return null;
                    })
                    .toList();
            executor.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CentralLogger.logError("Embedding interrupted", e);
        } finally {
            executor.shutdown();
            progressTracker.stopOperation();
        }
    }

    /**
     * Embeds a batch of chunks using a single Ollama API call.
     * Also upserts into Qdrant when available for fast ANN search.
     */
    private void embedBatch(List<DocumentChunk> batch) {
        List<String> texts = batch.stream().map(DocumentChunk::getContent).toList();

        try {
            List<List<Double>> embeddings = embeddingService.embedBatch(texts);

            List<QdrantService.ChunkPoint> qdrantPoints = new ArrayList<>();

            for (int i = 0; i < batch.size(); i++) {
                DocumentChunk chunk = batch.get(i);
                if (i < embeddings.size() && !embeddings.get(i).isEmpty()) {
                    chunk.setEmbedding(embeddings.get(i));
                    chunk.setIngestionStatus("embedded");
                    chunk.setEmbeddedAt(LocalDateTime.now());

                    // Prepare Qdrant upsert payload
                    if (qdrantService.isAvailable() && chunk.getId() != null) {
                        Map<String, String> payload = buildQdrantPayload(chunk);
                        qdrantPoints.add(new QdrantService.ChunkPoint(
                                chunk.getId(), embeddings.get(i), payload));
                    }
                } else {
                    chunk.setIngestionStatus("failed");
                }
                progressTracker.increment();
            }
            chunkRepository.saveAll(batch);

            // Batch upsert to Qdrant
            if (!qdrantPoints.isEmpty()) {
                qdrantService.upsertBatch(qdrantPoints);
            }
        } catch (Exception e) {
            CentralLogger.logError("Batch embedding failed for " + batch.size() + " chunks", e);
            for (DocumentChunk chunk : batch) {
                chunk.setIngestionStatus("failed");
                progressTracker.increment();
            }
            chunkRepository.saveAll(batch);
        }
    }

    private Map<String, String> buildQdrantPayload(DocumentChunk chunk) {
        Map<String, String> payload = new HashMap<>();
        payload.put("chunkId", chunk.getId());
        payload.put("emailId", chunk.getEmailId());
        payload.put("sourceType", chunk.getSourceType());
        payload.put("attachmentFileName", chunk.getAttachmentFileName());
        payload.put("content", chunk.getContent());
        payload.put("emailSubject", chunk.getEmailSubject());
        payload.put("senderName", chunk.getSenderName());
        payload.put("senderEmailAddress", chunk.getSenderEmailAddress());
        payload.put("pstFileName", chunk.getPstFileName());
        return payload;
    }

    private <T> List<List<T>> partitionList(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    /**
     * Re-ingests a specific email (deletes old chunks and recreates).
     * Uses batch embedding for the new chunks.
     */
    public void reIngestEmail(String emailId) {
        // Delete old chunks from Qdrant too
        List<DocumentChunk> oldChunks = chunkRepository.findByEmailId(emailId);
        if (qdrantService.isAvailable() && !oldChunks.isEmpty()) {
            List<String> chunkIds = oldChunks.stream()
                    .map(DocumentChunk::getId)
                    .filter(id -> id != null)
                    .toList();
            qdrantService.deleteByChunkIds(chunkIds);
        }
        chunkRepository.deleteByEmailId(emailId);
        Email email = emailRepository.findById(emailId).orElse(null);
        if (email != null) {
            ingestEmail(email);
            List<DocumentChunk> chunks = chunkRepository.findByEmailId(emailId);
            List<DocumentChunk> pendingChunks = chunks.stream()
                    .filter(c -> "pending".equals(c.getIngestionStatus()))
                    .toList();
            if (!pendingChunks.isEmpty()) {
                embedBatch(new ArrayList<>(pendingChunks));
            }
        }
    }

    private DocumentChunk createChunk(Email email, String sourceType, String attachmentPath,
                                      String attachmentFileName, int chunkIndex, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setEmailId(email.getId());
        chunk.setSourceType(sourceType);
        chunk.setAttachmentPath(attachmentPath);
        chunk.setAttachmentFileName(attachmentFileName);
        chunk.setChunkIndex(chunkIndex);
        chunk.setContent(content);
        chunk.setEmailSubject(email.getSubject());
        chunk.setSenderName(email.getSenderName());
        chunk.setSenderEmailAddress(email.getSenderEmailAddress());
        chunk.setEmailReceivedTime(email.getReceivedTime());
        chunk.setPstFileName(email.getPstFileName());
        chunk.setFolderPath(email.getFolderPath());
        chunk.setIngestionStatus("pending");
        chunk.setCreatedAt(LocalDateTime.now());
        return chunk;
    }

    /**
     * Returns ingestion statistics.
     */
    public IngestionStats getStats() {
        long total = chunkRepository.count();
        long embedded = chunkRepository.countByIngestionStatus("embedded");
        long pending = chunkRepository.countByIngestionStatus("pending");
        long failed = chunkRepository.countByIngestionStatus("failed");
        long totalEmails = emailRepository.count();
        return new IngestionStats(totalEmails, total, embedded, pending, failed);
    }

    public record IngestionStats(long totalEmails, long totalChunks, long embeddedChunks,
                                 long pendingChunks, long failedChunks) {}

    /** Resets all 'failed' chunks back to 'pending' so embedding can be retried. */
    public int resetFailed() {
        List<DocumentChunk> failed = chunkRepository.findByIngestionStatus("failed");
        for (DocumentChunk chunk : failed) {
            chunk.setIngestionStatus("pending");
            chunk.setEmbedding(null);
            chunk.setEmbeddedAt(null);
        }
        chunkRepository.saveAll(failed);
        CentralLogger.logInfo("Reset " + failed.size() + " failed chunks to pending");
        return failed.size();
    }

    /** Deletes ALL chunks so a completely fresh ingestion can be started. */
    public long resetAll() {
        long count = chunkRepository.count();
        chunkRepository.deleteAll();
        // Also reset Qdrant collection if available
        if (qdrantService.isAvailable()) {
            qdrantService.resetCollection();
        }
        CentralLogger.logInfo("Deleted all " + count + " chunks for fresh ingestion");
        return count;
    }
}

package hu.fmdev.backend.service.rag;

import hu.fmdev.backend.config.RagConfig;
import hu.fmdev.backend.domain.DocumentChunk;
import hu.fmdev.backend.domain.Email;
import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.repository.DocumentChunkRepository;
import hu.fmdev.backend.repository.EmailRepository;
import hu.fmdev.backend.service.ProgressTracker;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Service
public class RagIngestionService {

    private final EmailRepository emailRepository;
    private final DocumentChunkRepository chunkRepository;
    private final TextExtractionService textExtractionService;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final ProgressTracker progressTracker;
    private final RagConfig ragConfig;

    private volatile boolean running = false;

    public RagIngestionService(EmailRepository emailRepository,
                               DocumentChunkRepository chunkRepository,
                               TextExtractionService textExtractionService,
                               ChunkingService chunkingService,
                               EmbeddingService embeddingService,
                               ProgressTracker progressTracker,
                               RagConfig ragConfig) {
        this.emailRepository = emailRepository;
        this.chunkRepository = chunkRepository;
        this.textExtractionService = textExtractionService;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.progressTracker = progressTracker;
        this.ragConfig = ragConfig;
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
            List<Email> allEmails = emailRepository.findAll();
            List<Email> toProcess = allEmails.stream()
                    .filter(email -> !chunkRepository.existsByEmailIdAndSourceTypeAndChunkIndex(
                            email.getId(), "email_body", 0))
                    .toList();

            if (toProcess.isEmpty()) {
                CentralLogger.logInfo("No new emails to ingest for RAG");
                return;
            }

            CentralLogger.logInfo("RAG ingestion starting for " + toProcess.size() + " emails");
            progressTracker.startOperation("RAG indexelés", toProcess.size());

            ExecutorService executor = Executors.newFixedThreadPool(ragConfig.getIngestionThreads());
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
            } finally {
                executor.shutdown();
                progressTracker.stopOperation();
            }

            // Now embed all pending chunks
            embedPendingChunks();

            CentralLogger.logInfo("RAG ingestion completed");
        } finally {
            running = false;
        }
    }

    /**
     * Ingests a single email: extracts text, chunks it, stores chunks.
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

        // 3. Extract and chunk attachment text
        if (email.getAttachmentPaths() != null) {
            for (String attachmentPath : email.getAttachmentPaths()) {
                String attachmentText = textExtractionService.extractTextFromFile(attachmentPath);
                if (!attachmentText.isBlank()) {
                    String fileName = Path.of(attachmentPath).getFileName().toString();
                    List<String> attachmentChunks = chunkingService.chunkText(attachmentText);
                    for (int i = 0; i < attachmentChunks.size(); i++) {
                        chunks.add(createChunk(email, "attachment", attachmentPath, fileName, i, attachmentChunks.get(i)));
                    }
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
     */
    private void embedBatch(List<DocumentChunk> batch) {
        List<String> texts = batch.stream().map(DocumentChunk::getContent).toList();

        try {
            List<List<Double>> embeddings = embeddingService.embedBatch(texts);

            for (int i = 0; i < batch.size(); i++) {
                DocumentChunk chunk = batch.get(i);
                if (i < embeddings.size() && !embeddings.get(i).isEmpty()) {
                    chunk.setEmbedding(embeddings.get(i));
                    chunk.setIngestionStatus("embedded");
                    chunk.setEmbeddedAt(LocalDateTime.now());
                } else {
                    chunk.setIngestionStatus("failed");
                }
                progressTracker.increment();
            }
            chunkRepository.saveAll(batch);
        } catch (Exception e) {
            CentralLogger.logError("Batch embedding failed for " + batch.size() + " chunks", e);
            for (DocumentChunk chunk : batch) {
                chunk.setIngestionStatus("failed");
                progressTracker.increment();
            }
            chunkRepository.saveAll(batch);
        }
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
}

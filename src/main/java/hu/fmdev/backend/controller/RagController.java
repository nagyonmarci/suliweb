package hu.fmdev.backend.controller;

import hu.fmdev.backend.service.rag.EmbeddingService;
import hu.fmdev.backend.service.rag.RagIngestionService;
import hu.fmdev.backend.service.rag.RagSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagIngestionService ingestionService;
    private final RagSearchService searchService;
    private final EmbeddingService embeddingService;

    public RagController(RagIngestionService ingestionService,
                         RagSearchService searchService,
                         EmbeddingService embeddingService) {
        this.ingestionService = ingestionService;
        this.searchService = searchService;
        this.embeddingService = embeddingService;
    }

    /**
     * Starts RAG ingestion for all unprocessed emails.
     * Extracts text from email bodies + attachments, chunks them, generates embeddings.
     */
    @PostMapping("/ingest")
    public ResponseEntity<String> ingestAll() {
        if (ingestionService.isRunning()) {
            return ResponseEntity.badRequest().body("Indexelés már folyamatban");
        }
        Thread.startVirtualThread(ingestionService::ingestAllEmails);
        return ResponseEntity.ok("RAG indexelés elindítva");
    }

    /**
     * Re-ingests a specific email (useful after content changes).
     */
    @PostMapping("/ingest/{emailId}")
    public ResponseEntity<String> reIngestEmail(@PathVariable String emailId) {
        Thread.startVirtualThread(() -> ingestionService.reIngestEmail(emailId));
        return ResponseEntity.ok("Email újraindexelés elindítva: " + emailId);
    }

    /**
     * Generates embeddings for chunks that don't have one yet.
     */
    @PostMapping("/embed")
    public ResponseEntity<String> embedPending() {
        Thread.startVirtualThread(ingestionService::embedPendingChunks);
        return ResponseEntity.ok("Embedding generálás elindítva");
    }

    /**
     * Semantic search across all indexed emails and attachments.
     * Returns matching chunks with relevance scores.
     */
    @GetMapping("/search")
    public List<RagSearchService.SearchResult> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int topK) {
        return searchService.search(q, topK);
    }

    /**
     * Semantic search grouped by email.
     * Returns emails with their matching chunks and best relevance scores.
     */
    @GetMapping("/search/emails")
    public List<RagSearchService.EmailSearchResult> searchEmails(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int topK) {
        return searchService.searchEmails(q, topK);
    }

    /**
     * Builds a context string suitable for LLM consumption.
     * Use this endpoint to feed RAG context into a chat/completion call.
     */
    @GetMapping("/context")
    public Map<String, String> getContext(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int topK) {
        String context = searchService.buildContext(q, topK);
        return Map.of("query", q, "context", context);
    }

    /**
     * Returns ingestion statistics.
     */
    @GetMapping("/stats")
    public RagIngestionService.IngestionStats getStats() {
        return ingestionService.getStats();
    }

    /**
     * Checks if the Ollama embedding service is available.
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        boolean ollamaAvailable = embeddingService.isAvailable();
        RagIngestionService.IngestionStats stats = ingestionService.getStats();
        return Map.of(
                "ollamaAvailable", ollamaAvailable,
                "ingestionRunning", ingestionService.isRunning(),
                "stats", stats
        );
    }
}

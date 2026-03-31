package hu.fmdev.backend.controller;

import hu.fmdev.backend.config.RagConfig;
import hu.fmdev.backend.service.rag.EmbeddingService;
import hu.fmdev.backend.service.rag.RagChatService;
import hu.fmdev.backend.service.rag.RagIngestionService;
import hu.fmdev.backend.service.rag.RagSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagIngestionService ingestionService;
    private final RagSearchService searchService;
    private final EmbeddingService embeddingService;
    private final RagConfig ragConfig;
    private final RagChatService chatService;
    private final WebClient ollamaWebClient;

    public RagController(RagIngestionService ingestionService,
                         RagSearchService searchService,
                         EmbeddingService embeddingService,
                         RagConfig ragConfig,
                         RagChatService chatService,
                         WebClient ollamaWebClient) {
        this.ingestionService = ingestionService;
        this.searchService = searchService;
        this.embeddingService = embeddingService;
        this.ragConfig = ragConfig;
        this.chatService = chatService;
        this.ollamaWebClient = ollamaWebClient;
    }

    /**
     * Starts RAG ingestion for all unprocessed emails.
     * Extracts text from email bodies + attachments, chunks them, generates embeddings.
     */
    @PostMapping("/ingest")
    public ResponseEntity<String> ingestAll(
            @RequestParam(defaultValue = "false") boolean includeAttachments) {
        if (ingestionService.isRunning()) {
            return ResponseEntity.badRequest().body("Indexelés már folyamatban");
        }
        // Apply the toggle to the shared config so the ingestion thread picks it up
        ragConfig.setIncludeAttachments(includeAttachments);
        Thread.startVirtualThread(ingestionService::ingestAllEmails);
        String msg = "RAG indexelés elindítva" + (includeAttachments ? " (csatolmányok feldolgozásával)" : "");
        return ResponseEntity.ok(msg);
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
     * RAG-grounded chat: retrieves relevant email chunks and sends them as context
     * to a local Ollama LLM, returning a natural-language answer + source emails.
     */
    @PostMapping("/chat")
    public ResponseEntity<RagChatService.ChatResponse> chat(@RequestBody RagChatService.ChatRequest request) {
        try {
            RagChatService.ChatResponse response = chatService.chat(
                    request.message(), request.topK(), request.model(), request.history());
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().build();
        }
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

    /** Resets all 'failed' chunks to 'pending' so embedding can be retried. */
    @PostMapping("/reset-failed")
    public ResponseEntity<String> resetFailed() {
        int count = ingestionService.resetFailed();
        return ResponseEntity.ok("Visszaállítva " + count + " sikertelen chunk 'pending' állapotba");
    }

    /** Deletes ALL chunks so a completely fresh ingestion can be started. */
    @PostMapping("/reset-all")
    public ResponseEntity<String> resetAll() {
        long count = ingestionService.resetAll();
        return ResponseEntity.ok("Törölve " + count + " chunk – indítsd el az indexelést újra");
    }

    /**
     * Returns the list of models currently available in Ollama.
     * Used by the frontend model selector.
     */
    @GetMapping("/models")
    @SuppressWarnings("unchecked")
    public ResponseEntity<List<String>> listModels() {
        try {
            Map<String, Object> response = ollamaWebClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (response == null) return ResponseEntity.ok(Collections.emptyList());
            List<Map<String, Object>> models = (List<Map<String, Object>>) response.get("models");
            if (models == null) return ResponseEntity.ok(Collections.emptyList());
            List<String> names = models.stream()
                    .map(m -> (String) m.get("name"))
                    .filter(name -> name != null && !name.isBlank())
                    .sorted()
                    .toList();
            return ResponseEntity.ok(names);
        } catch (Exception e) {
            return ResponseEntity.ok(Collections.emptyList());
        }
    }
}

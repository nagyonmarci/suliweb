package hu.fmdev.backend.controller;

import hu.fmdev.backend.config.RagConfig;
import hu.fmdev.backend.service.FileAccessService;
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
import java.util.Set;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagIngestionService ingestionService;
    private final RagSearchService searchService;
    private final EmbeddingService embeddingService;
    private final RagConfig ragConfig;
    private final RagChatService chatService;
    private final WebClient ollamaWebClient;
    private final FileAccessService fileAccessService;

    public RagController(RagIngestionService ingestionService,
                         RagSearchService searchService,
                         EmbeddingService embeddingService,
                         RagConfig ragConfig,
                         RagChatService chatService,
                         WebClient ollamaWebClient,
                         FileAccessService fileAccessService) {
        this.ingestionService = ingestionService;
        this.searchService = searchService;
        this.embeddingService = embeddingService;
        this.ragConfig = ragConfig;
        this.chatService = chatService;
        this.ollamaWebClient = ollamaWebClient;
        this.fileAccessService = fileAccessService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<String> ingestAll(
            @RequestParam(defaultValue = "false") boolean includeAttachments) {
        if (ingestionService.isRunning()) {
            return ResponseEntity.badRequest().body("Indexelés már folyamatban");
        }
        ragConfig.setIncludeAttachments(includeAttachments);
        Thread.startVirtualThread(ingestionService::ingestAllEmails);
        String msg = "RAG indexelés elindítva" + (includeAttachments ? " (csatolmányok feldolgozásával)" : "");
        return ResponseEntity.ok(msg);
    }

    @PostMapping("/ingest/{emailId}")
    public ResponseEntity<String> reIngestEmail(@PathVariable String emailId) {
        Thread.startVirtualThread(() -> ingestionService.reIngestEmail(emailId));
        return ResponseEntity.ok("Email újraindexelés elindítva: " + emailId);
    }

    @PostMapping("/embed")
    public ResponseEntity<String> embedPending() {
        Thread.startVirtualThread(ingestionService::embedPendingChunks);
        return ResponseEntity.ok("Embedding generálás elindítva");
    }

    @GetMapping("/search")
    public List<RagSearchService.SearchResult> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int topK) {
        Set<String> allowed = fileAccessService.getAllowedPstFileNames();
        return searchService.search(q, topK, allowed);
    }

    @GetMapping("/search/emails")
    public List<RagSearchService.EmailSearchResult> searchEmails(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int topK) {
        Set<String> allowed = fileAccessService.getAllowedPstFileNames();
        return searchService.searchEmails(q, topK, allowed);
    }

    @GetMapping("/context")
    public Map<String, String> getContext(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int topK) {
        Set<String> allowed = fileAccessService.getAllowedPstFileNames();
        String context = searchService.buildContext(q, topK, allowed);
        return Map.of("query", q, "context", context);
    }

    @PostMapping("/chat")
    public ResponseEntity<RagChatService.ChatResponse> chat(@RequestBody RagChatService.ChatRequest request) {
        try {
            Set<String> allowed = fileAccessService.getAllowedPstFileNames();
            RagChatService.ChatResponse response = chatService.chat(
                    request.message(), request.topK(), request.model(), allowed);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/stats")
    public RagIngestionService.IngestionStats getStats() {
        return ingestionService.getStats();
    }

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

    @PostMapping("/reset-failed")
    public ResponseEntity<String> resetFailed() {
        int count = ingestionService.resetFailed();
        return ResponseEntity.ok("Visszaállítva " + count + " sikertelen chunk 'pending' állapotba");
    }

    @PostMapping("/reset-all")
    public ResponseEntity<String> resetAll() {
        long count = ingestionService.resetAll();
        return ResponseEntity.ok("Törölve " + count + " chunk – indítsd el az indexelést újra");
    }

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

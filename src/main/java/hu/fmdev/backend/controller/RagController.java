package hu.fmdev.backend.controller;

import hu.fmdev.backend.service.KnowledgeGraphIngestionService;
import hu.fmdev.backend.service.rag.GraphRagChatService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final GraphRagChatService chatService;
    private final KnowledgeGraphIngestionService kgIngestionService;
    private final WebClient ollamaWebClient;

    public RagController(GraphRagChatService chatService,
                         KnowledgeGraphIngestionService kgIngestionService,
                         WebClient ollamaWebClient) {
        this.chatService          = chatService;
        this.kgIngestionService   = kgIngestionService;
        this.ollamaWebClient      = ollamaWebClient;
    }

    @PostMapping("/chat")
    public ResponseEntity<GraphRagChatService.ChatResponse> chat(
            @RequestBody GraphRagChatService.ChatRequest request) {
        try {
            return ResponseEntity.ok(
                    chatService.chat(request.message(), request.topK(),
                            request.model(), request.history()));
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody GraphRagChatService.ChatRequest request) {
        return chatService.chatStream(
                request.message(), request.topK(), request.model(), request.history());
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        var kgStats = kgIngestionService.getStats();
        long pending = Math.max(0, kgStats.totalEmails() - kgStats.processed() - kgStats.failed());
        boolean ollamaUp = false;
        try {
            ollamaWebClient.get().uri("/api/tags").retrieve()
                    .bodyToMono(String.class)
                    .block(java.time.Duration.ofSeconds(2));
            ollamaUp = true;
        } catch (Exception ignored) {}
        return Map.of(
                "ollamaAvailable",  ollamaUp,
                "ingestionRunning", kgIngestionService.isRunning(),
                "stats", Map.of(
                        "totalEmails",    kgStats.totalEmails(),
                        "totalChunks",    kgStats.totalEmails(),
                        "embeddedChunks", kgStats.processed(),
                        "pendingChunks",  pending,
                        "failedChunks",   kgStats.failed()
                ));
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
                    .filter(n -> n != null && !n.isBlank())
                    .sorted()
                    .toList();
            return ResponseEntity.ok(names);
        } catch (Exception e) {
            return ResponseEntity.ok(Collections.emptyList());
        }
    }
}

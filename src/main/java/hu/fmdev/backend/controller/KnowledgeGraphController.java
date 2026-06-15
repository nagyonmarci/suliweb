package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.graph.EmailNode;
import hu.fmdev.backend.domain.graph.PersonNode;
import hu.fmdev.backend.service.GraphSearchService;
import hu.fmdev.backend.service.KnowledgeGraphIngestionService;
import hu.fmdev.backend.service.rag.GraphRagChatService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/kg")
public class KnowledgeGraphController {

    private final KnowledgeGraphIngestionService ingestionService;
    private final GraphSearchService graphSearch;
    private final GraphRagChatService chatService;
    private final WebClient ollamaWebClient;

    public KnowledgeGraphController(KnowledgeGraphIngestionService ingestionService,
                                    GraphSearchService graphSearch,
                                    GraphRagChatService chatService,
                                    WebClient ollamaWebClient) {
        this.ingestionService = ingestionService;
        this.graphSearch      = graphSearch;
        this.chatService      = chatService;
        this.ollamaWebClient  = ollamaWebClient;
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @PostMapping("/ingest")
    public ResponseEntity<String> triggerIngestion() {
        if (ingestionService.isRunning()) {
            return ResponseEntity.badRequest().body("KG ingestion már folyamatban");
        }
        Thread.startVirtualThread(ingestionService::ingestAll);
        return ResponseEntity.ok("Knowledge Graph építés elindítva");
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @PostMapping("/reingest-concepts")
    public ResponseEntity<String> triggerConceptReingestion() {
        if (ingestionService.isRunning()) {
            return ResponseEntity.badRequest().body("KG ingestion már folyamatban");
        }
        Thread.startVirtualThread(ingestionService::ingestConceptsOnly);
        return ResponseEntity.ok("Koncepció újraépítés elindítva");
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "running", ingestionService.isRunning(),
                "stats",   ingestionService.getStats());
    }

    @GetMapping("/graph-stats")
    public GraphSearchService.GraphStats graphStats() {
        return graphSearch.getGraphStats();
    }

    @GetMapping("/persons/{email}/network")
    public List<PersonNode> communicationNetwork(
            @PathVariable String email,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return graphSearch.findCommunicationPartners(email, from, to);
    }

    @GetMapping("/thread/{threadId}")
    public List<EmailNode> threadEmails(@PathVariable String threadId) {
        return graphSearch.getThreadEmails(threadId);
    }

    @GetMapping("/concept/{name}")
    public List<EmailNode> conceptNeighborhood(
            @PathVariable String name,
            @RequestParam(defaultValue = "10") int topK) {
        return graphSearch.findEmailsByConceptProximity(name, topK);
    }

    @PostMapping("/chat")
    public GraphRagChatService.ChatResponse chat(
            @RequestBody GraphRagChatService.ChatRequest request) {
        return chatService.chat(request.message(), request.topK(), request.model(), request.history());
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(
            @RequestBody GraphRagChatService.ChatRequest request) {
        return chatService.chatStream(request.message(), request.topK(), request.model(), request.history());
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

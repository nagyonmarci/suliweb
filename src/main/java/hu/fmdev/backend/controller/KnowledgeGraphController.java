package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.graph.EmailNode;
import hu.fmdev.backend.domain.graph.PersonNode;
import hu.fmdev.backend.service.GraphSearchService;
import hu.fmdev.backend.service.KnowledgeGraphIngestionService;
import hu.fmdev.backend.service.rag.GraphRagChatService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/kg")
public class KnowledgeGraphController {

    private final KnowledgeGraphIngestionService ingestionService;
    private final GraphSearchService graphSearch;
    private final GraphRagChatService chatService;

    public KnowledgeGraphController(KnowledgeGraphIngestionService ingestionService,
                                    GraphSearchService graphSearch,
                                    GraphRagChatService chatService) {
        this.ingestionService = ingestionService;
        this.graphSearch      = graphSearch;
        this.chatService      = chatService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<String> triggerIngestion() {
        if (ingestionService.isRunning()) {
            return ResponseEntity.badRequest().body("KG ingestion már folyamatban");
        }
        Thread.startVirtualThread(ingestionService::ingestAll);
        return ResponseEntity.ok("Knowledge Graph építés elindítva");
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "running", ingestionService.isRunning(),
                "stats",   ingestionService.getStats());
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
}

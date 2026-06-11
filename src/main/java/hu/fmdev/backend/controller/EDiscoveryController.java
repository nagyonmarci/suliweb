package hu.fmdev.backend.controller;

import hu.fmdev.backend.service.EDiscoveryIngestionService;
import hu.fmdev.backend.service.EDiscoverySearchService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ediscovery")
public class EDiscoveryController {

    private final EDiscoveryIngestionService ingestionService;
    private final EDiscoverySearchService searchService;

    public EDiscoveryController(EDiscoveryIngestionService ingestionService,
                                EDiscoverySearchService searchService) {
        this.ingestionService = ingestionService;
        this.searchService = searchService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<String> triggerIngestion() {
        if (ingestionService.isRunning()) {
            return ResponseEntity.badRequest().body("e-Discovery indexelés már folyamatban");
        }
        Thread.startVirtualThread(ingestionService::ingestAll);
        return ResponseEntity.ok("e-Discovery indexelés elindítva");
    }

    @PostMapping("/ingest/{mongoEmailId}")
    public ResponseEntity<String> reIngest(@PathVariable String mongoEmailId) {
        Thread.startVirtualThread(() -> ingestionService.reIngest(mongoEmailId));
        return ResponseEntity.ok("Újraindexelés elindítva: " + mongoEmailId);
    }

    @GetMapping("/search")
    public List<EDiscoverySearchService.SearchResult> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int topK,
            @RequestParam(required = false) String sender,
            @RequestParam(required = false) String pstOwner,
            @RequestParam(required = false) String pstFileName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return searchService.search(q, sender, pstOwner, pstFileName, dateFrom, dateTo, topK);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "running", ingestionService.isRunning(),
                "stats",   ingestionService.getStats());
    }
}

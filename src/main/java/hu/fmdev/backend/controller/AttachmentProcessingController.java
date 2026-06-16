package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.FailedConversion;
import hu.fmdev.backend.service.AttachmentProcessingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/attachments/processing")
public class AttachmentProcessingController {

    private final AttachmentProcessingService processingService;

    public AttachmentProcessingController(AttachmentProcessingService processingService) {
        this.processingService = processingService;
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @PostMapping("/start")
    public ResponseEntity<String> start() {
        if (processingService.isRunning()) {
            return ResponseEntity.badRequest().body("Csatolmány feldolgozás már folyamatban");
        }
        Thread.startVirtualThread(processingService::processAll);
        return ResponseEntity.ok("Csatolmány feldolgozás elindítva");
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "running", processingService.isRunning(),
                "stats",   processingService.getStats(),
                "failedCount", processingService.getFailedCount());
    }

    @GetMapping("/failed")
    public List<FailedConversion> listFailed(@RequestParam(required = false) Boolean resolved) {
        return processingService.getFailedConversions(resolved);
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @PostMapping("/retry-failed")
    public ResponseEntity<String> retryAllFailed() {
        int count = processingService.retryAllFailed();
        return ResponseEntity.ok("Retry elindítva " + count + " csatolmányhoz");
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @PostMapping("/retry-failed/{id}")
    public ResponseEntity<String> retryFailed(@PathVariable String id) {
        processingService.retryFailed(id);
        return ResponseEntity.ok("Retry elindítva: " + id);
    }
}

package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.PipelineStatus;
import hu.fmdev.backend.dto.PipelineStartRequest;
import hu.fmdev.backend.service.PipelineOrchestrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pipeline")
public class PipelineController {

    private final PipelineOrchestrationService pipelineService;

    public PipelineController(PipelineOrchestrationService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @PostMapping("/start")
    public ResponseEntity<String> start(@RequestBody PipelineStartRequest req) {
        boolean started = pipelineService.start(req);
        if (!started) {
            return ResponseEntity.badRequest().body("Pipeline már fut.");
        }
        return ResponseEntity.ok("Pipeline elindítva.");
    }

    @GetMapping("/status")
    public PipelineStatus status() {
        return pipelineService.getStatus();
    }
}

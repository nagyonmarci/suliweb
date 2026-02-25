package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.ProgressState;
import hu.fmdev.backend.service.ProgressTracker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/progress")
public class ProgressController {

    private final ProgressTracker progressTracker;

    public ProgressController(ProgressTracker progressTracker) {
        this.progressTracker = progressTracker;
    }

    @GetMapping
    public ProgressState getProgress() {
        return progressTracker.getProgress();
    }
}

package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.PstFinderSettings;
import hu.fmdev.backend.service.PstFinderSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/pst-finder/settings")
@RequiredArgsConstructor
public class PstFinderSettingsController {

    private final PstFinderSettingsService service;

    @GetMapping
    public ResponseEntity<PstFinderSettings> getSettings() {
        return ResponseEntity.ok(service.get());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<PstFinderSettings> saveSettings(@RequestBody SettingsRequest request) {
        return ResponseEntity.ok(service.save(request.searchDirectories(), request.excludedDirectories()));
    }

    public record SettingsRequest(List<String> searchDirectories, List<String> excludedDirectories) {}
}

package hu.fmdev.backend.controller;

import hu.fmdev.backend.dto.SynologySettingsRequest;
import hu.fmdev.backend.dto.SynologySettingsResponse;
import hu.fmdev.backend.service.SynologySettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/synology/settings")
@RequiredArgsConstructor
public class SynologySettingsController {

    private final SynologySettingsService synologySettingsService;

    @GetMapping
    public ResponseEntity<SynologySettingsResponse> getSettings() {
        return ResponseEntity.ok(synologySettingsService.getSettingsResponse());
    }

    @PutMapping
    public ResponseEntity<SynologySettingsResponse> saveSettings(@RequestBody SynologySettingsRequest request) {
        return ResponseEntity.ok(synologySettingsService.saveSettings(request));
    }
}

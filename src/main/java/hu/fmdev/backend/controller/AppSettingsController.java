package hu.fmdev.backend.controller;

import hu.fmdev.backend.dto.AppSettingsDto;
import hu.fmdev.backend.service.AppSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class AppSettingsController {

    private final AppSettingsService appSettingsService;

    @GetMapping
    public ResponseEntity<AppSettingsDto> getSettings() {
        return ResponseEntity.ok(appSettingsService.getSettings());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<AppSettingsDto> saveSettings(@RequestBody AppSettingsDto request) {
        return ResponseEntity.ok(appSettingsService.saveSettings(request));
    }
}

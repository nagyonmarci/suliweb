package hu.fmdev.backend.service;

import hu.fmdev.backend.config.SynologyConfig;
import hu.fmdev.backend.domain.SynologySettings;
import hu.fmdev.backend.dto.SynologySettingsRequest;
import hu.fmdev.backend.dto.SynologySettingsResponse;
import hu.fmdev.backend.repository.SynologySettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SynologySettingsService {

    private final SynologySettingsRepository settingsRepository;
    private final SynologyConfig fallbackConfig;

    private Optional<SynologySettings> settings() {
        return settingsRepository.findById("singleton");
    }

    public String getEffectiveHost() {
        return settings().map(SynologySettings::getHost).filter(StringUtils::hasText).orElse(fallbackConfig.getHost());
    }

    public String getEffectiveUsername() {
        return settings().map(SynologySettings::getUsername).filter(StringUtils::hasText).orElse(fallbackConfig.getUsername());
    }

    public String getEffectivePassword() {
        return settings().map(SynologySettings::getPassword).filter(StringUtils::hasText).orElse(fallbackConfig.getPassword());
    }

    public String getEffectivePathPrefix() {
        return settings().map(SynologySettings::getPathPrefix).filter(StringUtils::hasText).orElse(fallbackConfig.getPathPrefix());
    }

    public String getEffectiveLocalMountPrefix() {
        return settings().map(SynologySettings::getLocalMountPrefix).filter(StringUtils::hasText).orElse(fallbackConfig.getLocalMountPrefix());
    }

    public String getEffectiveSearchExtensions() {
        return settings().map(SynologySettings::getSearchExtensions).filter(StringUtils::hasText).orElse(fallbackConfig.getSearchExtensions());
    }

    public int getEffectiveBatchSize() {
        return settings().map(SynologySettings::getBatchSize).filter(bs -> bs != null && bs > 0).orElse(fallbackConfig.getBatchSize());
    }

    public SynologySettingsResponse getSettingsResponse() {
        Optional<SynologySettings> s = settings();
        SynologySettingsResponse dto = new SynologySettingsResponse();
        dto.setHost(s.map(SynologySettings::getHost).filter(StringUtils::hasText).orElse(fallbackConfig.getHost()));
        dto.setUsername(s.map(SynologySettings::getUsername).filter(StringUtils::hasText).orElse(fallbackConfig.getUsername()));
        dto.setPasswordConfigured(s.map(SynologySettings::getPassword).filter(StringUtils::hasText).isPresent() || StringUtils.hasText(fallbackConfig.getPassword()));
        dto.setPathPrefix(s.map(SynologySettings::getPathPrefix).filter(StringUtils::hasText).orElse(fallbackConfig.getPathPrefix()));
        dto.setLocalMountPrefix(s.map(SynologySettings::getLocalMountPrefix).filter(StringUtils::hasText).orElse(fallbackConfig.getLocalMountPrefix()));
        dto.setSearchExtensions(s.map(SynologySettings::getSearchExtensions).filter(StringUtils::hasText).orElse(fallbackConfig.getSearchExtensions()));
        dto.setBatchSize(s.map(SynologySettings::getBatchSize).filter(bs -> bs != null && bs > 0).orElse(fallbackConfig.getBatchSize()));
        return dto;
    }

    public SynologySettingsResponse saveSettings(SynologySettingsRequest req) {
        SynologySettings doc = settings().orElse(new SynologySettings());

        if (StringUtils.hasText(req.getHost()))             doc.setHost(req.getHost());
        if (StringUtils.hasText(req.getUsername()))         doc.setUsername(req.getUsername());
        if (StringUtils.hasText(req.getPassword()))         doc.setPassword(req.getPassword());
        if (StringUtils.hasText(req.getPathPrefix()))       doc.setPathPrefix(req.getPathPrefix());
        if (StringUtils.hasText(req.getLocalMountPrefix())) doc.setLocalMountPrefix(req.getLocalMountPrefix());
        if (StringUtils.hasText(req.getSearchExtensions())) doc.setSearchExtensions(req.getSearchExtensions());
        if (req.getBatchSize() != null && req.getBatchSize() > 0) doc.setBatchSize(req.getBatchSize());
        doc.setUpdatedAt(LocalDateTime.now());

        settingsRepository.save(doc);
        return getSettingsResponse();
    }
}

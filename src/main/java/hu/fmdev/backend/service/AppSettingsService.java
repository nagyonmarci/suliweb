package hu.fmdev.backend.service;

import hu.fmdev.backend.config.RagConfig;
import hu.fmdev.backend.domain.AppSettings;
import hu.fmdev.backend.dto.AppSettingsDto;
import hu.fmdev.backend.repository.AppSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AppSettingsService {

    private final AppSettingsRepository repository;
    private final RagConfig ragConfig;

    @Value("${kg.ingestion.batch-size:100}")
    private int kgDefaultBatchSize;

    @Value("${kg.ingestion.max-concurrent-writes:4}")
    private int kgDefaultMaxConcurrentWrites;

    public String getEffectiveChatModel() {
        return repository.findById("singleton")
                .map(AppSettings::getChatModel)
                .filter(s -> s != null && !s.isBlank())
                .orElse(ragConfig.getChatModel());
    }

    public String getEffectiveNerModel() {
        return repository.findById("singleton")
                .map(AppSettings::getNerModel)
                .filter(s -> s != null && !s.isBlank())
                .orElse(ragConfig.getChatModel());
    }

    public int getEffectiveChatMaxHistoryTurns() {
        return repository.findById("singleton")
                .map(AppSettings::getChatMaxHistoryTurns)
                .filter(v -> v != null && v > 0)
                .orElse(ragConfig.getChatMaxHistoryTurns());
    }

    public int getEffectiveChatContextTopK() {
        return repository.findById("singleton")
                .map(AppSettings::getChatContextTopK)
                .filter(v -> v != null && v > 0)
                .orElse(ragConfig.getChatContextTopK());
    }

    public int getEffectiveKgBatchSize() {
        return repository.findById("singleton")
                .map(AppSettings::getKgBatchSize)
                .filter(v -> v != null && v > 0)
                .orElse(kgDefaultBatchSize);
    }

    public int getEffectiveKgMaxConcurrentWrites() {
        return repository.findById("singleton")
                .map(AppSettings::getKgMaxConcurrentWrites)
                .filter(v -> v != null && v > 0)
                .orElse(kgDefaultMaxConcurrentWrites);
    }

    public AppSettingsDto getSettings() {
        AppSettingsDto dto = new AppSettingsDto();
        dto.setOllamaBaseUrl(ragConfig.getOllamaBaseUrl());
        dto.setChatModel(getEffectiveChatModel());
        dto.setNerModel(getEffectiveNerModel());
        dto.setChatMaxHistoryTurns(getEffectiveChatMaxHistoryTurns());
        dto.setChatContextTopK(getEffectiveChatContextTopK());
        dto.setKgBatchSize(getEffectiveKgBatchSize());
        dto.setKgMaxConcurrentWrites(getEffectiveKgMaxConcurrentWrites());
        return dto;
    }

    public AppSettingsDto saveSettings(AppSettingsDto req) {
        AppSettings doc = repository.findById("singleton").orElse(new AppSettings());

        if (req.getChatModel() != null && !req.getChatModel().isBlank())
            doc.setChatModel(req.getChatModel());
        if (req.getNerModel() != null && !req.getNerModel().isBlank())
            doc.setNerModel(req.getNerModel());
        if (req.getChatMaxHistoryTurns() != null && req.getChatMaxHistoryTurns() > 0)
            doc.setChatMaxHistoryTurns(req.getChatMaxHistoryTurns());
        if (req.getChatContextTopK() != null && req.getChatContextTopK() > 0)
            doc.setChatContextTopK(req.getChatContextTopK());
        if (req.getKgBatchSize() != null && req.getKgBatchSize() > 0)
            doc.setKgBatchSize(req.getKgBatchSize());
        if (req.getKgMaxConcurrentWrites() != null && req.getKgMaxConcurrentWrites() > 0)
            doc.setKgMaxConcurrentWrites(req.getKgMaxConcurrentWrites());

        doc.setUpdatedAt(Instant.now());
        repository.save(doc);
        return getSettings();
    }
}

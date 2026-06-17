package hu.fmdev.backend.service;

import hu.fmdev.backend.config.RagConfig;
import hu.fmdev.backend.domain.AppSettings;
import hu.fmdev.backend.dto.AppSettingsDto;
import hu.fmdev.backend.repository.AppSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppSettingsServiceTest {

    @Mock private AppSettingsRepository repository;
    private RagConfig ragConfig;

    private AppSettingsService service;

    @BeforeEach
    void setUp() {
        ragConfig = new RagConfig();
        ragConfig.setOllamaBaseUrl("http://localhost:11434");
        ragConfig.setChatModel("llama3.1:8b");
        ragConfig.setChatMaxHistoryTurns(6);
        ragConfig.setChatContextTopK(8);

        service = new AppSettingsService(repository, ragConfig);
        ReflectionTestUtils.setField(service, "kgDefaultBatchSize", 100);
        ReflectionTestUtils.setField(service, "kgDefaultMaxConcurrentWrites", 4);
    }

    @Test
    void getEffectiveChatModel_noOverride_fallsBackToConfig() {
        when(repository.findById("singleton")).thenReturn(Optional.empty());

        assertEquals("llama3.1:8b", service.getEffectiveChatModel());
    }

    @Test
    void getEffectiveChatModel_withOverride_usesDbValue() {
        AppSettings settings = new AppSettings();
        settings.setChatModel("qwen2.5:14b");
        when(repository.findById("singleton")).thenReturn(Optional.of(settings));

        assertEquals("qwen2.5:14b", service.getEffectiveChatModel());
    }

    @Test
    void getEffectiveChatModel_blankOverride_fallsBackToConfig() {
        AppSettings settings = new AppSettings();
        settings.setChatModel("   ");
        when(repository.findById("singleton")).thenReturn(Optional.of(settings));

        assertEquals("llama3.1:8b", service.getEffectiveChatModel());
    }

    @Test
    void getEffectiveNerModel_noOverride_fallsBackToChatModelConfig() {
        when(repository.findById("singleton")).thenReturn(Optional.empty());

        assertEquals("llama3.1:8b", service.getEffectiveNerModel());
    }

    @Test
    void getEffectiveNerModel_withOverride_usesDbValue() {
        AppSettings settings = new AppSettings();
        settings.setNerModel("mistral:7b");
        when(repository.findById("singleton")).thenReturn(Optional.of(settings));

        assertEquals("mistral:7b", service.getEffectiveNerModel());
    }

    @Test
    void getEffectiveChatMaxHistoryTurns_noOverride_fallsBackToConfig() {
        when(repository.findById("singleton")).thenReturn(Optional.empty());

        assertEquals(6, service.getEffectiveChatMaxHistoryTurns());
    }

    @Test
    void getEffectiveChatMaxHistoryTurns_zeroOverride_fallsBackToConfig() {
        AppSettings settings = new AppSettings();
        settings.setChatMaxHistoryTurns(0);
        when(repository.findById("singleton")).thenReturn(Optional.of(settings));

        assertEquals(6, service.getEffectiveChatMaxHistoryTurns());
    }

    @Test
    void getEffectiveChatContextTopK_noOverride_fallsBackToConfig() {
        when(repository.findById("singleton")).thenReturn(Optional.empty());

        assertEquals(8, service.getEffectiveChatContextTopK());
    }

    @Test
    void getEffectiveChatContextTopK_withOverride_usesDbValue() {
        AppSettings settings = new AppSettings();
        settings.setChatContextTopK(15);
        when(repository.findById("singleton")).thenReturn(Optional.of(settings));

        assertEquals(15, service.getEffectiveChatContextTopK());
    }

    @Test
    void getEffectiveKgBatchSize_noOverride_fallsBackToValueDefault() {
        when(repository.findById("singleton")).thenReturn(Optional.empty());

        assertEquals(100, service.getEffectiveKgBatchSize());
    }

    @Test
    void getEffectiveKgBatchSize_negativeOverride_fallsBackToDefault() {
        AppSettings settings = new AppSettings();
        settings.setKgBatchSize(-5);
        when(repository.findById("singleton")).thenReturn(Optional.of(settings));

        assertEquals(100, service.getEffectiveKgBatchSize());
    }

    @Test
    void getEffectiveKgMaxConcurrentWrites_withOverride_usesDbValue() {
        AppSettings settings = new AppSettings();
        settings.setKgMaxConcurrentWrites(8);
        when(repository.findById("singleton")).thenReturn(Optional.of(settings));

        assertEquals(8, service.getEffectiveKgMaxConcurrentWrites());
    }

    @Test
    void getSettings_buildsDtoFromAllEffectiveValues() {
        when(repository.findById("singleton")).thenReturn(Optional.empty());

        AppSettingsDto dto = service.getSettings();

        assertEquals("http://localhost:11434", dto.getOllamaBaseUrl());
        assertEquals("llama3.1:8b", dto.getChatModel());
        assertEquals(6, dto.getChatMaxHistoryTurns());
        assertEquals(8, dto.getChatContextTopK());
        assertEquals(100, dto.getKgBatchSize());
        assertEquals(4, dto.getKgMaxConcurrentWrites());
    }

    @Test
    void saveSettings_partialUpdate_onlySetsProvidedFields() {
        AppSettings existing = new AppSettings();
        existing.setChatModel("old-model");
        existing.setChatContextTopK(8);
        when(repository.findById("singleton")).thenReturn(Optional.of(existing));

        AppSettingsDto req = new AppSettingsDto();
        req.setChatModel("new-model");
        // chatContextTopK left null -> should not be overwritten

        service.saveSettings(req);

        ArgumentCaptor<AppSettings> captor = ArgumentCaptor.forClass(AppSettings.class);
        verify(repository).save(captor.capture());
        assertEquals("new-model", captor.getValue().getChatModel());
        assertEquals(8, captor.getValue().getChatContextTopK());
        assertNotNull(captor.getValue().getUpdatedAt());
    }

    @Test
    void saveSettings_blankOrNonPositiveValues_areIgnored() {
        AppSettings existing = new AppSettings();
        existing.setChatModel("kept-model");
        existing.setChatContextTopK(8);
        when(repository.findById("singleton")).thenReturn(Optional.of(existing));

        AppSettingsDto req = new AppSettingsDto();
        req.setChatModel("  ");
        req.setChatContextTopK(-1);

        service.saveSettings(req);

        ArgumentCaptor<AppSettings> captor = ArgumentCaptor.forClass(AppSettings.class);
        verify(repository).save(captor.capture());
        assertEquals("kept-model", captor.getValue().getChatModel());
        assertEquals(8, captor.getValue().getChatContextTopK());
    }

    @Test
    void saveSettings_noExistingDocument_createsNewOne() {
        when(repository.findById("singleton")).thenReturn(Optional.empty());
        AppSettingsDto req = new AppSettingsDto();
        req.setKgBatchSize(200);

        service.saveSettings(req);

        ArgumentCaptor<AppSettings> captor = ArgumentCaptor.forClass(AppSettings.class);
        verify(repository).save(captor.capture());
        assertEquals(200, captor.getValue().getKgBatchSize());
    }
}

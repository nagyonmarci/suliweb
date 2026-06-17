package hu.fmdev.backend.dto;

import lombok.Data;

@Data
public class AppSettingsDto {
    private String ollamaBaseUrl;
    private String chatModel;
    private String nerModel;
    private Integer chatMaxHistoryTurns;
    private Integer chatContextTopK;
    private Integer kgBatchSize;
    private Integer kgMaxConcurrentWrites;
}

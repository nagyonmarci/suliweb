package hu.fmdev.backend.dto;

import lombok.Data;

@Data
public class SynologySettingsResponse {
    private String host;
    private String username;
    private boolean passwordConfigured;
    private String pathPrefix;
    private String localMountPrefix;
    private String searchExtensions;
    private Integer batchSize;
}

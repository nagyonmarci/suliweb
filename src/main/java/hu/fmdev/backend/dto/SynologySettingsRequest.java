package hu.fmdev.backend.dto;

import lombok.Data;

@Data
public class SynologySettingsRequest {
    private String host;
    private String username;
    private String password;
    private String pathPrefix;
    private String localMountPrefix;
    private String searchExtensions;
    private Integer batchSize;
}

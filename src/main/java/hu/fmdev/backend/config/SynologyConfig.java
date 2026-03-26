package hu.fmdev.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "synology")
public class SynologyConfig {
    private String host;
    private String username;
    private String password;
    private String pathPrefix = "/volume1";
    private String localMountPrefix = "/mnt/nas";
    private String searchExtensions = "pst,ost";
    private int batchSize = 100;
}

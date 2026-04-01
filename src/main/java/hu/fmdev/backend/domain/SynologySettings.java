package hu.fmdev.backend.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Document(collection = "synology_settings")
public class SynologySettings {

    @Id
    private String id = "singleton";

    private String host;
    private String username;
    private String password;
    private String pathPrefix;
    private String localMountPrefix;
    private String searchExtensions;
    private Integer batchSize;
    private LocalDateTime updatedAt;
}

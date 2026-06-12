package hu.fmdev.backend.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@Document(collection = "app_settings")
public class AppSettings {

    @Id
    private String id = "singleton";

    private String ollamaBaseUrl;
    private String chatModel;
    private String nerModel;
    private Integer chatMaxHistoryTurns;
    private Integer kgBatchSize;
    private Integer kgMaxConcurrentWrites;

    private Instant updatedAt;
}

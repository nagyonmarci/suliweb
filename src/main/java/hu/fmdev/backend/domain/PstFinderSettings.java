package hu.fmdev.backend.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Data
@NoArgsConstructor
@Document(collection = "pst_finder_settings")
public class PstFinderSettings {

    @Id
    private String id = "singleton";

    private List<String> searchDirectories = List.of();
    private List<String> excludedDirectories = List.of();
}

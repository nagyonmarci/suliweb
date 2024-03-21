package hu.fmdev.backend.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;

import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "fileInfo")
public class FileInfo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private String id;

    private String path;
    private long size;
    private LocalDateTime lastModified;
    private String status;

    public FileInfo(String path, long size, LocalDateTime lastModified, String status) {
        this.path = path;
        this.size = size;
        this.lastModified = lastModified;
        this.status = status;
    }
}

package hu.fmdev.backend.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Document(collection = "fileInfo")
public class FileInfo {
    @Id
    private String id;
    private String path;
    private String fileName;
    private long size;
    private LocalDateTime lastModified;
    private String status;

    public FileInfo(String path, String fileName, long size, LocalDateTime lastModified, String status) {
        this.path = path;
        this.fileName = fileName;
        this.size = size;
        this.lastModified = lastModified;
        this.status = status;
    }
}

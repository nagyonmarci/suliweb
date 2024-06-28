package hu.fmdev.backend.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "fileInfo")
public class FileInfo {
    @Id
    private String id;
    private String path;
    private long size;
    private LocalDateTime lastModified;
    private String status; // Add this field

    // Constructor
    public FileInfo() {
    }

    public FileInfo(String path, long size, LocalDateTime lastModified, String status) {
        this.path = path;
        this.size = size;
        this.lastModified = lastModified;
        this.status = status;
    }

    // Getters and setters
    // ...

    // Getters and setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public LocalDateTime getLastModified() {
        return lastModified;
    }

    public void setLastModified(LocalDateTime lastModified) {
        this.lastModified = lastModified;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

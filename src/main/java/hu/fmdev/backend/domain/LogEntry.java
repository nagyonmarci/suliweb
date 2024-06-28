package hu.fmdev.backend.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "log_entries")
public class LogEntry {
    @Id
    private String id;
    private LocalDateTime timestamp;
    private String level;
    private String message;
    private String stackTrace;

    // Getters and setters

    public LogEntry() {
    }

    public LogEntry(LocalDateTime timestamp, String level, String message, String stackTrace) {
        this.timestamp = timestamp;
        this.level = level;
        this.message = message;
        this.stackTrace = stackTrace;
    }
}
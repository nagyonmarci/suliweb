package hu.fmdev.backend.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "log_entries")
public class LogEntry {
    @Id
    private String id;
    private Instant timestamp;
    private String level;
    private String message;
    private String stackTrace;

    public String getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getLevel() { return level; }
    public String getMessage() { return message; }
    public String getStackTrace() { return stackTrace; }

    public LogEntry() {
    }

    public LogEntry(Instant timestamp, String level, String message, String stackTrace) {
        this.timestamp = timestamp;
        this.level = level;
        this.message = message;
        this.stackTrace = stackTrace;
    }
}

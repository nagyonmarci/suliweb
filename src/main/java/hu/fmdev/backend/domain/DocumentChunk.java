package hu.fmdev.backend.domain;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Document(collection = "document_chunks")
public class DocumentChunk {
    @Id
    private String id;

    @Indexed
    private String emailId;

    @Indexed
    private String sourceType; // "email_body", "email_subject", "attachment"

    private String attachmentPath; // null for email body chunks

    private String attachmentFileName;

    private int chunkIndex;

    private String content;

    private List<Double> embedding;

    // Metadata for context reconstruction
    private String emailSubject;
    private String senderName;
    private String senderEmailAddress;
    private LocalDateTime emailReceivedTime;
    private String pstFileName;
    private String folderPath;

    @Indexed
    private String ingestionStatus; // "pending", "embedded", "failed"

    private LocalDateTime createdAt;
    private LocalDateTime embeddedAt;
}

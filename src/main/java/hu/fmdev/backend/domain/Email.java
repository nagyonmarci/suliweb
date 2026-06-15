package hu.fmdev.backend.domain;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Document(collection = "emails")
public class Email {
    @Id
    private String id;
    private String uniqueEntryId;
    private String pstFileName;
    private String folderPath;
    private String senderEmailAddress;
    private String senderName;
    private String subject;
    private LocalDateTime receivedTime;
    private String body;
    private String htmlContent;
    private String strippedBody;
    private List<String> recipients;
    private List<String> cc;
    private List<String> bcc;
    private List<String> attachmentPaths;
    private String status;

    // --- Extended Metadata ---
    private String internetMessageId;
    private String conversationTopic;
    private String conversationId;
    private Boolean isRead;
}
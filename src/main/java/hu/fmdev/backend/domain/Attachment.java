package hu.fmdev.backend.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document(collection = "attachments")
@NoArgsConstructor
public class Attachment {
    @Id
    private String id;
    private String emailId;
    private String filename;
    private String contentType;
    private long size;
    private String localPath;
    private String hash;
    private String pstFileName;
    private LocalDateTime creationTime;
    
    // Email context for easy display
    private String emailSubject;
    private String senderName;
    private LocalDateTime receivedTime;
}

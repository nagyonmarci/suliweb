package hu.fmdev.backend.domain;

import lombok.Data;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;

@Document
@Data
@Setter
public class Email {
    @Id
    private String id;
    private String pstFileName;
    private String folderPath;
    private String senderEmailAddress;
    private String senderName;
    private List<String> recipients;
    private List<String> cc;
    private List<String> bcc;
    private String subject;
    private Date receivedTime;
    private String body;
}
package hu.fmdev.backend.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

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
    private List<String> recipients;
    private List<String> cc;
    private List<String> bcc;
    private List<String> attachmentPaths;
    private String status; // Add this field

    // Getters and setters
    // ...

    // Constructor
    public Email() {
    }

    public Email(String uniqueEntryId, String pstFileName, String folderPath, String senderEmailAddress, String senderName, String subject, LocalDateTime receivedTime, String body, String htmlContent, List<String> recipients, List<String> cc, List<String> bcc, List<String> attachmentPaths, String status) {
        this.uniqueEntryId = uniqueEntryId;
        this.pstFileName = pstFileName;
        this.folderPath = folderPath;
        this.senderEmailAddress = senderEmailAddress;
        this.senderName = senderName;
        this.subject = subject;
        this.receivedTime = receivedTime;
        this.body = body;
        this.htmlContent = htmlContent;
        this.recipients = recipients;
        this.cc = cc;
        this.bcc = bcc;
        this.attachmentPaths = attachmentPaths;
        this.status = status;
    }

    // Getters and setters
    // ...

    public String getUniqueEntryId() {
        return uniqueEntryId;
    }

    public void setUniqueEntryId(String uniqueEntryId) {
        this.uniqueEntryId = uniqueEntryId;
    }

    // Other getters and setters
    // ...

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPstFileName() {
        return pstFileName;
    }

    public void setPstFileName(String pstFileName) {
        this.pstFileName = pstFileName;
    }

    public String getFolderPath() {
        return folderPath;
    }

    public void setFolderPath(String folderPath) {
        this.folderPath = folderPath;
    }

    public String getSenderEmailAddress() {
        return senderEmailAddress;
    }

    public void setSenderEmailAddress(String senderEmailAddress) {
        this.senderEmailAddress = senderEmailAddress;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public LocalDateTime getReceivedTime() {
        return receivedTime;
    }

    public void setReceivedTime(LocalDateTime receivedTime) {
        this.receivedTime = receivedTime;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getHtmlContent() {
        return htmlContent;
    }

    public void setHtmlContent(String htmlContent) {
        this.htmlContent = htmlContent;
    }

    public List<String> getRecipients() {
        return recipients;
    }

    public void setRecipients(List<String> recipients) {
        this.recipients = recipients;
    }

    public List<String> getCc() {
        return cc;
    }

    public void setCc(List<String> cc) {
        this.cc = cc;
    }

    public List<String> getBcc() {
        return bcc;
    }

    public void setBcc(List<String> bcc) {
        this.bcc = bcc;
    }

    public List<String> getAttachmentPaths() {
        return attachmentPaths;
    }

    public void setAttachmentPaths(List<String> attachmentPaths) {
        this.attachmentPaths = attachmentPaths;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

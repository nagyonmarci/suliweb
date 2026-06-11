package hu.fmdev.backend.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "failed_conversions")
public class FailedConversion {

    public enum FailureType { REPLY_STRIP, ATTACHMENT_CONVERT }

    @Id
    private String id;
    private String mongoEmailId;
    private String messageId;
    private String attachmentHash;
    private String attachmentFilename;
    @Indexed
    private FailureType failureType;
    private String errorMessage;
    private Instant occurredAt;
    private int retryCount;
    @Indexed
    private boolean resolved;

    public static FailedConversion replyStrip(String mongoEmailId, String messageId, String errorMessage) {
        FailedConversion fc = new FailedConversion();
        fc.mongoEmailId = mongoEmailId;
        fc.messageId = messageId;
        fc.failureType = FailureType.REPLY_STRIP;
        fc.errorMessage = errorMessage;
        fc.occurredAt = Instant.now();
        fc.retryCount = 0;
        fc.resolved = false;
        return fc;
    }

    public static FailedConversion attachmentConvert(String mongoEmailId, String messageId,
                                                      String hash, String filename, String errorMessage) {
        FailedConversion fc = new FailedConversion();
        fc.mongoEmailId = mongoEmailId;
        fc.messageId = messageId;
        fc.attachmentHash = hash;
        fc.attachmentFilename = filename;
        fc.failureType = FailureType.ATTACHMENT_CONVERT;
        fc.errorMessage = errorMessage;
        fc.occurredAt = Instant.now();
        fc.retryCount = 0;
        fc.resolved = false;
        return fc;
    }

    public String getId() { return id; }
    public String getMongoEmailId() { return mongoEmailId; }
    public String getMessageId() { return messageId; }
    public String getAttachmentHash() { return attachmentHash; }
    public String getAttachmentFilename() { return attachmentFilename; }
    public FailureType getFailureType() { return failureType; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getOccurredAt() { return occurredAt; }
    public int getRetryCount() { return retryCount; }
    public boolean isResolved() { return resolved; }

    public void markResolved() { this.resolved = true; }
    public void incrementRetry(String newError) {
        this.retryCount++;
        this.errorMessage = newError;
    }
}

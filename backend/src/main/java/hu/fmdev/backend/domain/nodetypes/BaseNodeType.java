package hu.fmdev.backend.domain.nodetypes;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document
public abstract class BaseNodeType {

    @Id
    private String id;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public BaseNodeType() {
        this.createdAt = LocalDateTime.now();
    }

    public void setUpdatedAt() {
        this.updatedAt = LocalDateTime.now();
    }
}

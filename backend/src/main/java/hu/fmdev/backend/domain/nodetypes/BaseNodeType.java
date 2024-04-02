package hu.fmdev.backend.domain.nodetypes;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
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

package hu.fmdev.backend.domain.nodetypes;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.mongodb.core.mapping.Document;

@EqualsAndHashCode(callSuper = true)
@Data
@Document
public class Institution extends BaseNodeType {
    private String name;
    private String address;
}

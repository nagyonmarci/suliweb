package hu.fmdev.backend.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;

import javax.persistence.Id;

@Data
@Document(collection = "organizations")
@NoArgsConstructor
public class Organization {
    @Id
    private String id;

    private String name;
}
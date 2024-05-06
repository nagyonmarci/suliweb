package hu.fmdev.backend.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;

import javax.persistence.Id;

@Data
@Document(collection = "authorities")
@NoArgsConstructor
public class Authority {
    @Id
    private String id;

    private String permission; // például "CONTRACTS_EDIT", "CONTRACTS_READ", "REQUESTS_SUBMIT", "REQUESTS_READ"

}

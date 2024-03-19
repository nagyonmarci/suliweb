package hu.fmdev.backend.domain;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document
@Data
public class Email {
    @Id
    private String id;
    private String sender;
    private String subject;
}


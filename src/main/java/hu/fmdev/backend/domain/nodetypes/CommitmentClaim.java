package hu.fmdev.backend.domain.nodetypes;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDate;

@EqualsAndHashCode(callSuper = true)
@Data
@Document
public class CommitmentClaim extends BaseNodeType {
    private String commitmentDemandTopic;

    private Integer amount; // Kötelezettségvállalás összege

    private PaymentMethod paymentMethod; // Fizetés módja

    @DBRef
    private ServiceProvider serviceProvider; // Szolgáltató

    private String reason; // Kötelezettségvállalás indoklása, max 1000 karakter hosszúságú szöveg

    private LocalDate dueDate; // Teljesítési határidő

    private Status status; // Státusz

    private String projectIdentifier; // Pályázati azonosító

    private String comment; // Megjegyzés, max 1000 karakter hosszúságú szöveg

    public enum PaymentMethod {
        TRANSFER, CASH
    }

    public enum Status {
        UNDER_SIGNATURE, COMPLETED, APPROVED, ERROR, REVOKED, REJECTED
    }
}

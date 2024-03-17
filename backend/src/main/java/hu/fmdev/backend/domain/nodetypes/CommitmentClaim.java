package hu.fmdev.backend.domain.nodetypes;

import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDate;

@Entity
@NoArgsConstructor
public class CommitmentClaim extends BaseNodeType {
    private String CommitmentDemandTopic;

    private Integer amount; // Kötelezettségvállalás összege

    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod; // Fizetés módja

    // Szolgáltató - feltételezve, hogy van egy ServiceProvider entitás
    @ManyToOne
    private ServiceProvider serviceProvider;

    @Column(length = 1000)
    private String reason; // Kötelezettségvállalás indoklása

    private LocalDate dueDate; // Teljesítési határidő

    @Enumerated(EnumType.STRING)
    private Status status; // Státusz

    private String projectIdentifier; // Pályázati azonosító

    @Column(length = 1000)
    private String comment; // Megjegyzés


    public enum PaymentMethod {
        TRANSFER, CASH
    }

    public enum Status {
        UNDER_SIGNATURE, COMPLETED, APPROVED, ERROR, REVOKED, REJECTED
    }
}

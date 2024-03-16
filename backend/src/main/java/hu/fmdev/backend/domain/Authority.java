package hu.fmdev.backend.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

@Entity
@Table(name = "authorities")
@Data
@NoArgsConstructor
public class Authority {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String permission; // például "CONTRACTS_EDIT", "CONTRACTS_READ", "REQUESTS_SUBMIT", "REQUESTS_READ"

}

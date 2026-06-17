package hu.fmdev.backend.domain.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.*;

import java.util.List;

@Data
@Node("Claim")
public class ClaimNode {

    @Id @GeneratedValue
    private Long id;

    // Free-text statement of the claim
    @Property("text")
    private String text;

    // e.g. FACTUAL | CAUSAL | NORMATIVE | SPECULATIVE
    @Property("claimType")
    private String claimType;

    // 0.0 – 1.0, set by the extraction model
    @Property("confidence")
    private Double confidence;

    @Relationship(type = "SUPPORTED_BY", direction = Relationship.Direction.OUTGOING)
    private List<EvidenceNode> evidence;

    @Relationship(type = "INVOLVES", direction = Relationship.Direction.OUTGOING)
    private List<MechanismNode> mechanisms;
}

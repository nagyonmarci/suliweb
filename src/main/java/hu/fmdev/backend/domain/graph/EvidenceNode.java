package hu.fmdev.backend.domain.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.*;

@Data
@Node("Evidence")
public class EvidenceNode {

    @Id @GeneratedValue
    private Long id;

    // Quoted or paraphrased evidence text
    @Property("text")
    private String text;

    // e.g. DIRECT_QUOTE | TABLE | FIGURE | CITATION | INFERENCE
    @Property("evidenceType")
    private String evidenceType;

    // Reference to source (mongoId, attachment hash, URL, citation string)
    @Property("sourceRef")
    private String sourceRef;
}

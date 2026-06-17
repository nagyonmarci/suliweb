package hu.fmdev.backend.domain.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.*;

@Data
@Node("Mechanism")
public class MechanismNode {

    @Id @GeneratedValue
    private Long id;

    @Property("name")
    private String name;

    @Property("description")
    private String description;

    // e.g. BIOLOGICAL | COMPUTATIONAL | LEGAL | FINANCIAL | ORGANIZATIONAL
    @Property("mechanismType")
    private String mechanismType;
}

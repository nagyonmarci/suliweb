package hu.fmdev.backend.domain.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.*;

@Data
@Node("Concept")
public class ConceptNode {

    @Id @GeneratedValue
    private Long id;

    @Property("name")
    private String name;

    // PERSON | ORG | TOPIC | LOCATION
    @Property("type")
    private String type;
}

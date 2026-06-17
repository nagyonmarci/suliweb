package hu.fmdev.backend.domain.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.*;

@Data
@Node("MethodLineage")
public class MethodLineageNode {

    @Id @GeneratedValue
    private Long id;

    @Property("methodName")
    private String methodName;

    @Property("version")
    private String version;

    @Property("description")
    private String description;

    // Self-referential: this method extends a prior one in the lineage chain
    @Relationship(type = "EXTENDS", direction = Relationship.Direction.OUTGOING)
    private MethodLineageNode extendsMethod;
}

package hu.fmdev.backend.domain.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.*;

@Data
@Node("Thread")
public class ThreadNode {

    @Id @GeneratedValue
    private Long id;

    @Property("threadId")
    private String threadId;

    @Property("subject")
    private String subject;

    @Property("lastActivity")
    private String lastActivity;

    @Relationship(type = "EXTENDS", direction = Relationship.Direction.OUTGOING)
    private java.util.List<MethodLineageNode> methodLineages;
}

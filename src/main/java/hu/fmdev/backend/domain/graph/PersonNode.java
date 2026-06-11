package hu.fmdev.backend.domain.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.*;


import java.util.List;

@Data
@Node("Person")
public class PersonNode {

    @Id @GeneratedValue
    private Long id;

    @Property("email")
    private String email;

    @Property("name")
    private String name;

    @Property("organization")
    private String organization;

    @Relationship(type = "COMMUNICATES_WITH", direction = Relationship.Direction.OUTGOING)
    private List<CommunicatesWithRel> communicatesWith;
}

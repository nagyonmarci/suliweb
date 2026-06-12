package hu.fmdev.backend.domain.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.*;

@Data
@RelationshipProperties
public class CommunicatesWithRel {

    @RelationshipId
    private Long id;

    @TargetNode
    private PersonNode target;

    private long count;
    private String lastDate;
}

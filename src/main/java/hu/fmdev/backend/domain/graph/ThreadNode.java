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
}

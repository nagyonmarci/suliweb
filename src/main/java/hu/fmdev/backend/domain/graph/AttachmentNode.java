package hu.fmdev.backend.domain.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.*;

import java.util.List;

@Data
@Node("Attachment")
public class AttachmentNode {

    @Id @GeneratedValue
    private Long id;

    @Property("sha256")
    private String sha256;

    @Property("filename")
    private String filename;

    @Property("markdownContent")
    private String markdownContent;

    @Relationship(type = "REFERENCES", direction = Relationship.Direction.OUTGOING)
    private List<ConceptNode> references;
}

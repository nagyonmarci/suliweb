package hu.fmdev.backend.domain.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.*;

import java.util.List;

@Data
@Node("Email")
public class EmailNode {

    @Id @GeneratedValue
    private Long id;

    @Property("messageId")
    private String messageId;

    @Property("mongoId")
    private String mongoId;

    @Property("subject")
    private String subject;

    @Property("bodyDelta")
    private String bodyDelta;

    @Property("date")
    private String date;

    @Property("pstFileName")
    private String pstFileName;

    @Property("pstOwner")
    private String pstOwner;

    @Relationship(type = "SENT", direction = Relationship.Direction.INCOMING)
    private PersonNode sender;

    @Relationship(type = "TO", direction = Relationship.Direction.OUTGOING)
    private List<PersonNode> toRecipients;

    @Relationship(type = "CC", direction = Relationship.Direction.OUTGOING)
    private List<PersonNode> ccRecipients;

    @Relationship(type = "BELONGS_TO", direction = Relationship.Direction.OUTGOING)
    private ThreadNode thread;

    @Relationship(type = "REPLY_TO", direction = Relationship.Direction.OUTGOING)
    private EmailNode replyTo;

    @Relationship(type = "HAS_ATTACHMENT", direction = Relationship.Direction.OUTGOING)
    private List<AttachmentNode> attachments;

    @Relationship(type = "MENTIONS", direction = Relationship.Direction.OUTGOING)
    private List<ConceptNode> mentions;

    @Relationship(type = "PROVES", direction = Relationship.Direction.OUTGOING)
    private List<ClaimNode> provedClaims;

    @Relationship(type = "CONTRADICTS", direction = Relationship.Direction.OUTGOING)
    private List<ClaimNode> contradictedClaims;
}

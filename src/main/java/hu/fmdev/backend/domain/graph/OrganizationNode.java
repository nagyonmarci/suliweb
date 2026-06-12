package hu.fmdev.backend.domain.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.*;

@Data
@Node("Organization")
public class OrganizationNode {

    @Id @GeneratedValue
    private Long id;

    @Property("name")
    private String name;

    @Property("domain")
    private String domain;
}

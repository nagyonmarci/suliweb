package hu.fmdev.backend.repository.graph;

import hu.fmdev.backend.domain.graph.EvidenceNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;

import java.util.List;

public interface EvidenceNodeRepository extends Neo4jRepository<EvidenceNode, Long> {

    List<EvidenceNode> findByEvidenceType(String evidenceType);

    List<EvidenceNode> findBySourceRef(String sourceRef);
}

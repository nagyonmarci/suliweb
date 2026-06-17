package hu.fmdev.backend.repository.graph;

import hu.fmdev.backend.domain.graph.ClaimNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;

import java.util.List;

public interface ClaimNodeRepository extends Neo4jRepository<ClaimNode, Long> {

    List<ClaimNode> findByClaimType(String claimType);

    List<ClaimNode> findByConfidenceGreaterThanEqual(Double threshold);
}

package hu.fmdev.backend.repository.graph;

import hu.fmdev.backend.domain.graph.ConceptNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;

import java.util.Optional;

public interface ConceptNodeRepository extends Neo4jRepository<ConceptNode, Long> {

    Optional<ConceptNode> findByNameIgnoreCase(String name);
}

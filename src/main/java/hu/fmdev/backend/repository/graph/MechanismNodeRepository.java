package hu.fmdev.backend.repository.graph;

import hu.fmdev.backend.domain.graph.MechanismNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;

import java.util.List;

public interface MechanismNodeRepository extends Neo4jRepository<MechanismNode, Long> {

    List<MechanismNode> findByMechanismType(String mechanismType);

    List<MechanismNode> findByNameContainingIgnoreCase(String namePart);
}

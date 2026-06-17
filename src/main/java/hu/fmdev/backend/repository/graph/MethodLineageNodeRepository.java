package hu.fmdev.backend.repository.graph;

import hu.fmdev.backend.domain.graph.MethodLineageNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;

import java.util.List;
import java.util.Optional;

public interface MethodLineageNodeRepository extends Neo4jRepository<MethodLineageNode, Long> {

    Optional<MethodLineageNode> findByMethodNameAndVersion(String methodName, String version);

    List<MethodLineageNode> findByMethodName(String methodName);
}

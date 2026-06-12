package hu.fmdev.backend.repository.graph;

import hu.fmdev.backend.domain.graph.ThreadNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;

import java.util.Optional;

public interface ThreadNodeRepository extends Neo4jRepository<ThreadNode, Long> {

    Optional<ThreadNode> findByThreadId(String threadId);
}

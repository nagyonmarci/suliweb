package hu.fmdev.backend.repository.graph;

import hu.fmdev.backend.domain.graph.EmailNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface EmailNodeRepository extends Neo4jRepository<EmailNode, Long> {

    Optional<EmailNode> findByMessageId(String messageId);

    boolean existsByMessageId(String messageId);

    @Query("MATCH (e:Email)-[:BELONGS_TO]->(t:Thread {threadId: $threadId}) " +
           "RETURN e ORDER BY e.date ASC")
    List<EmailNode> findByThreadId(String threadId);

    @Query("MATCH (e:Email)-[:MENTIONS]->(c:Concept) " +
           "WHERE c.name CONTAINS $conceptName " +
           "RETURN e ORDER BY e.date DESC LIMIT $limit")
    List<EmailNode> findByConceptProximity(String conceptName, int limit);

    @Query("MATCH (e:Email {mongoId: $mongoId}) " +
           "OPTIONAL MATCH (e)-[r:MENTIONS]->(:Concept) DELETE r " +
           "WITH e UNWIND $concepts AS c " +
           "MERGE (concept:Concept {name: c.name}) ON CREATE SET concept.type = c.type " +
           "MERGE (e)-[:MENTIONS]->(concept)")
    void replaceMentions(@Param("mongoId") String mongoId,
                         @Param("concepts") List<Map<String, String>> concepts);
}

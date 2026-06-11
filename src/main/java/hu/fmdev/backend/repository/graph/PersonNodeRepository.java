package hu.fmdev.backend.repository.graph;

import hu.fmdev.backend.domain.graph.PersonNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.List;
import java.util.Optional;

public interface PersonNodeRepository extends Neo4jRepository<PersonNode, Long> {

    Optional<PersonNode> findByEmail(String email);

    @Query("MATCH (p:Person {email: $email})-[:COMMUNICATES_WITH]->(partner:Person) " +
           "RETURN partner ORDER BY partner.name")
    List<PersonNode> findCommunicationPartners(String email);

    @Query("MATCH (p:Person {email: $email})-[r:COMMUNICATES_WITH]->(partner:Person) " +
           "WHERE r.lastDate >= $fromDate AND r.lastDate <= $toDate " +
           "RETURN partner ORDER BY r.count DESC")
    List<PersonNode> findCommunicationPartnersInRange(String email, String fromDate, String toDate);
}

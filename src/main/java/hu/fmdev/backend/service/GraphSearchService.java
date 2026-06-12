package hu.fmdev.backend.service;

import hu.fmdev.backend.domain.graph.EmailNode;
import hu.fmdev.backend.domain.graph.PersonNode;
import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.repository.graph.EmailNodeRepository;
import hu.fmdev.backend.repository.graph.PersonNodeRepository;
import org.neo4j.driver.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class GraphSearchService {

    public record ConceptStat(String name, long count) {}
    public record PersonStat(String email, String name, long count) {}
    public record GraphStats(
            List<ConceptStat> topTopics,
            List<ConceptStat> topOrgs,
            List<PersonStat> topSenders,
            long personCount,
            long emailCount,
            long conceptCount
    ) {}

    private final PersonNodeRepository personRepo;
    private final EmailNodeRepository emailNodeRepo;
    private final Neo4jClient neo4jClient;

    public GraphSearchService(PersonNodeRepository personRepo,
                              EmailNodeRepository emailNodeRepo,
                              Neo4jClient neo4jClient) {
        this.personRepo    = personRepo;
        this.emailNodeRepo = emailNodeRepo;
        this.neo4jClient   = neo4jClient;
    }

    public List<PersonNode> findCommunicationPartners(String email,
                                                      LocalDate from, LocalDate to) {
        try {
            if (from != null && to != null) {
                return personRepo.findCommunicationPartnersInRange(
                        email, from.toString(), to.toString());
            }
            return personRepo.findCommunicationPartners(email);
        } catch (Exception e) {
            CentralLogger.logError("KG findCommunicationPartners hiba", e);
            return List.of();
        }
    }

    public List<EmailNode> getThreadEmails(String threadId) {
        try {
            return emailNodeRepo.findByThreadId(threadId);
        } catch (Exception e) {
            CentralLogger.logError("KG getThreadEmails hiba", e);
            return List.of();
        }
    }

    public List<EmailNode> findEmailsByConceptProximity(String conceptName, int topK) {
        try {
            return emailNodeRepo.findByConceptProximity(conceptName, topK);
        } catch (Exception e) {
            CentralLogger.logError("KG findEmailsByConceptProximity hiba", e);
            return List.of();
        }
    }

    public GraphStats getGraphStats() {
        try {
            List<ConceptStat> topTopics = neo4jClient.query(
                    "MATCH (c:Concept {type:'TOPIC'})<-[:MENTIONS]-(e:Email) " +
                    "RETURN c.name AS name, count(e) AS cnt ORDER BY cnt DESC LIMIT 10")
                    .fetch().all().stream()
                    .map(r -> new ConceptStat((String) r.get("name"), toLong(r.get("cnt"))))
                    .toList();

            List<ConceptStat> topOrgs = neo4jClient.query(
                    "MATCH (c:Concept {type:'ORG'})<-[:MENTIONS]-(e:Email) " +
                    "RETURN c.name AS name, count(e) AS cnt ORDER BY cnt DESC LIMIT 10")
                    .fetch().all().stream()
                    .map(r -> new ConceptStat((String) r.get("name"), toLong(r.get("cnt"))))
                    .toList();

            List<PersonStat> topSenders = neo4jClient.query(
                    "MATCH (p:Person)-[:SENT]->(e:Email) " +
                    "RETURN p.email AS email, p.name AS name, count(e) AS cnt ORDER BY cnt DESC LIMIT 10")
                    .fetch().all().stream()
                    .map(r -> new PersonStat((String) r.get("email"), (String) r.get("name"), toLong(r.get("cnt"))))
                    .toList();

            long personCount  = countNodes("Person");
            long emailCount   = countNodes("Email");
            long conceptCount = countNodes("Concept");

            return new GraphStats(topTopics, topOrgs, topSenders, personCount, emailCount, conceptCount);
        } catch (Exception e) {
            CentralLogger.logError("KG getGraphStats hiba", e);
            return new GraphStats(List.of(), List.of(), List.of(), 0, 0, 0);
        }
    }

    private long countNodes(String label) {
        return neo4jClient.query("MATCH (n:" + label + ") RETURN count(n) AS cnt")
                .fetch().one()
                .map(r -> toLong(r.get("cnt")))
                .orElse(0L);
    }

    private long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Long l) return l;
        if (value instanceof Number n) return n.longValue();
        if (value instanceof Value v) return v.asLong();
        return 0L;
    }
}

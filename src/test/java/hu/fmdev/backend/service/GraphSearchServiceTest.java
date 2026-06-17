package hu.fmdev.backend.service;

import hu.fmdev.backend.domain.graph.EmailNode;
import hu.fmdev.backend.domain.graph.PersonNode;
import hu.fmdev.backend.repository.graph.EmailNodeRepository;
import hu.fmdev.backend.repository.graph.PersonNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GraphSearchServiceTest {

    @Mock private PersonNodeRepository personRepo;
    @Mock private EmailNodeRepository emailNodeRepo;
    private Neo4jClient neo4jClient;

    private GraphSearchService service;

    @BeforeEach
    void setUp() {
        neo4jClient = mock(Neo4jClient.class, withSettings().defaultAnswer(org.mockito.Answers.RETURNS_DEEP_STUBS));
        service = new GraphSearchService(personRepo, emailNodeRepo, neo4jClient);
    }

    @Test
    void findCommunicationPartners_noDateRange_usesSimpleQuery() {
        PersonNode p = new PersonNode();
        p.setEmail("a@b.com");
        when(personRepo.findCommunicationPartners("a@b.com")).thenReturn(List.of(p));

        List<PersonNode> result = service.findCommunicationPartners("a@b.com", null, null);

        assertEquals(1, result.size());
        verify(personRepo, never()).findCommunicationPartnersInRange(any(), any(), any());
    }

    @Test
    void findCommunicationPartners_withDateRange_usesRangeQuery() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 31);
        when(personRepo.findCommunicationPartnersInRange("a@b.com", "2026-01-01", "2026-01-31"))
                .thenReturn(List.of());

        service.findCommunicationPartners("a@b.com", from, to);

        verify(personRepo).findCommunicationPartnersInRange("a@b.com", "2026-01-01", "2026-01-31");
        verify(personRepo, never()).findCommunicationPartners(any());
    }

    @Test
    void findCommunicationPartners_repositoryThrows_returnsEmptyList() {
        when(personRepo.findCommunicationPartners(any())).thenThrow(new RuntimeException("Neo4j down"));

        List<PersonNode> result = service.findCommunicationPartners("a@b.com", null, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void getThreadEmails_delegatesToRepository() {
        EmailNode e = new EmailNode();
        when(emailNodeRepo.findByThreadId("thread-1")).thenReturn(List.of(e));

        List<EmailNode> result = service.getThreadEmails("thread-1");

        assertEquals(1, result.size());
    }

    @Test
    void getThreadEmails_repositoryThrows_returnsEmptyList() {
        when(emailNodeRepo.findByThreadId(any())).thenThrow(new RuntimeException("boom"));

        assertTrue(service.getThreadEmails("thread-1").isEmpty());
    }

    @Test
    void findEmailsByConceptProximity_delegatesToRepository() {
        when(emailNodeRepo.findByConceptProximity("contract", 10)).thenReturn(List.of(new EmailNode()));

        List<EmailNode> result = service.findEmailsByConceptProximity("contract", 10);

        assertEquals(1, result.size());
    }

    @Test
    void findEmailsByConceptProximity_repositoryThrows_returnsEmptyList() {
        when(emailNodeRepo.findByConceptProximity(any(), anyInt())).thenThrow(new RuntimeException("boom"));

        assertTrue(service.findEmailsByConceptProximity("contract", 10).isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void getGraphStats_aggregatesAllQueries() {
        Map<String, Object> topicRow = Map.of("name", "Budget", "cnt", 5L);
        Map<String, Object> orgRow = Map.of("name", "Acme", "cnt", 3L);
        Map<String, Object> senderRow = Map.of("email", "x@y.com", "name", "X Y", "cnt", 7L);

        when(neo4jClient.query(contains("{type:'TOPIC'}")).fetch().all()).thenReturn(List.of(topicRow));
        when(neo4jClient.query(contains("{type:'ORG'}")).fetch().all()).thenReturn(List.of(orgRow));
        when(neo4jClient.query(contains("[:SENT]")).fetch().all()).thenReturn(List.of(senderRow));
        when(neo4jClient.query(eq("MATCH (n:Person) RETURN count(n) AS cnt")).fetch().one()).thenReturn(Optional.of(Map.of("cnt", 42L)));
        when(neo4jClient.query(eq("MATCH (n:Email) RETURN count(n) AS cnt")).fetch().one()).thenReturn(Optional.of(Map.of("cnt", 100L)));
        when(neo4jClient.query(eq("MATCH (n:Concept) RETURN count(n) AS cnt")).fetch().one()).thenReturn(Optional.of(Map.of("cnt", 15L)));

        GraphSearchService.GraphStats stats = service.getGraphStats();

        assertEquals(1, stats.topTopics().size());
        assertEquals("Budget", stats.topTopics().get(0).name());
        assertEquals(5L, stats.topTopics().get(0).count());
        assertEquals(1, stats.topOrgs().size());
        assertEquals(1, stats.topSenders().size());
        assertEquals("x@y.com", stats.topSenders().get(0).email());
        assertEquals(42L, stats.personCount());
        assertEquals(100L, stats.emailCount());
        assertEquals(15L, stats.conceptCount());
    }

    @Test
    void getGraphStats_neo4jThrows_returnsZeroedStats() {
        when(neo4jClient.query(any(String.class))).thenThrow(new RuntimeException("Neo4j unreachable"));

        GraphSearchService.GraphStats stats = service.getGraphStats();

        assertTrue(stats.topTopics().isEmpty());
        assertTrue(stats.topOrgs().isEmpty());
        assertTrue(stats.topSenders().isEmpty());
        assertEquals(0, stats.personCount());
        assertEquals(0, stats.emailCount());
        assertEquals(0, stats.conceptCount());
    }

    @Test
    void getGraphStats_countQueryReturnsEmptyOptional_defaultsToZero() {
        when(neo4jClient.query(contains("{type:'TOPIC'}")).fetch().all()).thenReturn(List.of());
        when(neo4jClient.query(contains("{type:'ORG'}")).fetch().all()).thenReturn(List.of());
        when(neo4jClient.query(contains("[:SENT]")).fetch().all()).thenReturn(List.of());
        when(neo4jClient.query(eq("MATCH (n:Person) RETURN count(n) AS cnt")).fetch().one()).thenReturn(Optional.empty());
        when(neo4jClient.query(eq("MATCH (n:Email) RETURN count(n) AS cnt")).fetch().one()).thenReturn(Optional.empty());
        when(neo4jClient.query(eq("MATCH (n:Concept) RETURN count(n) AS cnt")).fetch().one()).thenReturn(Optional.empty());

        GraphSearchService.GraphStats stats = service.getGraphStats();

        assertEquals(0, stats.personCount());
        assertEquals(0, stats.emailCount());
        assertEquals(0, stats.conceptCount());
    }
}

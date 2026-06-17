package hu.fmdev.backend.service;

import hu.fmdev.backend.domain.Email;
import hu.fmdev.backend.domain.graph.ConceptNode;
import hu.fmdev.backend.domain.graph.EmailNode;
import hu.fmdev.backend.domain.graph.PersonNode;
import hu.fmdev.backend.repository.AttachmentRepository;
import hu.fmdev.backend.repository.EmailRepository;
import hu.fmdev.backend.repository.graph.ClaimNodeRepository;
import hu.fmdev.backend.repository.graph.ConceptNodeRepository;
import hu.fmdev.backend.repository.graph.EmailNodeRepository;
import hu.fmdev.backend.repository.graph.EvidenceNodeRepository;
import hu.fmdev.backend.repository.graph.MechanismNodeRepository;
import hu.fmdev.backend.repository.graph.PersonNodeRepository;
import hu.fmdev.backend.repository.graph.ThreadNodeRepository;
import hu.fmdev.backend.service.rag.K1ExtractionOutput;
import hu.fmdev.backend.service.rag.NerExtractor;
import hu.fmdev.backend.service.rag.TextExtractionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeGraphIngestionServiceTest {

    @Mock private EmailRepository emailRepository;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private PersonNodeRepository personRepo;
    @Mock private EmailNodeRepository emailNodeRepo;
    @Mock private ThreadNodeRepository threadRepo;
    @Mock private ConceptNodeRepository conceptRepo;
    @Mock private ClaimNodeRepository claimRepo;
    @Mock private EvidenceNodeRepository evidenceRepo;
    @Mock private MechanismNodeRepository mechanismRepo;
    @Mock private NerExtractor entityExtraction;
    @Mock private TextExtractionService textExtraction;
    @Mock private ProgressTracker progressTracker;
    @Mock private AppSettingsService appSettingsService;

    private KnowledgeGraphIngestionService service;

    @BeforeEach
    void setUp() {
        when(appSettingsService.getEffectiveKgBatchSize()).thenReturn(10);
        when(appSettingsService.getEffectiveKgMaxConcurrentWrites()).thenReturn(2);
        when(attachmentRepository.findByEmailId(any())).thenReturn(List.of());
        when(personRepo.save(any(PersonNode.class))).thenAnswer(inv -> inv.getArgument(0));
        when(conceptRepo.save(any(ConceptNode.class))).thenAnswer(inv -> inv.getArgument(0));
        when(emailNodeRepo.save(any(EmailNode.class))).thenAnswer(inv -> inv.getArgument(0));

        service = new KnowledgeGraphIngestionService(emailRepository, attachmentRepository, personRepo,
                emailNodeRepo, threadRepo, conceptRepo, claimRepo, evidenceRepo, mechanismRepo,
                entityExtraction, textExtraction, progressTracker, appSettingsService);
    }

    private Email email(String id, String messageId, String senderEmail) {
        Email e = new Email();
        e.setId(id);
        e.setInternetMessageId(messageId);
        e.setSenderEmailAddress(senderEmail);
        e.setSenderName("Sender Name");
        e.setSubject("Subject " + id);
        e.setStrippedBody("Body of " + id);
        e.setReceivedTime(LocalDateTime.of(2026, 1, 1, 10, 0));
        e.setRecipients(List.of());
        e.setCc(List.of());
        return e;
    }

    /** One page with the given emails, then an empty page so the batch loop terminates. */
    private void mockSinglePage(List<Email> emails) {
        when(emailRepository.count()).thenReturn((long) emails.size());
        Page<Email> page = new PageImpl<>(emails, PageRequest.of(0, 10), emails.size());
        when(emailRepository.findAll(any(Pageable.class))).thenReturn(page);
    }

    @Test
    void ingestAll_newEmail_createsPersonAndEmailNodes() {
        Email e = email("e1", "<msg1@x.com>", "sender@x.com");
        mockSinglePage(List.of(e));
        when(emailNodeRepo.existsByMessageId(anyString())).thenReturn(false);
        when(entityExtraction.extractK1(anyString()))
                .thenReturn(new K1ExtractionOutput(
                        List.of(new NerExtractor.ExtractedEntity("Acme", "ORG")),
                        List.of(), List.of(), List.of(), List.of()));
        when(personRepo.findByEmail(any())).thenReturn(Optional.empty());
        when(conceptRepo.findByNameIgnoreCase(any())).thenReturn(Optional.empty());

        service.ingestAll();

        verify(personRepo).save(any(PersonNode.class));
        verify(conceptRepo).save(any(ConceptNode.class));
        verify(emailNodeRepo).save(any(EmailNode.class));
        assertFalse(service.isRunning());
        assertEquals(1, service.getStats().processed());
        assertEquals(0, service.getStats().failed());
    }

    @Test
    void ingestAll_emailAlreadyInGraph_skipsEntirely() {
        Email e = email("e1", "<msg1@x.com>", "sender@x.com");
        mockSinglePage(List.of(e));
        when(emailNodeRepo.existsByMessageId(anyString())).thenReturn(true);

        service.ingestAll();

        verify(emailNodeRepo, never()).save(any());
        verify(entityExtraction, never()).extract(any());
        assertEquals(0, service.getStats().processed());
    }

    @Test
    void ingestAll_writeFails_incrementsFailedCount() {
        Email e = email("e1", "<msg1@x.com>", "sender@x.com");
        mockSinglePage(List.of(e));
        when(emailNodeRepo.existsByMessageId(anyString())).thenReturn(false);
        when(entityExtraction.extractK1(anyString())).thenReturn(K1ExtractionOutput.empty());
        when(personRepo.findByEmail(any())).thenThrow(new RuntimeException("Neo4j write failed"));

        service.ingestAll();

        assertEquals(0, service.getStats().processed());
        assertEquals(1, service.getStats().failed());
    }

    @Test
    void ingestAll_nerExtractionThrows_emailSkippedButIngestionContinues() {
        Email e1 = email("e1", "<msg1@x.com>", "a@x.com");
        Email e2 = email("e2", "<msg2@x.com>", "b@x.com");
        mockSinglePage(List.of(e1, e2));
        when(emailNodeRepo.existsByMessageId(anyString())).thenReturn(false);
        when(personRepo.findByEmail(any())).thenReturn(Optional.empty());
        when(entityExtraction.extractK1("Body of e1")).thenThrow(new RuntimeException("Ollama down"));
        when(entityExtraction.extractK1("Body of e2")).thenReturn(K1ExtractionOutput.empty());

        service.ingestAll();

        // e1's NER call threw -> its result is dropped before the write phase, so it's
        // neither processed nor counted as a write failure; e2 still gets written.
        assertEquals(1, service.getStats().processed());
        verify(emailNodeRepo, times(1)).save(any());
    }

    @Test
    void ingestAll_alreadyRunning_isNoOp() throws InterruptedException {
        // Make the pipeline block long enough for a second concurrent call to observe running=true.
        mockSinglePage(List.of(email("e1", "<m1@x.com>", "a@x.com")));
        when(emailNodeRepo.existsByMessageId(anyString())).thenReturn(false);
        when(personRepo.findByEmail(any())).thenReturn(Optional.empty());
        when(entityExtraction.extractK1(any())).thenAnswer(inv -> {
            Thread.sleep(200);
            return K1ExtractionOutput.empty();
        });

        Thread first = new Thread(service::ingestAll);
        first.start();
        Thread.sleep(50); // let the first call enter ingestAll() and flip running=true
        service.ingestAll(); // should be a no-op since running is already true
        first.join();

        assertFalse(service.isRunning());
        // Only the first call's single email should have been processed.
        assertEquals(1, service.getStats().processed());
    }

    @Test
    void ingestConceptsOnly_skipsEmailsWithoutExistingNode() {
        Email e = email("e1", "<msg1@x.com>", "sender@x.com");
        mockSinglePage(List.of(e));
        when(emailNodeRepo.existsByMessageId(anyString())).thenReturn(false);

        service.ingestConceptsOnly();

        verify(entityExtraction, never()).extract(any());
        verify(emailNodeRepo, never()).ensureConcepts(any());
    }

    @Test
    void ingestConceptsOnly_existingNodeWithEntities_writesConceptsAndLinks() {
        Email e = email("e1", "<msg1@x.com>", "sender@x.com");
        mockSinglePage(List.of(e));
        when(emailNodeRepo.existsByMessageId(anyString())).thenReturn(true);
        when(entityExtraction.extractK1(anyString()))
                .thenReturn(new K1ExtractionOutput(
                        List.of(new NerExtractor.ExtractedEntity("Budget Q3", "TOPIC")),
                        List.of(), List.of(), List.of(), List.of()));

        service.ingestConceptsOnly();

        verify(emailNodeRepo).ensureConcepts(any());
        verify(emailNodeRepo).linkEmailToConcepts(eq("e1"), eq(List.of("Budget Q3")));
        assertEquals(1, service.getStats().processed());
    }

    @Test
    void ingestConceptsOnly_existingNodeNoEntities_skipsWrite() {
        Email e = email("e1", "<msg1@x.com>", "sender@x.com");
        mockSinglePage(List.of(e));
        when(emailNodeRepo.existsByMessageId(anyString())).thenReturn(true);
        when(entityExtraction.extractK1(anyString())).thenReturn(K1ExtractionOutput.empty());

        service.ingestConceptsOnly();

        verify(emailNodeRepo, never()).ensureConcepts(any());
        assertEquals(0, service.getStats().processed());
    }

    @Test
    void getStats_beforeAnyRun_returnsZeroedStats() {
        KnowledgeGraphIngestionService.KgStats stats = service.getStats();

        assertEquals(0, stats.totalEmails());
        assertEquals(0, stats.processed());
        assertEquals(0, stats.failed());
        assertNull(stats.etaSeconds());
    }
}

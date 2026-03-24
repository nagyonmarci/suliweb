package hu.fmdev.backend.service.rag;

import hu.fmdev.backend.config.RagConfig;
import hu.fmdev.backend.domain.DocumentChunk;
import hu.fmdev.backend.domain.Email;
import hu.fmdev.backend.repository.DocumentChunkRepository;
import hu.fmdev.backend.repository.EmailRepository;
import hu.fmdev.backend.service.ProgressTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagIngestionServiceTest {

    @Mock private EmailRepository emailRepository;
    @Mock private DocumentChunkRepository chunkRepository;
    @Mock private TextExtractionService textExtractionService;
    @Mock private ChunkingService chunkingService;
    @Mock private EmbeddingService embeddingService;
    @Mock private ProgressTracker progressTracker;

    @Captor private ArgumentCaptor<List<DocumentChunk>> chunksCaptor;

    private RagIngestionService service;

    @BeforeEach
    void setUp() {
        RagConfig config = new RagConfig();
        config.setIngestionThreads(1);
        service = new RagIngestionService(
                emailRepository, chunkRepository, textExtractionService,
                chunkingService, embeddingService, progressTracker, config);
    }

    private Email createTestEmail(String id, String subject, String body) {
        Email email = new Email();
        email.setId(id);
        email.setSubject(subject);
        email.setBody(body);
        email.setSenderName("Test Sender");
        email.setSenderEmailAddress("test@example.com");
        email.setReceivedTime(LocalDateTime.now());
        email.setPstFileName("test.pst");
        email.setFolderPath("Inbox");
        return email;
    }

    @Test
    void ingestEmail_withBody_createsBodyAndSubjectChunks() {
        Email email = createTestEmail("e1", "Test Subject", "This is the email body text");
        when(textExtractionService.getEmailTextContent("This is the email body text", null))
                .thenReturn("This is the email body text");
        when(chunkingService.chunkText("This is the email body text"))
                .thenReturn(List.of("This is the email body text"));

        service.ingestEmail(email);

        verify(chunkRepository).saveAll(chunksCaptor.capture());
        List<DocumentChunk> chunks = chunksCaptor.getValue();

        // 1 body chunk + 1 subject chunk
        assertEquals(2, chunks.size());

        DocumentChunk bodyChunk = chunks.stream()
                .filter(c -> "email_body".equals(c.getSourceType())).findFirst().orElseThrow();
        assertEquals("e1", bodyChunk.getEmailId());
        assertEquals("This is the email body text", bodyChunk.getContent());
        assertEquals(0, bodyChunk.getChunkIndex());
        assertEquals("pending", bodyChunk.getIngestionStatus());
        assertNull(bodyChunk.getAttachmentPath());

        DocumentChunk subjectChunk = chunks.stream()
                .filter(c -> "email_subject".equals(c.getSourceType())).findFirst().orElseThrow();
        assertEquals("Test Subject", subjectChunk.getContent());
    }

    @Test
    void ingestEmail_withAttachments_createsAttachmentChunks() {
        Email email = createTestEmail("e2", "With attachment", null);
        email.setAttachmentPaths(List.of("/app/attachments/doc.pdf"));

        when(textExtractionService.getEmailTextContent(null, null)).thenReturn("");
        when(textExtractionService.extractTextFromFile("/app/attachments/doc.pdf"))
                .thenReturn("PDF content extracted");
        when(chunkingService.chunkText("PDF content extracted"))
                .thenReturn(List.of("PDF content extracted"));

        service.ingestEmail(email);

        verify(chunkRepository).saveAll(chunksCaptor.capture());
        List<DocumentChunk> chunks = chunksCaptor.getValue();

        // 1 subject + 1 attachment chunk (body is empty)
        assertEquals(2, chunks.size());

        DocumentChunk attachmentChunk = chunks.stream()
                .filter(c -> "attachment".equals(c.getSourceType())).findFirst().orElseThrow();
        assertEquals("PDF content extracted", attachmentChunk.getContent());
        assertEquals("/app/attachments/doc.pdf", attachmentChunk.getAttachmentPath());
        assertEquals("doc.pdf", attachmentChunk.getAttachmentFileName());
    }

    @Test
    void ingestEmail_emptyBodyNoAttachments_onlySubjectChunk() {
        Email email = createTestEmail("e3", "Only subject", null);
        when(textExtractionService.getEmailTextContent(null, null)).thenReturn("");

        service.ingestEmail(email);

        verify(chunkRepository).saveAll(chunksCaptor.capture());
        assertEquals(1, chunksCaptor.getValue().size());
        assertEquals("email_subject", chunksCaptor.getValue().getFirst().getSourceType());
    }

    @Test
    void ingestEmail_noSubjectNoBody_noChunks() {
        Email email = createTestEmail("e4", null, null);
        when(textExtractionService.getEmailTextContent(null, null)).thenReturn("");

        service.ingestEmail(email);

        verify(chunkRepository, never()).saveAll(any());
    }

    @Test
    void ingestEmail_multipleBodyChunks_setsCorrectIndices() {
        Email email = createTestEmail("e5", "Multi chunk", "Long body text");
        when(textExtractionService.getEmailTextContent("Long body text", null))
                .thenReturn("Long body text");
        when(chunkingService.chunkText("Long body text"))
                .thenReturn(List.of("chunk 0", "chunk 1", "chunk 2"));

        service.ingestEmail(email);

        verify(chunkRepository).saveAll(chunksCaptor.capture());
        List<DocumentChunk> bodyChunks = chunksCaptor.getValue().stream()
                .filter(c -> "email_body".equals(c.getSourceType())).toList();

        assertEquals(3, bodyChunks.size());
        assertEquals(0, bodyChunks.get(0).getChunkIndex());
        assertEquals(1, bodyChunks.get(1).getChunkIndex());
        assertEquals(2, bodyChunks.get(2).getChunkIndex());
    }

    @Test
    void ingestEmail_setsMetadataOnChunks() {
        Email email = createTestEmail("e6", "Meta test", "body");
        email.setSenderName("Alice");
        email.setSenderEmailAddress("alice@test.com");
        email.setPstFileName("archive.pst");
        email.setFolderPath("Sent Items");

        when(textExtractionService.getEmailTextContent("body", null)).thenReturn("body");
        when(chunkingService.chunkText("body")).thenReturn(List.of("body"));

        service.ingestEmail(email);

        verify(chunkRepository).saveAll(chunksCaptor.capture());
        DocumentChunk chunk = chunksCaptor.getValue().getFirst();
        assertEquals("Alice", chunk.getSenderName());
        assertEquals("alice@test.com", chunk.getSenderEmailAddress());
        assertEquals("archive.pst", chunk.getPstFileName());
        assertEquals("Sent Items", chunk.getFolderPath());
        assertEquals("Meta test", chunk.getEmailSubject());
        assertNotNull(chunk.getCreatedAt());
    }

    @Test
    void embedPendingChunks_successfulEmbedding_updatesStatus() {
        DocumentChunk pending = new DocumentChunk();
        pending.setId("c1");
        pending.setContent("Test content");
        pending.setIngestionStatus("pending");

        when(chunkRepository.findByIngestionStatus("pending")).thenReturn(List.of(pending));
        when(embeddingService.embed("Test content")).thenReturn(List.of(0.1, 0.2, 0.3));

        service.embedPendingChunks();

        verify(chunkRepository).save(argThat(chunk ->
                "embedded".equals(chunk.getIngestionStatus()) &&
                chunk.getEmbedding() != null &&
                chunk.getEmbedding().size() == 3 &&
                chunk.getEmbeddedAt() != null));
    }

    @Test
    void embedPendingChunks_emptyEmbedding_setsFailedStatus() {
        DocumentChunk pending = new DocumentChunk();
        pending.setId("c2");
        pending.setContent("Problematic content");
        pending.setIngestionStatus("pending");

        when(chunkRepository.findByIngestionStatus("pending")).thenReturn(List.of(pending));
        when(embeddingService.embed("Problematic content")).thenReturn(List.of());

        service.embedPendingChunks();

        verify(chunkRepository).save(argThat(chunk ->
                "failed".equals(chunk.getIngestionStatus())));
    }

    @Test
    void embedPendingChunks_noPending_doesNothing() {
        when(chunkRepository.findByIngestionStatus("pending")).thenReturn(List.of());

        service.embedPendingChunks();

        verify(chunkRepository, never()).save(any());
    }

    @Test
    void reIngestEmail_deletesOldAndCreatesNew() {
        Email email = createTestEmail("e7", "Reindex", "new body");
        when(emailRepository.findById("e7")).thenReturn(Optional.of(email));
        when(textExtractionService.getEmailTextContent("new body", null)).thenReturn("new body");
        when(chunkingService.chunkText("new body")).thenReturn(List.of("new body"));

        DocumentChunk newChunk = new DocumentChunk();
        newChunk.setIngestionStatus("pending");
        newChunk.setContent("new body");
        when(chunkRepository.findByEmailId("e7")).thenReturn(List.of(newChunk));
        when(embeddingService.embed("new body")).thenReturn(List.of(0.5));

        service.reIngestEmail("e7");

        verify(chunkRepository).deleteByEmailId("e7");
        verify(chunkRepository).saveAll(any());
        verify(embeddingService).embed("new body");
    }

    @Test
    void getStats_returnsCorrectCounts() {
        when(chunkRepository.count()).thenReturn(100L);
        when(chunkRepository.countByIngestionStatus("embedded")).thenReturn(80L);
        when(chunkRepository.countByIngestionStatus("pending")).thenReturn(15L);
        when(chunkRepository.countByIngestionStatus("failed")).thenReturn(5L);
        when(emailRepository.count()).thenReturn(50L);

        var stats = service.getStats();

        assertEquals(50, stats.totalEmails());
        assertEquals(100, stats.totalChunks());
        assertEquals(80, stats.embeddedChunks());
        assertEquals(15, stats.pendingChunks());
        assertEquals(5, stats.failedChunks());
    }

    @Test
    void isRunning_initiallyFalse() {
        assertFalse(service.isRunning());
    }
}

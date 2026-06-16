package hu.fmdev.backend.service;

import hu.fmdev.backend.domain.FailedConversion;
import hu.fmdev.backend.repository.EmailRepository;
import hu.fmdev.backend.repository.FailedConversionRepository;
import hu.fmdev.backend.service.rag.TextExtractionService;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EDiscoveryFailedConversionTest {

    @Mock EmailRepository emailRepository;
    @Mock ElasticsearchClient esClient;
    @Mock TextExtractionService textExtractionService;
    @Mock ProgressTracker progressTracker;
    @Mock FailedConversionRepository failedConversionRepository;

    private EDiscoveryIngestionService service;

    @BeforeEach
    void setUp() {
        service = new EDiscoveryIngestionService(
                emailRepository, esClient,
                textExtractionService, progressTracker, failedConversionRepository,
                200, 200);
    }

    @Test
    void retryFailed_resolved_skipsProcessing() {
        FailedConversion fc = FailedConversion.replyStrip("emailId1", "msgId1", "error");
        fc.markResolved();
        when(failedConversionRepository.findById("fc1")).thenReturn(java.util.Optional.of(fc));

        service.retryFailed("fc1");

        verify(emailRepository, never()).findById(any());
    }

    @Test
    void retryFailed_notFound_skipsProcessing() {
        when(failedConversionRepository.findById("missing")).thenReturn(java.util.Optional.empty());

        service.retryFailed("missing");

        verify(emailRepository, never()).findById(any());
    }

    @Test
    void retryFailed_pendingRecord_triggersReIngestAndMarksResolved() {
        FailedConversion fc = FailedConversion.replyStrip("emailId1", "msgId1", "error");
        when(failedConversionRepository.findById("fc1")).thenReturn(java.util.Optional.of(fc));
        when(emailRepository.findById("emailId1")).thenReturn(java.util.Optional.empty());

        service.retryFailed("fc1");

        ArgumentCaptor<FailedConversion> captor = ArgumentCaptor.forClass(FailedConversion.class);
        verify(failedConversionRepository).save(captor.capture());
        assertTrue(captor.getValue().isResolved());
    }

    @Test
    void retryAllFailed_deduplicatesByEmailId() {
        FailedConversion fc1 = FailedConversion.replyStrip("emailId1", "msg1", "err");
        FailedConversion fc2 = FailedConversion.attachmentConvert("emailId1", "msg1", "hash", "file.pdf", "err");
        when(failedConversionRepository.findByResolved(false)).thenReturn(List.of(fc1, fc2));
        when(emailRepository.findById("emailId1")).thenReturn(java.util.Optional.empty());

        int count = service.retryAllFailed();

        assertEquals(1, count);
        verify(emailRepository, times(1)).findById("emailId1");
    }

    @Test
    void failedConversionRecord_hasCorrectFields() {
        FailedConversion fc = FailedConversion.attachmentConvert("eid", "mid", "sha256abc", "doc.pdf", "timeout");

        assertEquals("eid", fc.getMongoEmailId());
        assertEquals("mid", fc.getMessageId());
        assertEquals("sha256abc", fc.getAttachmentHash());
        assertEquals("doc.pdf", fc.getAttachmentFilename());
        assertEquals(FailedConversion.FailureType.ATTACHMENT_CONVERT, fc.getFailureType());
        assertEquals("timeout", fc.getErrorMessage());
        assertEquals(0, fc.getRetryCount());
        assertFalse(fc.isResolved());
        assertNotNull(fc.getOccurredAt());
    }

    @Test
    void incrementRetry_updatesCountAndMessage() {
        FailedConversion fc = FailedConversion.replyStrip("eid", "mid", "original error");

        fc.incrementRetry("new error");

        assertEquals(1, fc.getRetryCount());
        assertEquals("new error", fc.getErrorMessage());
        assertFalse(fc.isResolved());
    }
}

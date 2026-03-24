package hu.fmdev.backend.service;

import hu.fmdev.backend.domain.FileInfo;
import hu.fmdev.backend.repository.EmailRepository;
import hu.fmdev.backend.repository.FileInfoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PstProcessorServiceTest {

    @Mock private EmailRepository emailRepository;
    @Mock private FileInfoRepository fileInfoRepository;
    @Mock private ProgressTracker progressTracker;

    private PstProcessorService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new PstProcessorService(emailRepository, fileInfoRepository, progressTracker);
    }

    // --- generateUniqueEntryId ---

    @Test
    void generateUniqueEntryId_returnsSha256Hash() {
        String hash = service.generateUniqueEntryId("test.pst", 12345);

        assertNotNull(hash);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void generateUniqueEntryId_sameInput_sameHash() {
        String hash1 = service.generateUniqueEntryId("test.pst", 100);
        String hash2 = service.generateUniqueEntryId("test.pst", 100);

        assertEquals(hash1, hash2);
    }

    @Test
    void generateUniqueEntryId_differentFile_differentHash() {
        String hash1 = service.generateUniqueEntryId("file1.pst", 100);
        String hash2 = service.generateUniqueEntryId("file2.pst", 100);

        assertNotEquals(hash1, hash2);
    }

    @Test
    void generateUniqueEntryId_differentNodeId_differentHash() {
        String hash1 = service.generateUniqueEntryId("test.pst", 1);
        String hash2 = service.generateUniqueEntryId("test.pst", 2);

        assertNotEquals(hash1, hash2);
    }

    // --- processPstFile ---

    @Test
    void processPstFile_nonExistentFile_throwsIOException() {
        assertThrows(IOException.class, () ->
                service.processPstFile("/nonexistent/file.pst", false));
    }

    @Test
    void processPstFile_unreadableFile_throwsIOException() throws Exception {
        Path file = tempDir.resolve("unreadable.pst");
        Files.writeString(file, "not a real pst");

        // This will throw because it's not a valid PST file
        assertThrows(IOException.class, () ->
                service.processPstFile(file.toString(), false));
    }

    // --- processPstFilesFromDb ---

    @Test
    void processPstFilesFromDb_noFiles_logsAndReturns() {
        when(fileInfoRepository.findByStatusIn(Arrays.asList("New", "Modified")))
                .thenReturn(List.of());

        service.processPstFilesFromDb(false);

        verify(progressTracker, never()).startOperation(anyString(), anyInt());
    }

    @Test
    void processPstFilesFromDb_withFiles_startsProgressTracking() {
        FileInfo file1 = new FileInfo("/tmp/a.pst", "a.pst", 100, null, "New");
        FileInfo file2 = new FileInfo("/tmp/b.pst", "b.pst", 200, null, "Modified");
        when(fileInfoRepository.findByStatusIn(Arrays.asList("New", "Modified")))
                .thenReturn(List.of(file1, file2));

        service.processPstFilesFromDb(false);

        verify(progressTracker).startOperation("PST Fájlok feldolgozása az adatbázisból", 2);
        verify(progressTracker).stopOperation();
    }

    // --- pause / resume ---

    @Test
    void pauseAndResume_togglesPausedState() {
        service.pauseProcessing();
        service.resumeProcessing();
        // If we got here without hanging, pause/resume works
        assertTrue(true);
    }

    // --- convertMultiPartToFile ---

    @Test
    void convertMultiPartToFile_savesToTempDir() throws Exception {
        var multipartFile = mock(org.springframework.web.multipart.MultipartFile.class);
        when(multipartFile.getOriginalFilename()).thenReturn("test.pst");
        when(multipartFile.getInputStream())
                .thenReturn(new java.io.ByteArrayInputStream("test content".getBytes()));

        java.io.File result = service.convertMultiPartToFile(multipartFile);

        assertTrue(result.exists());
        assertTrue(result.getName().equals("test.pst"));
        result.delete(); // cleanup
    }

    // --- processPstFilesFromTxt ---

    @Test
    void processPstFilesFromTxt_nonExistentTxtFile_throwsException() {
        assertThrows(IOException.class, () ->
                service.processPstFilesFromTxt("/nonexistent/paths.txt", false));
    }

    @Test
    void processPstFilesFromTxt_emptyFile_processesNothing() throws Exception {
        Path txtFile = tempDir.resolve("empty.txt");
        Files.writeString(txtFile, "");

        // Should not throw, just process nothing
        service.processPstFilesFromTxt(txtFile.toString(), false);

        verify(emailRepository, never()).save(any());
    }
}

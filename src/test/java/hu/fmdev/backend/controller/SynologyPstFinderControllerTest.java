package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.FileInfo;
import hu.fmdev.backend.service.SynologyPstFinderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SynologyPstFinderControllerTest {

    @Mock
    private SynologyPstFinderService synologyPstFinderService;

    private SynologyPstFinderController controller;

    @BeforeEach
    void setUp() {
        controller = new SynologyPstFinderController();
        // Inject mock via reflection since the controller uses @Autowired field injection
        try {
            var field = SynologyPstFinderController.class.getDeclaredField("synologyPstFinderService");
            field.setAccessible(true);
            field.set(controller, synologyPstFinderService);
        } catch (Exception e) {
            fail("Could not inject mock: " + e.getMessage());
        }
    }

    // --- /find/synology ---

    @Test
    void searchPstOnSynology_success_returnsFileList() {
        FileInfo file = new FileInfo("/mnt/nas/archive.pst", "archive.pst", 1024, LocalDateTime.now(), "New");
        when(synologyPstFinderService.findPstFilesOnNas()).thenReturn(List.of(file));

        ResponseEntity<?> response = controller.searchPstOnSynology();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<FileInfo> body = (List<FileInfo>) response.getBody();
        assertEquals(1, body.size());
        assertEquals("archive.pst", body.getFirst().getFileName());
    }

    @Test
    void searchPstOnSynology_emptyResult_returnsEmptyList() {
        when(synologyPstFinderService.findPstFilesOnNas()).thenReturn(List.of());

        ResponseEntity<?> response = controller.searchPstOnSynology();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<FileInfo> body = (List<FileInfo>) response.getBody();
        assertTrue(body.isEmpty());
    }

    @Test
    void searchPstOnSynology_exception_returns500() {
        when(synologyPstFinderService.findPstFilesOnNas())
                .thenThrow(new RuntimeException("Connection refused"));

        ResponseEntity<?> response = controller.searchPstOnSynology();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("Connection refused"));
    }

    // --- /find/synologyToDb ---

    @Test
    void searchAndSavePstFromSynology_success_returnsOk() {
        doNothing().when(synologyPstFinderService).findAndSaveFiles();

        ResponseEntity<String> response = controller.searchAndSavePstFromSynology();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("sikeresen"));
        verify(synologyPstFinderService).findAndSaveFiles();
    }

    @Test
    void searchAndSavePstFromSynology_exception_returns500() {
        doThrow(new RuntimeException("NAS unavailable"))
                .when(synologyPstFinderService).findAndSaveFiles();

        ResponseEntity<String> response = controller.searchAndSavePstFromSynology();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(response.getBody().contains("NAS unavailable"));
    }

    @Test
    void searchPstOnSynology_multipleFiles_returnsAll() {
        List<FileInfo> files = List.of(
                new FileInfo("/mnt/nas/a.pst", "a.pst", 100, LocalDateTime.now(), "New"),
                new FileInfo("/mnt/nas/b.pst", "b.pst", 200, LocalDateTime.now(), "New"),
                new FileInfo("/mnt/nas/c.ost", "c.ost", 300, LocalDateTime.now(), "New"));
        when(synologyPstFinderService.findPstFilesOnNas()).thenReturn(files);

        ResponseEntity<?> response = controller.searchPstOnSynology();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<FileInfo> body = (List<FileInfo>) response.getBody();
        assertEquals(3, body.size());
    }
}

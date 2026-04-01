package hu.fmdev.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hu.fmdev.backend.domain.FileInfo;
import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.repository.FileInfoRepository;
import hu.fmdev.backend.repository.LogEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SynologyPstFinderServiceTest {

    @Mock private SynologyApiClient synologyApiClient;
    @Mock private SynologySettingsService settingsService;
    @Mock private FileInfoRepository fileInfoRepository;
    @Mock private PstFinderService pstFinderService;
    @Mock private LogEntryRepository logEntryRepository;

    private SynologyPstFinderService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        CentralLogger centralLogger = new CentralLogger();
        ReflectionTestUtils.setField(centralLogger, "logEntryRepository", logEntryRepository);
        centralLogger.init();

        when(settingsService.getEffectiveSearchExtensions()).thenReturn("pst,ost");
        lenient().when(settingsService.getEffectivePathPrefix()).thenReturn("/volume1");
        lenient().when(settingsService.getEffectiveLocalMountPrefix()).thenReturn("/mnt/nas");
        service = new SynologyPstFinderService(synologyApiClient, settingsService, fileInfoRepository, pstFinderService);
    }

    private JsonNode createHit(String path, String fileName, long size, long lastModified) {
        return objectMapper.createObjectNode()
                .put("SYNOMDPath", path)
                .put("SYNOMDFSName", fileName)
                .put("SYNOMDFSSize", size)
                .put("SYNOMDLastModifiedDate", lastModified);
    }

    @Test
    void findPstFilesOnNas_searchesBothExtensions() {
        when(synologyApiClient.searchFiles("pst")).thenReturn(List.of());
        when(synologyApiClient.searchFiles("ost")).thenReturn(List.of());

        service.findPstFilesOnNas();

        verify(synologyApiClient).login();
        verify(synologyApiClient).searchFiles("pst");
        verify(synologyApiClient).searchFiles("ost");
        verify(synologyApiClient).logout();
    }

    @Test
    void findPstFilesOnNas_mapsHitsToFileInfo() {
        JsonNode hit = createHit("/volume1/shared/mail/archive.pst", "archive.pst", 1024000, 1700000000);
        when(synologyApiClient.searchFiles("pst")).thenReturn(List.of(hit));
        when(synologyApiClient.searchFiles("ost")).thenReturn(List.of());

        List<FileInfo> result = service.findPstFilesOnNas();

        assertEquals(1, result.size());
        FileInfo file = result.getFirst();
        assertEquals("archive.pst", file.getFileName());
        assertEquals(1024000, file.getSize());
        assertEquals("New", file.getStatus());
        // Path should be mapped: /volume1/shared/... -> /mnt/nas/shared/...
        assertEquals("/mnt/nas/shared/mail/archive.pst", file.getPath());
    }

    @Test
    void findPstFilesOnNas_skipsHitsWithEmptyPath() {
        JsonNode emptyHit = objectMapper.createObjectNode()
                .put("SYNOMDPath", "")
                .put("SYNOMDFSName", "test.pst");
        when(synologyApiClient.searchFiles("pst")).thenReturn(List.of(emptyHit));
        when(synologyApiClient.searchFiles("ost")).thenReturn(List.of());

        List<FileInfo> result = service.findPstFilesOnNas();

        assertTrue(result.isEmpty());
    }

    @Test
    void findPstFilesOnNas_skipsHitsWithEmptyFileName() {
        JsonNode emptyName = objectMapper.createObjectNode()
                .put("SYNOMDPath", "/volume1/test.pst")
                .put("SYNOMDFSName", "");
        when(synologyApiClient.searchFiles("pst")).thenReturn(List.of(emptyName));
        when(synologyApiClient.searchFiles("ost")).thenReturn(List.of());

        List<FileInfo> result = service.findPstFilesOnNas();

        assertTrue(result.isEmpty());
    }

    @Test
    void findPstFilesOnNas_multipleHits_returnsAll() {
        JsonNode hit1 = createHit("/volume1/a.pst", "a.pst", 100, 1700000000);
        JsonNode hit2 = createHit("/volume1/b.pst", "b.pst", 200, 1700000000);
        when(synologyApiClient.searchFiles("pst")).thenReturn(List.of(hit1, hit2));
        when(synologyApiClient.searchFiles("ost")).thenReturn(List.of());

        List<FileInfo> result = service.findPstFilesOnNas();

        assertEquals(2, result.size());
    }

    @Test
    void findPstFilesOnNas_logoutCalledEvenOnException() {
        when(synologyApiClient.searchFiles("pst")).thenThrow(new RuntimeException("API error"));

        assertThrows(RuntimeException.class, () -> service.findPstFilesOnNas());

        verify(synologyApiClient).logout();
    }

    @Test
    void findPstFilesOnNas_pathWithoutPrefix_prependsLocalMount() {
        JsonNode hit = createHit("/other/path/file.pst", "file.pst", 500, 0);
        when(synologyApiClient.searchFiles("pst")).thenReturn(List.of(hit));
        when(synologyApiClient.searchFiles("ost")).thenReturn(List.of());

        List<FileInfo> result = service.findPstFilesOnNas();

        assertEquals("/mnt/nas/other/path/file.pst", result.getFirst().getPath());
    }

    @Test
    void findAndSaveFiles_savesEachFileToDb() {
        JsonNode hit1 = createHit("/volume1/a.pst", "a.pst", 100, 1700000000);
        JsonNode hit2 = createHit("/volume1/b.ost", "b.ost", 200, 1700000000);
        when(synologyApiClient.searchFiles("pst")).thenReturn(List.of(hit1));
        when(synologyApiClient.searchFiles("ost")).thenReturn(List.of(hit2));

        service.findAndSaveFiles();

        verify(pstFinderService, times(1)).saveOrUpdateFileInfos(anyList(), anyList());
    }

    @Test
    void findPstFilesOnNas_zeroLastModified_usesCurrentTime() {
        JsonNode hit = createHit("/volume1/file.pst", "file.pst", 100, 0);
        when(synologyApiClient.searchFiles("pst")).thenReturn(List.of(hit));
        when(synologyApiClient.searchFiles("ost")).thenReturn(List.of());

        List<FileInfo> result = service.findPstFilesOnNas();

        assertNotNull(result.getFirst().getLastModified());
    }

    @Test
    void findPstFilesOnNas_singleExtension() {
        when(settingsService.getEffectiveSearchExtensions()).thenReturn("pst");
        service = new SynologyPstFinderService(synologyApiClient, settingsService, fileInfoRepository, pstFinderService);

        when(synologyApiClient.searchFiles("pst")).thenReturn(List.of());

        service.findPstFilesOnNas();

        verify(synologyApiClient).searchFiles("pst");
        verify(synologyApiClient, never()).searchFiles("ost");
    }
}

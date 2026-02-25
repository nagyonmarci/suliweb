//import hu.fmdev.backend.controller.PstController;
//import hu.fmdev.backend.repository.FileInfoRepository;
//import hu.fmdev.backend.service.PstService;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.ArgumentCaptor;
//import org.mockito.Captor;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.http.ResponseEntity;
//import org.springframework.mock.web.MockMultipartFile;
//import org.springframework.web.multipart.MultipartFile;
//
//import java.io.File;
//import java.io.IOException;
//
//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.junit.jupiter.api.Assertions.assertThrows;
//import static org.mockito.ArgumentMatchers.anyString;
//import static org.mockito.Mockito.*;
//
//@ExtendWith(MockitoExtension.class)
//public class PstControllerTest {
//
//    @Mock
//    private FileInfoRepository fileInfoRepository;
//
//    @Mock
//    private PstService pstService;
//
//    @InjectMocks
//    private PstController pstController;
//
//    @Captor
//    private ArgumentCaptor<String> filePathCaptor;
//
//    private MultipartFile multipartFile;
//
//    @BeforeEach
//    public void setUp() throws IOException {
//        multipartFile = new MockMultipartFile("file", "test.pst", "multipart/form-data", "test".getBytes());
//    }
//
//    @Test
//    public void processPstFile_success() throws Exception {
//        when(pstService.convertMultiPartToFile(multipartFile)).thenReturn(new File("test.pst"));
//        when(pstService.processPstFile(anyString())).thenReturn("Processing result");
//
//        ResponseEntity<String> responseEntity = pstController.processPstFile(multipartFile);
//
//        assertEquals(ResponseEntity.ok("Processing result"), responseEntity);
//        verify(pstService).convertMultiPartToFile(multipartFile);
//        verify(pstService).processPstFile(filePathCaptor.capture());
//        assertEquals("test.pst", filePathCaptor.getValue());
//        verify(pstService).deleteTempFile("test.pst");
//    }
//
//    @Test
//    public void processPstFile_exception() throws Exception {
//        when(pstService.convertMultiPartToFile(multipartFile)).thenReturn(new File("test.pst"));
//        doThrow(new Exception("Test exception")).when(pstService).processPstFile(anyString());
//
//        Exception exception = assertThrows(Exception.class, () -> pstController.processPstFile(multipartFile));
//        assertEquals("Test exception", exception.getMessage());
//        verify(pstService).convertMultiPartToFile(multipartFile);
//        verify(pstService).processPstFile(filePathCaptor.capture());
//        assertEquals("test.pst", filePathCaptor.getValue());
//        verify(pstService).deleteTempFile("test.pst");
//    }
//}
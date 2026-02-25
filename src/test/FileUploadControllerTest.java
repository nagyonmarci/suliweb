//package hu.fmdev.backend.controller;
//
//import hu.fmdev.backend.service.FileUploadService;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.boot.test.mock.mockito.MockBean;
//import org.springframework.http.MediaType;
//import org.springframework.mock.web.MockMultipartFile;
//import org.springframework.test.web.servlet.MockMvc;
//
//import java.nio.file.Path;
//import java.nio.file.Paths;
//
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.Mockito.doReturn;
//import static org.mockito.Mockito.doThrow;
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
//
//@SpringBootTest
//@AutoConfigureMockMvc // Automatikus MockMvc konfiguráció
//public class FileUploadControllerTest {
//
//    @Autowired
//    private MockMvc mockMvc;
//
//    @MockBean
//    private FileUploadService fileUploadService;
//
//    @Test
//    public void testUploadFileSuccess() throws Exception {
//        MockMultipartFile file = new MockMultipartFile("file", "testfile.txt", "text/plain", "Test file content".getBytes());
//        Path mockPath = Paths.get("compressed.zip");
//        doReturn(mockPath).when(fileUploadService).uploadAndCompressFile(any(), any(), any());
//
//        mockMvc.perform(multipart("/upload")
//                        .file(file)
//                        .param("password", "secure123")
//                        .param("zipName", "compressed.zip")
//                        .contentType(MediaType.MULTIPART_FORM_DATA))
//                .andExpect(status().isOk())
//                .andExpect(content().string("Fájl sikeresen feltöltve és tömörítve: http://localhost/uploads/compressed.zip"));
//    }
//
//    @Test
//    public void testUploadFileError() throws Exception {
//        MockMultipartFile file = new MockMultipartFile("file", "testfile.txt", "text/plain", "Test file content".getBytes());
//        doThrow(new RuntimeException("Failed to process file")).when(fileUploadService).uploadAndCompressFile(any(), any(), any());
//
//        mockMvc.perform(multipart("/upload")
//                        .file(file)
//                        .param("password", "secure123")
//                        .param("zipName", "compressed.zip")
//                        .contentType(MediaType.MULTIPART_FORM_DATA))
//                .andExpect(status().isInternalServerError())
//                .andExpect(content().string("Hiba történt a fájl feltöltésekor."));
//    }
//}

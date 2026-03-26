package hu.fmdev.backend.controller;

import hu.fmdev.backend.exceptionhandler.FileCompressionException;
import hu.fmdev.backend.service.FileUploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/files")
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    @Autowired
    private FileUploadService fileUploadService;

    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file,
                                             @RequestParam("password") String password,
                                             @RequestParam("zipName") String zipName,
                                             HttpServletRequest request) {
        try {
            Path zipFilePath = fileUploadService.uploadAndCompressFile(file, password, zipName);
            String requestURL = request.getRequestURL().toString();
            String baseUrl = requestURL.substring(0, requestURL.indexOf(request.getRequestURI()));

            String fileUrl = fileUploadService.buildCompleteFileUrl(baseUrl, zipFilePath);

            return ResponseEntity.ok("Fájl sikeresen feltöltve és tömörítve: " + fileUrl);
        } catch (FileCompressionException e) {
            logger.error("Compression error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Compression error: " + e.getMessage());
        } catch (Exception e) {
            logger.error("General error during file upload: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Internal server error.");
        }
    }
}
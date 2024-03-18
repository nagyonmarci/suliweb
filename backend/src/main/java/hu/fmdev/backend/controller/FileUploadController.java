package hu.fmdev.backend.controller;

import hu.fmdev.backend.service.FileUploadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.nio.file.Path;

@Controller
public class FileUploadController {

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
            String host = requestURL.substring(0, requestURL.indexOf(request.getRequestURI()));

            String fileUrl = host + "/uploads/" + zipFilePath.getFileName().toString();
            return ResponseEntity.ok("Fájl sikeresen feltöltve és tömörítve: " + fileUrl);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Hiba történt a fájl feltöltésekor.");
        }
    }
}
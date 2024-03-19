package hu.fmdev.backend.controller;

import hu.fmdev.backend.service.PstService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Paths;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/pst")
public class PstController {

    private final PstService pstService;

    public PstController(PstService pstService) {
        this.pstService = pstService;
    }

    //    @PostMapping("/process")
//    public ResponseEntity<String> processPstFile(@RequestParam("file") MultipartFile file) {
//        try {
//            String result = pstService.processPstFile(file);
//            return ResponseEntity.ok(result);
//        } catch (Exception e) {
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error processing PST file");
//        }
//    }
    @PostMapping("/process")
    public ResponseEntity<String> processPstFile(@RequestParam("file") MultipartFile file) {
        try {
            String tempDir = System.getProperty("java.io.tmpdir");
            File tempFile = new File(Paths.get(tempDir, file.getOriginalFilename()).toString());

            try (InputStream in = file.getInputStream(); OutputStream out = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = in.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                }
            }

            String result = pstService.processPstFile(tempFile.getAbsolutePath());

            tempFile.delete();

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error processing PST file");
        }
    }

    @PostMapping("/processFromTxt")
    public ResponseEntity<String> processPstFilesFromTxt(@RequestParam("file") MultipartFile file) {
        try {
            String content = new BufferedReader(new InputStreamReader(file.getInputStream()))
                    .lines().collect(Collectors.joining("\n"));

            String[] pstFilePaths = content.split("\n");
            for (String filePath : pstFilePaths) {
                pstService.processPstFile(filePath.trim());
            }

            return ResponseEntity.ok("PST files processed successfully from TXT");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error processing PST files from TXT");
        }
    }
}

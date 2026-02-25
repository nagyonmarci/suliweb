package hu.fmdev.backend.controller;

import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.repository.FileInfoRepository;
import hu.fmdev.backend.service.PstProcessorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/pst")
public class PstProcessorController {

    private final FileInfoRepository fileInfoRepository;
    private final PstProcessorService pstProcessorService;
    private final CentralLogger centralLogger;

    public PstProcessorController(FileInfoRepository fileInfoRepository, PstProcessorService pstProcessorService, CentralLogger centralLogger) {
        this.fileInfoRepository = fileInfoRepository;
        this.pstProcessorService = pstProcessorService;
        this.centralLogger = centralLogger;
    }

    @PostMapping("/processFromFile")
    public ResponseEntity<String> processPstFile(@RequestParam("file") MultipartFile file,
                                                 @RequestParam("saveAttachments") boolean saveAttachments) {
        try {
            return processFile(file, saveAttachments);
        } catch (Exception e) {
            centralLogger.logError("Error processing uploaded PST file", e);
            return ResponseEntity.status(500).body("Error processing uploaded PST file: " + e.getMessage());
        }
    }

    @PostMapping("/processFromTxt")
    public ResponseEntity<String> processPstFilesFromTxt(@RequestParam("file") MultipartFile file,
                                                         @RequestParam("saveAttachments") boolean saveAttachments) {
        try {
            var content = new BufferedReader(new InputStreamReader(file.getInputStream()))
                    .lines().collect(Collectors.joining("\n"));
            pstProcessorService.processPstFilesFromTxt(content, saveAttachments);
            return ResponseEntity.ok("PST files processed successfully from TXT");
        } catch (IOException e) {
            centralLogger.logError("Error processing PST files from TXT file", e);
            return ResponseEntity.status(500).body("Error processing PST files from TXT file: " + e.getMessage());
        }
    }

    @PostMapping("/processFromDb")
    public ResponseEntity<String> processPstFilesFromDb(@RequestParam("saveAttachments") boolean saveAttachments) {
        try {
            pstProcessorService.processPstFilesFromDb(saveAttachments);
            return ResponseEntity.ok("PST files processed successfully from database");
        } catch (Exception e) {
            centralLogger.logError("Error processing PST files from database", e);
            return ResponseEntity.status(500).body("Error processing PST files from database: " + e.getMessage());
        }
    }

    private void processPstFile(String filePath, boolean saveAttachments) {
        try {
            pstProcessorService.processPstFile(filePath, saveAttachments);
        } catch (IOException e) {
            centralLogger.logError("Error processing PST file at path: " + filePath, e);
            throw new UncheckedIOException(e);
        }
    }

    private ResponseEntity<String> processFile(MultipartFile file, boolean saveAttachments) throws Exception {
        var tempFile = pstProcessorService.convertMultiPartToFile(file);
        var result = pstProcessorService.processPstFile(tempFile.getAbsolutePath(), saveAttachments);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/pause")
    public String pauseProcessing() {
        pstProcessorService.pauseProcessing();
        return "PST processing paused";
    }

    @PostMapping("/resume")
    public String resumeProcessing() {
        pstProcessorService.resumeProcessing();
        return "PST processing resumed";
    }
}

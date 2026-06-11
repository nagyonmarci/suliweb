package hu.fmdev.backend.controller;

import hu.fmdev.backend.dto.ProcessFileRequest;
import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.service.PstProcessorService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.stream.Collectors;

@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RestController
@RequestMapping("/pst")
public class PstProcessorController {

    private final PstProcessorService pstProcessorService;

    public PstProcessorController(PstProcessorService pstProcessorService) {
        this.pstProcessorService = pstProcessorService;
    }

    @PostMapping("/processFromFile")
    public ResponseEntity<String> processPstFile(@RequestParam("file") MultipartFile file,
            @RequestParam("saveAttachments") boolean saveAttachments) {
        try {
            return processFile(file, saveAttachments);
        } catch (Exception e) {
            CentralLogger.logError("Error processing uploaded PST file", e);
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
            CentralLogger.logError("Error processing PST files from TXT file", e);
            return ResponseEntity.status(500).body("Error processing PST files from TXT file: " + e.getMessage());
        }
    }

    @PostMapping("/processFromDb")
    public ResponseEntity<String> processPstFilesFromDb(@RequestParam("saveAttachments") boolean saveAttachments) {
        try {
            pstProcessorService.processPstFilesFromDb(saveAttachments);
            return ResponseEntity.ok("PST files processed successfully from database");
        } catch (Exception e) {
            CentralLogger.logError("Error processing PST files from database", e);
            return ResponseEntity.status(500).body("Error processing PST files from database: " + e.getMessage());
        }
    }

    private ResponseEntity<String> processFile(MultipartFile file, boolean saveAttachments) throws Exception {
        var tempFile = pstProcessorService.convertMultiPartToFile(file);
        var result = pstProcessorService.processPstFile(tempFile.getAbsolutePath(), saveAttachments);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/processSelected")
    public ResponseEntity<String> processSelected(@RequestBody List<ProcessFileRequest> requests) {
        try {
            pstProcessorService.processPstFilesSelected(requests);
            return ResponseEntity.ok("Selected PST files processed successfully");
        } catch (Exception e) {
            CentralLogger.logError("Error processing selected PST files", e);
            return ResponseEntity.status(500).body("Error processing selected PST files: " + e.getMessage());
        }
    }

    @PostMapping("/saveAttachmentsFromDb")
    public ResponseEntity<String> saveAttachmentsFromDb() {
        try {
            pstProcessorService.saveAttachmentsFromDb();
            return ResponseEntity.ok("Attachments saved successfully for processed PST files");
        } catch (Exception e) {
            CentralLogger.logError("Error saving attachments from database", e);
            return ResponseEntity.status(500).body("Error saving attachments: " + e.getMessage());
        }
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

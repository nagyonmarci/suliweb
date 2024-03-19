package hu.fmdev.backend.controller;

import hu.fmdev.backend.service.PstService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/pst")
public class PstController {

    private final PstService pstService;

    public PstController(PstService pstService) {
        this.pstService = pstService;
    }

    @PostMapping("/process")
    public ResponseEntity<String> processPstFile(@RequestParam("file") MultipartFile file) {
        try {
            String result = pstService.processPstFile(file);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error processing PST file");
        }
    }
}

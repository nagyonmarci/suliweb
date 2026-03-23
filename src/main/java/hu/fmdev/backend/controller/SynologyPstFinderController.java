package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.FileInfo;
import hu.fmdev.backend.service.SynologyPstFinderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Slf4j
@RequestMapping("/find")
public class SynologyPstFinderController {

    @Autowired
    private SynologyPstFinderService synologyPstFinderService;

    @GetMapping("/synology")
    public ResponseEntity<?> searchPstOnSynology() {
        try {
            List<FileInfo> files = synologyPstFinderService.findPstFilesOnNas();
            return ResponseEntity.ok(files);
        } catch (Exception e) {
            log.error("Hiba a Synology PST keresés során: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Hiba történt: " + e.getMessage());
        }
    }

    @GetMapping("/synologyToDb")
    public ResponseEntity<String> searchAndSavePstFromSynology() {
        try {
            synologyPstFinderService.findAndSaveFiles();
            return ResponseEntity.ok("Synology PST fájlok sikeresen feldolgozva és mentve az adatbázisba.");
        } catch (Exception e) {
            log.error("Hiba a Synology PST keresés és mentés során: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Hiba történt: " + e.getMessage());
        }
    }
}

package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.FileInfo;
import hu.fmdev.backend.service.PstFinderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RestController
@Slf4j
@RequestMapping("/find")
public class PstFinderController {

    @Autowired
    private PstFinderService searchService;

    @GetMapping("/pstToTxt")
    public ResponseEntity<String> searchAndWritePstToTxt(@RequestParam List<String> directories, @RequestParam(required = false) List<String> excludedDirectories, @RequestParam String outputFile) {
        if (excludedDirectories == null) {
            excludedDirectories = new ArrayList<>();
        }
        try {
            if (directories.isEmpty()) {
                return ResponseEntity.badRequest().body("A keresési könyvtárak listája nem lehet üres.");
            }

            List<FileInfo> fileInfos = searchService.findFiles(directories, excludedDirectories);
            searchService.saveOrUpdateFileInfos(fileInfos, directories);

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
                for (FileInfo fileInfo : fileInfos) {
                    writer.write(fileInfo.toString());
                    writer.newLine();
                }
            }

            return ResponseEntity.ok("Fájlok sikeresen feldolgozva, elmentve az adatbázisba és a fájlba: " + outputFile);
        } catch (Exception e) {
            log.error("Hiba a PST fájlok feldolgozása során: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Hiba történt: " + e.getMessage());
        }
    }

    @GetMapping("/pst")
    public ResponseEntity<String> searchAndWritePst(@RequestParam List<String> directories, @RequestParam(required = false) List<String> excludedDirectories) {
        if (excludedDirectories == null) {
            excludedDirectories = new ArrayList<>();
        }
        try {
            if (directories.isEmpty()) {
                return ResponseEntity.badRequest().body("A keresési könyvtárak listája nem lehet üres.");
            }

            searchService.findAndSaveFiles(directories, excludedDirectories);

            return ResponseEntity.ok("Fájlok sikeresen feldolgozva és elmentve az adatbázisba.");
        } catch (Exception e) {
            log.error("Hiba a PST fájlok feldolgozása során: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Hiba történt: " + e.getMessage());
        }
    }

    @GetMapping("/updateDb")
    public ResponseEntity<String> updateDatabaseFileRecords() {
        try {
            searchService.updateDatabaseFileRecords();
            return ResponseEntity.ok("Adatbázis fájlrekordok sikeresen frissítve.");
        } catch (Exception e) {
            log.error("Hiba történt az adatbázis fájlrekordok frissítése közben: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Hiba történt az adatbázis fájlrekordok frissítése közben: " + e.getMessage());
        }
    }
}
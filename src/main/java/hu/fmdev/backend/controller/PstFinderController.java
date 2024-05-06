package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.FileInfo;
import hu.fmdev.backend.service.PstSearchService;
import hu.fmdev.backend.util.FileWriterUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@Slf4j
@RequestMapping("/find")
public class PstFinderController {

    @Autowired
    private PstSearchService searchService;

    @Autowired
    private FileWriterUtil fileWriterUtil;

    @GetMapping("/pstToTxt")
    public ResponseEntity<String> searchAndWritePstToTxt(@RequestParam List<String> directories, @RequestParam(required = false) List<String> excludedDirectories, @RequestParam String outputFile) {
        if (excludedDirectories == null) {
            excludedDirectories = new ArrayList<>();
        }
        try {
            if (directories.isEmpty()) {
                return ResponseEntity.badRequest().body("A keresési könyvtárak listája nem lehet üres.");
            }

            List<FileInfo> fileInfos = searchService.findPstFiles(directories, excludedDirectories);
            searchService.saveOrUpdateFileInfo(fileInfos);

            fileWriterUtil.writeFileInfoToFile(fileInfos, outputFile);

            return ResponseEntity.ok("Fájlok sikeresen feldolgozva, elmentve az adatbázisba és a fájlba: " + outputFile);
        } catch (Exception e) {
            log.error("Hiba a PST fájlok feldolgozása során: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Hiba történt: " + e.getMessage());
        }
    }

    @GetMapping("/pst")
    public ResponseEntity<String> searchAndWritePst(@RequestParam List<String> directories, @RequestParam(required = false) List<String> excludedDirectories, @RequestParam String outputFile) {
        if (excludedDirectories == null) {
            excludedDirectories = new ArrayList<>();
        }
        try {
            if (directories.isEmpty()) {
                return ResponseEntity.badRequest().body("A keresési könyvtárak listája nem lehet üres.");
            }

            searchService.findAndSavePstFiles(directories, excludedDirectories);

            return ResponseEntity.ok("Fájlok sikeresen feldolgozva és elmentve az adatbázisba.");
        } catch (Exception e) {
            log.error("Hiba a PST fájlok feldolgozása során: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Hiba történt: " + e.getMessage());
        }
    }


}
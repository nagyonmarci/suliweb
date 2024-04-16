package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.FileInfo;
import hu.fmdev.backend.repository.FileInfoRepository;
import hu.fmdev.backend.service.PstService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/pst")
public class PstController {


    private static final Logger logger = LoggerFactory.getLogger(PstController.class);

    private final FileInfoRepository fileInfoRepository;
    private final PstService pstService;

    public PstController(FileInfoRepository fileInfoRepository, PstService pstService) {
        this.fileInfoRepository = fileInfoRepository;
        this.pstService = pstService;
    }


    @PostMapping("/process")
    public ResponseEntity<String> processPstFile(@RequestParam("file") MultipartFile file) throws Exception {
        var tempFile = pstService.convertMultiPartToFile(file);
        var result = pstService.processPstFile(tempFile.getAbsolutePath());
        var deleted = tempFile.delete();
        if (!deleted) {
            throw new Exception("Temporary file could not be deleted: " + tempFile.getAbsolutePath());
        }
        return ResponseEntity.ok(result);
    }


    @PostMapping("/processFromTxt")
    public ResponseEntity<String> processPstFilesFromTxt(@RequestParam("file") MultipartFile file) throws IOException {
        var content = new BufferedReader(new InputStreamReader(file.getInputStream()))
                .lines().collect(Collectors.joining("\n"));
        Arrays.stream(content.split("\n"))
                .forEach(filePath -> {
                    try {
                        pstService.processPstFile(filePath.trim());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        return ResponseEntity.ok("PST files processed successfully from TXT");
    }


    @PostMapping("/processFromDb")
    public ResponseEntity<String> processPstFilesFromDb() throws Exception {
        List<FileInfo> fileInfoList = fileInfoRepository.findByStatusIn(Arrays.asList("Új", "Módosított"));
        for (FileInfo fileInfo : fileInfoList) {
            var filePath = fileInfo.getPath().trim();
            pstService.processPstFile(filePath);
            fileInfo.setStatus("Feldolgozva");
            fileInfoRepository.save(fileInfo);
        }
        return ResponseEntity.ok("PST files processed successfully from database");
    }
}

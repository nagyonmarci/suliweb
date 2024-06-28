package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.FileInfo;
import hu.fmdev.backend.repository.FileInfoRepository;
import hu.fmdev.backend.service.PstProcessorService;
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
public class PstProcessorController {


    private static final Logger logger = LoggerFactory.getLogger(PstProcessorController.class);

    private final FileInfoRepository fileInfoRepository;
    private final PstProcessorService pstProcessorService;

    public PstProcessorController(FileInfoRepository fileInfoRepository, PstProcessorService pstProcessorService) {
        this.fileInfoRepository = fileInfoRepository;
        this.pstProcessorService = pstProcessorService;
    }


    @PostMapping("/process")
    public ResponseEntity<String> processPstFile(@RequestParam("file") MultipartFile file) throws Exception {
        var tempFile = pstProcessorService.convertMultiPartToFile(file);
        var result = pstProcessorService.processPstFile(tempFile.getAbsolutePath());
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
                        pstProcessorService.processPstFile(filePath.trim());
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
            pstProcessorService.processPstFile(filePath);
            fileInfo.setStatus("Feldolgozva");
            fileInfoRepository.save(fileInfo);
        }
        return ResponseEntity.ok("PST files processed successfully from database");
    }
}

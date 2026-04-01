package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.FileInfo;
import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.repository.FileInfoRepository;
import hu.fmdev.backend.service.FileAccessService;
import hu.fmdev.backend.util.HashUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/file-infos")
public class FileInfoController {

    private final FileInfoRepository fileInfoRepository;
    private final FileAccessService fileAccessService;

    public FileInfoController(FileInfoRepository fileInfoRepository, FileAccessService fileAccessService) {
        this.fileInfoRepository = fileInfoRepository;
        this.fileAccessService = fileAccessService;
    }

    @GetMapping
    public List<FileInfo> getAllFileInfos() {
        Set<String> allowedIds = fileAccessService.getAllowedFileInfoIds();
        if (allowedIds == null) {
            return fileInfoRepository.findAll();
        }
        return fileInfoRepository.findAllById(allowedIds);
    }

    @GetMapping("/counts")
    public Map<String, Long> getCounts() {
        List<FileInfo> visible = getAllFileInfos();
        long total = visible.size();
        long pending = visible.stream().filter(f -> "New".equals(f.getStatus()) || "Modified".equals(f.getStatus())).count();
        long processed = visible.stream().filter(f -> "Processed".equals(f.getStatus())).count();
        return Map.of(
            "total", total,
            "pending", pending,
            "processed", processed
        );
    }

    @GetMapping("/duplicates")
    public List<List<FileInfo>> getDuplicates() {
        Map<String, List<FileInfo>> groups = fileInfoRepository.findAll().stream()
                .filter(f -> f.getContentHash() != null && f.getSize() > 0)
                .collect(Collectors.groupingBy(f -> f.getContentHash() + ":" + f.getSize()));

        return groups.values().stream()
                .filter(g -> g.size() > 1)
                .sorted((a, b) -> Long.compare(b.get(0).getSize(), a.get(0).getSize()))
                .collect(Collectors.toList());
    }

    @PostMapping("/compute-hashes")
    public ResponseEntity<String> computeHashes() {
        List<FileInfo> all = fileInfoRepository.findAll();
        int updated = 0;
        int skipped = 0;

        for (FileInfo fi : all) {
            if (fi.getContentHash() == null) {
                try {
                    var path = Paths.get(fi.getPath());
                    if (Files.exists(path)) {
                        fi.setContentHash(HashUtil.calculatePartialHash(path, 1_048_576));
                        fileInfoRepository.save(fi);
                        updated++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    CentralLogger.logError("Hash számítás hiba: " + fi.getPath(), e);
                    skipped++;
                }
            }
        }

        return ResponseEntity.ok("Hash kiszámítva: " + updated + " fájl, kihagyva: " + skipped + ".");
    }
}

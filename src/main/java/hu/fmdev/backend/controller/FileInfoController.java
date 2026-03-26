package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.FileInfo;
import hu.fmdev.backend.repository.FileInfoRepository;
import hu.fmdev.backend.service.FileAccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
}

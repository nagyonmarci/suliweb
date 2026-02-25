package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.FileInfo;
import hu.fmdev.backend.repository.FileInfoRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/file-infos")
public class FileInfoController {

    private final FileInfoRepository fileInfoRepository;

    public FileInfoController(FileInfoRepository fileInfoRepository) {
        this.fileInfoRepository = fileInfoRepository;
    }

    @GetMapping
    public List<FileInfo> getAllFileInfos() {
        return fileInfoRepository.findAll();
    }
}

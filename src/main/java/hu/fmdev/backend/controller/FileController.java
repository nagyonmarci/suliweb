package hu.fmdev.backend.controller;

import hu.fmdev.backend.service.FileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
public class FileController {

    private final FileService fileService;

    private static final Logger logger = LoggerFactory.getLogger(FileController.class);

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @GetMapping("/index")
    public CompletableFuture<String> index(
            @RequestParam String path,
            @RequestParam(required = false) List<String> exclude) {
        logger.info("Received request to index directory: {}", path);

        List<String> excludedDirectories = exclude != null ? exclude : List.of();

        return fileService.indexDirectory(path, excludedDirectories)
                .thenApply(v -> "Indexing completed for directory: " + path)
                .exceptionally(ex -> {
                    logger.error("Error indexing directory: {}", path, ex);
                    return "Failed to index directory: " + path;
                });
    }
}

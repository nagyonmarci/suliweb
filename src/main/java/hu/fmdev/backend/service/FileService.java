package hu.fmdev.backend.service;

import hu.fmdev.backend.domain.FileEntity;
import hu.fmdev.backend.repository.FileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class FileService {

    @Autowired
    private FileRepository fileRepository;

    private ExecutorService executorService = Executors.newFixedThreadPool(10);
    private static final Logger logger = LoggerFactory.getLogger(FileService.class);

    public CompletableFuture<Void> indexDirectory(String directoryPath, List<String> excludedDirectories) {
        return CompletableFuture.runAsync(() -> {
            File directory = new File(directoryPath);
            if (directory.exists() && directory.isDirectory()) {
                if (isExcluded(directory, excludedDirectories)) {
                    logger.info("Skipping excluded directory: {}", directoryPath);
                    return;
                }
                logger.info("Indexing directory: {}", directoryPath);
                File[] files = directory.listFiles();
                if (files != null) {
                    Set<String> currentFilePaths = new HashSet<>();
                    for (File file : files) {
                        currentFilePaths.add(file.getAbsolutePath());
                        executorService.submit(() -> processFile(file, excludedDirectories));
                    }
                    deleteMissingFiles(directoryPath, currentFilePaths);
                }
            } else {
                logger.error("Directory does not exist or is not a directory: {}", directoryPath);
            }
        }, executorService);
    }

    private void processFile(File file, List<String> excludedDirectories) {
        if (isExcluded(file, excludedDirectories)) {
            logger.info("Skipping excluded directory or file: {}", file.getAbsolutePath());
            return;
        }

        if (file.isFile()) {
            if (file.getName().endsWith(".tmp")) {
                logger.info("Skipping .tmp file: {}", file.getAbsolutePath());
                return;
            }
            saveOrUpdateFileEntity(file);
        } else if (file.isDirectory()) {
            logger.info("Entering subdirectory: {}", file.getAbsolutePath());
            indexDirectory(file.getAbsolutePath(), excludedDirectories);
        }
    }

    private boolean isExcluded(File file, List<String> excludedDirectories) {
        for (String excludedDir : excludedDirectories) {
            if (file.getAbsolutePath().contains(excludedDir)) {
                return true;
            }
        }
        return false;
    }

    private void saveOrUpdateFileEntity(File file) {
        try {
            Optional<FileEntity> existingFileEntityOpt = fileRepository.findByPath(file.getAbsolutePath());
            if (existingFileEntityOpt.isPresent()) {
                FileEntity existingFileEntity = existingFileEntityOpt.get();
                existingFileEntity.setName(file.getName());
                existingFileEntity.setSize(file.length());
                existingFileEntity.setLastModified(file.lastModified());
                fileRepository.save(existingFileEntity);
                logger.info("File updated in the database: {}", existingFileEntity);
            } else {
                FileEntity newFileEntity = new FileEntity();
                newFileEntity.setPath(file.getAbsolutePath());
                newFileEntity.setName(file.getName());
                newFileEntity.setSize(file.length());
                newFileEntity.setLastModified(file.lastModified());
                fileRepository.save(newFileEntity);
                logger.info("File saved to the database: {}", newFileEntity);
            }
        } catch (Exception e) {
            logger.error("Error processing file: {}", file.getAbsolutePath(), e);
        }
    }

    private void deleteMissingFiles(String directoryPath, Set<String> currentFilePaths) {
        List<FileEntity> storedFiles = fileRepository.findByPathStartingWith(directoryPath);
        for (FileEntity storedFile : storedFiles) {
            if (!currentFilePaths.contains(storedFile.getPath())) {
                fileRepository.delete(storedFile);
                logger.info("Deleted missing file from database: {}", storedFile.getPath());
            }
        }
    }
}

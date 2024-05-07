package hu.fmdev.backend.service;

import hu.fmdev.backend.exceptionhandler.FileCompressionException;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class FileUploadService {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadService.class);
    private static final String UPLOAD_DIR = "/uploads/";
    private final String uploadDirPath = System.getProperty("user.dir") + File.separator + "uploads";

    public Path uploadAndCompressFile(MultipartFile file, String password, String zipName) throws Exception {
        try {
            Path tempFile = createTemporaryFile(file);
            try {
                return compressFile(tempFile, zipName, password);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            logger.error("Error during file upload and compression", e);
            throw new Exception("Error processing file", e);
        }
    }

    private Path createTemporaryFile(MultipartFile file) throws IOException {
        File uploadDir = new File(uploadDirPath);
        if (!uploadDir.exists()) {
            uploadDir.mkdirs();
        }
        String originalFileName = file.getOriginalFilename();
        String fileNamePrefix = originalFileName != null ? originalFileName : "uploaded";
        return Files.createTempFile(uploadDir.toPath(), fileNamePrefix, ".tmp");
    }

    private Path compressFile(Path tempFile, String zipName, String password) throws FileCompressionException {
        String finalZipName = zipName.isEmpty() ? tempFile.getFileName().toString() : zipName;
        String zipFileName = finalZipName + ".zip";
        Path zipFilePath = Paths.get(uploadDirPath, zipFileName);

        try {
            ZipFile zipFile = new ZipFile(zipFilePath.toString(), password.toCharArray());
            ZipParameters zipParameters = new ZipParameters();
            zipParameters.setEncryptFiles(true);
            zipParameters.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD);
            zipParameters.setCompressionMethod(CompressionMethod.DEFLATE);
            zipFile.addFile(tempFile.toFile(), zipParameters);
            return zipFilePath;
        } catch (ZipException e) {
            String errorMsg = "Failed to compress file: " + zipFileName;
            logger.error(errorMsg, e);
            throw new FileCompressionException(errorMsg, e);
        }
    }

    public String buildCompleteFileUrl(String baseUrl, Path zipFilePath) {
        return baseUrl + UPLOAD_DIR + zipFilePath.getFileName().toString();
    }

}
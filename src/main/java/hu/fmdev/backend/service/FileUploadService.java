package hu.fmdev.backend.service;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class FileUploadService {

    private final String uploadDirPath = System.getProperty("user.dir") + File.separator + "uploads";

    public Path uploadAndCompressFile(MultipartFile file, String password, String zipName) throws Exception {
        File uploadDir = new File(uploadDirPath);
        if (!uploadDir.exists()) {
            uploadDir.mkdirs();
        }

        String originalFileName = file.getOriginalFilename();
        String fileNamePrefix = originalFileName != null ? originalFileName : "uploaded";
        Path tempFile = Files.createTempFile(uploadDir.toPath(), fileNamePrefix, ".tmp");
        file.transferTo(tempFile.toFile());

        String finalZipName = zipName.isEmpty() ? originalFileName : zipName;

        String zipFileName = finalZipName + ".zip";
        ZipFile zipFile = new ZipFile(uploadDir.toPath().resolve(zipFileName).toString(), password.toCharArray());
        ZipParameters zipParameters = new ZipParameters();
        zipParameters.setEncryptFiles(true);
        zipParameters.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD);
        zipParameters.setCompressionMethod(CompressionMethod.DEFLATE);
        zipFile.addFile(tempFile.toFile(), zipParameters);

        Files.delete(tempFile);

        return uploadDir.toPath().resolve(zipFileName);
    }
}

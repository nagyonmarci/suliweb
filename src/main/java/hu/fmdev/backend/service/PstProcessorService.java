package hu.fmdev.backend.service;

import com.pff.PSTAttachment;
import com.pff.PSTFile;
import com.pff.PSTFolder;
import com.pff.PSTMessage;
import hu.fmdev.backend.domain.Email;
import hu.fmdev.backend.domain.FileInfo;
import hu.fmdev.backend.exceptionhandler.PstProcessingException;
import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.repository.EmailRepository;
import hu.fmdev.backend.repository.FileInfoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class PstProcessorService {
    private final EmailRepository emailRepository;
    private final FileInfoRepository fileInfoRepository;

    @Value("${attachments.directory}")
    private String attachmentsDirectory;

    private final CentralLogger centralLogger;

    private static final int THREAD_POOL_SIZE = 10;

    public PstProcessorService(EmailRepository emailRepository, FileInfoRepository fileInfoRepository, CentralLogger centralLogger) {
        this.emailRepository = emailRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.centralLogger = centralLogger;
    }

    public String processPstFileFromUpload(MultipartFile file, boolean saveAttachments) throws PstProcessingException {
        try {
            File pstFile = convertMultiPartToFile(file);
            return processAndCleanUp(pstFile, saveAttachments);
        } catch (Exception e) {
            throw new PstProcessingException("Error processing PST file", e);
        }
    }

    public void processPstFilesFromTxt(String txtFilePath, boolean saveAttachments) throws IOException {
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

        List<Callable<String>> tasks = Files.lines(Paths.get(txtFilePath))
                .map(pstFilePath -> (Callable<String>) () -> processPstFile(pstFilePath, saveAttachments))
                .collect(Collectors.toList());

        try {
            List<Future<String>> futures = executorService.invokeAll(tasks);
            for (Future<String> future : futures) {
                try {
                    centralLogger.logInfo(future.get());
                } catch (ExecutionException e) {
                    centralLogger.logError("Error processing PST file", e.getCause());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            centralLogger.logError("Processing interrupted", e);
        } finally {
            executorService.shutdown();
        }
    }

    public void processPstFilesFromDb(boolean saveAttachments) {
        List<FileInfo> fileInfoList = fileInfoRepository.findByStatusIn(Arrays.asList("New", "Modified"));
        if (fileInfoList.isEmpty()) {
            centralLogger.logInfo("No PST files to process from the database");
            return;
        }

        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

        List<Callable<Void>> tasks = fileInfoList.stream()
                .map(fileInfo -> (Callable<Void>) () -> {
                    try {
                        centralLogger.logInfo("Processing file: " + fileInfo.getPath());
                        processPstFile(fileInfo.getPath().trim(), saveAttachments);
                        fileInfo.setStatus("Processed");
                        fileInfoRepository.save(fileInfo);
                    } catch (Exception e) {
                        centralLogger.logError("Error processing PST file from database: " + fileInfo.getPath(), e);
                    }
                    return null;
                })
                .collect(Collectors.toList());

        try {
            executorService.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            centralLogger.logError("Processing interrupted", e);
        } finally {
            executorService.shutdown();
        }
    }

    public String processPstFile(String filePath, boolean saveAttachments) throws IOException {
        File pstFile = new File(filePath);
        if (!pstFile.exists() || !pstFile.canRead()) {
            throw new IOException("File does not exist or cannot be read at " + filePath);
        }
        return processAndCleanUp(pstFile, saveAttachments);
    }

    private String processAndCleanUp(File pstFile, boolean saveAttachments) throws IOException {
        PSTFile pst = null;
        try {
            pst = new PSTFile(pstFile.getAbsolutePath());
            processFolder(pst.getRootFolder(), pstFile.getName(), saveAttachments);
        } catch (Exception e) {
            throw new IOException("Error processing PST file at " + pstFile.getAbsolutePath(), e);
        } finally {
            if (pst != null) {
                try {
                    pst.close();
                } catch (Exception e) {
                    centralLogger.logError("Error closing PST file", e);
                }
            }
        }
        return pstFile.getAbsolutePath();
    }

    public File convertMultiPartToFile(MultipartFile multipartFile) throws IOException {
        String tempDir = System.getProperty("java.io.tmpdir");
        File file = new File(Paths.get(tempDir, multipartFile.getOriginalFilename()).toString());
        try (InputStream in = multipartFile.getInputStream(); OutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
        }
        return file;
    }

    private void processFolder(PSTFolder folder, String pstFileName, boolean saveAttachments) throws Exception {
        String currentFolderPath = folder.getDisplayName();
        processMessages(folder, pstFileName, currentFolderPath, saveAttachments);
        if (folder.hasSubfolders()) {
            for (PSTFolder subFolder : folder.getSubFolders()) {
                processFolder(subFolder, pstFileName, saveAttachments);
            }
        }
    }

    private void processMessages(PSTFolder folder, String pstFileName, String currentFolderPath, boolean saveAttachments) throws Exception {
        PSTMessage message = (PSTMessage) folder.getNextChild();
        while (message != null) {
            if (isSupportedMessageType(message)) {
                processMessage(message, pstFileName, currentFolderPath, saveAttachments);
            } else {
                centralLogger.logWarn("Unsupported message type: " + message.getMessageClass() + " in " + pstFileName);
            }
            message = (PSTMessage) folder.getNextChild();
        }
    }

    private void processMessage(PSTMessage message, String pstFileName, String currentFolderPath, boolean saveAttachments) throws Exception {
        String uniqueEntryId = generateUniqueEntryId(pstFileName, message.getDescriptorNodeId());

        if (emailRepository.existsByUniqueEntryId(uniqueEntryId)) {
            centralLogger.logInfo("Skipping already processed email with ID: " + uniqueEntryId);
            return;
        }

        Email email = createNewEmail(uniqueEntryId, pstFileName, currentFolderPath);
        updateEmailWithMessageDetails(email, message);
        if (saveAttachments) {
            saveEmailWithAttachments(email, message, pstFileName, uniqueEntryId);
        }
        emailRepository.save(email);
    }


    private Email createNewEmail(String uniqueEntryId, String pstFileName, String currentFolderPath) {
        Email email = new Email();
        email.setUniqueEntryId(uniqueEntryId);
        email.setPstFileName(pstFileName);
        email.setFolderPath(currentFolderPath);
        return email;
    }

    private void updateEmailWithMessageDetails(Email email, PSTMessage message) {
        email.setSenderEmailAddress(message.getSenderEmailAddress());
        email.setSenderName(message.getSenderName());
        email.setSubject(message.getSubject());
        email.setReceivedTime(convertToLocalDateTime(message.getMessageDeliveryTime()));
        email.setBody(message.getBody());
        email.setHtmlContent(message.getBodyHTML());
        email.setRecipients(splitStringToList(message.getDisplayTo()));
        email.setCc(splitStringToList(message.getDisplayCC()));
        email.setBcc(splitStringToList(message.getDisplayBCC()));
    }

    private LocalDateTime convertToLocalDateTime(Date date) {
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    private void saveEmailWithAttachments(Email email, PSTMessage message, String pstFileName, String uniqueEntryId) throws Exception {
        try {
            List<String> attachmentPaths = saveAttachments(message, pstFileName, uniqueEntryId);
            email.setAttachmentPaths(attachmentPaths);
        } catch (Exception e) {
            throw new Exception("Failed to save attachments.", e);
        }
    }

    private List<String> splitStringToList(String input) {
        return input == null || input.isEmpty() ? Collections.emptyList() : Arrays.asList(input.split(";"));
    }

    public String generateUniqueEntryId(String pstFileName, long descriptorNodeId) {
        try {
            String identifier = pstFileName + "-" + descriptorNodeId;

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(identifier.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            centralLogger.logError("Error generating unique entry ID", e);
            return pstFileName + "-" + descriptorNodeId;
        }
    }

    private boolean isSupportedMessageType(PSTMessage message) {
        String messageType = message.getMessageClass();
        return messageType.startsWith("IPM.Note");
    }

    private List<String> saveAttachments(PSTMessage message, String pstFileName, String uniqueEntryId) throws Exception {
        List<String> attachmentPaths = new ArrayList<>();
        String attachmentsDirPath = attachmentsDirectory + "/" + pstFileName + "/" + uniqueEntryId;
        Path directoryPath = Paths.get(attachmentsDirPath);

        if (!Files.exists(directoryPath)) {
            Files.createDirectories(directoryPath);
        }

        int attachmentCount = message.getNumberOfAttachments();
        for (int i = 0; i < attachmentCount; i++) {
            PSTAttachment attachment = message.getAttachment(i);
            String filename = attachment.getLongFilename();
            if (filename == null || filename.isEmpty()) {
                filename = "attachment" + i;
            }
            Path filePath = directoryPath.resolve(filename);
            File attachmentFile = filePath.toFile();

            try (OutputStream os = new FileOutputStream(attachmentFile);
                 InputStream in = attachment.getFileInputStream()) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) > 0) {
                    os.write(buffer, 0, len);
                }
            }
            attachmentPaths.add(filePath.toString());
        }
        return attachmentPaths;
    }
}

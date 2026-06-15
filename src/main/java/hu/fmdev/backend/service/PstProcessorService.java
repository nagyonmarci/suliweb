package hu.fmdev.backend.service;

import com.pff.PSTAttachment;
import com.pff.PSTFile;
import com.pff.PSTFolder;
import com.pff.PSTMessage;
import hu.fmdev.backend.domain.Email;
import hu.fmdev.backend.domain.FileInfo;
import hu.fmdev.backend.dto.ProcessFileRequest;
import hu.fmdev.backend.exceptionhandler.PstProcessingException;
import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.repository.AttachmentRepository;
import hu.fmdev.backend.repository.EmailRepository;
import hu.fmdev.backend.repository.FileInfoRepository;
import hu.fmdev.backend.service.rag.TextExtractionService;
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
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class PstProcessorService {
    private final EmailRepository emailRepository;
    private final FileInfoRepository fileInfoRepository;
    private final AttachmentRepository attachmentRepository;
    private final ProgressTracker progressTracker;
    private final TextExtractionService textExtractionService;
    private volatile boolean paused = false;

    @Value("${attachments.directory}")
    private String attachmentsDirectory;

    private static final int THREAD_POOL_SIZE = 10;

    public void pauseProcessing() {
        paused = true;
    }

    public void resumeProcessing() {
        paused = false;
        synchronized (this) {
            notifyAll();
        }
    }

    private void checkPaused() throws InterruptedException {
        synchronized (this) {
            while (paused) {
                wait();
            }
        }
    }

    public PstProcessorService(EmailRepository emailRepository, FileInfoRepository fileInfoRepository,
            AttachmentRepository attachmentRepository, ProgressTracker progressTracker,
            TextExtractionService textExtractionService) {
        this.emailRepository = emailRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.attachmentRepository = attachmentRepository;
        this.progressTracker = progressTracker;
        this.textExtractionService = textExtractionService;
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
                    CentralLogger.logInfo(future.get());
                } catch (ExecutionException e) {
                    CentralLogger.logError("Error processing PST file", e.getCause());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CentralLogger.logError("Processing interrupted", e);
        } finally {
            executorService.shutdown();
        }
    }

    public void processPstFilesFromDb(boolean saveAttachments) {
        List<FileInfo> fileInfoList = fileInfoRepository.findByStatusIn(Arrays.asList("New", "Modified"));
        if (fileInfoList.isEmpty()) {
            CentralLogger.logInfo("No PST files to process from the database");
            return;
        }

        // Duplikátum-szűrés: ugyanaz a contentHash már feldolgozott fájlban
        Set<String> processedHashes = fileInfoRepository.findByStatusIn(List.of("Processed")).stream()
                .map(FileInfo::getContentHash)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<FileInfo> duplicates = fileInfoList.stream()
                .filter(f -> f.getContentHash() != null && processedHashes.contains(f.getContentHash()))
                .collect(Collectors.toList());

        if (!duplicates.isEmpty()) {
            duplicates.forEach(f -> {
                f.setStatus("Processed");
                fileInfoRepository.save(f);
                CentralLogger.logInfo("Duplikált PST kihagyva (már feldolgozott tartalom): " + f.getPath());
            });
        }

        List<FileInfo> toProcess = fileInfoList.stream()
                .filter(f -> f.getContentHash() == null || !processedHashes.contains(f.getContentHash()))
                .collect(Collectors.toList());

        if (toProcess.isEmpty()) {
            CentralLogger.logInfo("Minden fájl duplikátum, nincs új feldolgoznivaló.");
            return;
        }

        // Start tracking PST files processing
        progressTracker.startOperation("PST Fájlok feldolgozása az adatbázisból", toProcess.size());

        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

        List<Callable<Void>> tasks = toProcess.stream()
                .map(fileInfo -> (Callable<Void>) () -> {
                    try {
                        String fileName = Paths.get(fileInfo.getPath()).getFileName().toString();
                        CentralLogger.logInfo("Processing file: " + fileInfo.getPath());
                        progressTracker.setStatusDetail("Feldolgozás: " + fileName);
                        processPstFile(fileInfo.getPath().trim(), saveAttachments);
                        fileInfo.setStatus("Processed");
                        fileInfo.setAttachmentsSaved(saveAttachments);
                        fileInfoRepository.save(fileInfo);
                    } catch (Exception e) {
                        CentralLogger.logError("Error processing PST file from database: " + fileInfo.getPath(), e);
                        updateStatusOnError(fileInfo, e);
                    } finally {
                        progressTracker.increment();
                    }
                    return null;
                })
                .collect(Collectors.toList());

        try {
            executorService.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CentralLogger.logError("Processing interrupted", e);
        } finally {
            progressTracker.stopOperation();
            executorService.shutdown();
        }
    }

    public String processPstFile(String filePath, boolean saveAttachments) throws IOException {
        File pstFile = new File(filePath);
        if (!pstFile.exists() || !pstFile.canRead()) {
            throw new IOException("File does not exist or cannot be read at " + filePath);
        }

        // Ha a tracker még nem aktív (nem tömeges futtatásból hívták, hanem egyedi fájl
        // feltöltésből)
        boolean locallyStarted = false;
        if (!progressTracker.getProgress().isActive()) {
            progressTracker.startOperation("Egyedi fájl feldolgozása: " + pstFile.getName(), 1);
            locallyStarted = true;
        }

        try {
            return processAndCleanUp(pstFile, saveAttachments);
        } finally {
            if (locallyStarted) {
                progressTracker.increment();
                progressTracker.stopOperation();
            }
        }
    }

    private String processAndCleanUp(File pstFile, boolean saveAttachments) throws IOException {
        PSTFile pst = null;
        try {
            pst = new PSTFile(pstFile.getAbsolutePath());
            processRootFolder(pst.getRootFolder(), pstFile.getName(), saveAttachments);
        } catch (Exception e) {
            throw new IOException("Error processing PST file at " + pstFile.getAbsolutePath(), e);
        } finally {
            if (pst != null) {
                try {
                    pst.close();
                } catch (Exception e) {
                    CentralLogger.logError("Error closing PST file", e);
                }
            }
        }
        return pstFile.getAbsolutePath();
    }

    public File convertMultiPartToFile(MultipartFile multipartFile) throws IOException {
        String tempDir = System.getProperty("java.io.tmpdir");
        String originalFilename = multipartFile.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = "upload.pst";
        }
        // Strip path separators to prevent directory traversal via filename
        String safeFilename = Paths.get(originalFilename).getFileName().toString();
        File file = new File(Paths.get(tempDir, safeFilename).toString());
        try (InputStream in = multipartFile.getInputStream(); OutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
        }
        return file;
    }

    private void processRootFolder(PSTFolder rootFolder, String pstFileName, boolean saveAttachments) throws Exception {
        processMessages(rootFolder, pstFileName, "", saveAttachments);
        if (rootFolder.hasSubfolders()) {
            for (PSTFolder subFolder : rootFolder.getSubFolders()) {
                processFolder(subFolder, pstFileName, "", saveAttachments);
            }
        }
    }

    private void processFolder(PSTFolder folder, String pstFileName, String parentPath, boolean saveAttachments) throws Exception {
        String currentName = folder.getDisplayName();
        
        String currentFolderPath;
        if (parentPath.isEmpty() && (
                currentName.equalsIgnoreCase("Outlook-adatfájl teteje") ||
                currentName.equalsIgnoreCase("Top of Outlook data file") ||
                currentName.equalsIgnoreCase("Top of Information Store") ||
                currentName.equalsIgnoreCase("Top of Personal Folders") ||
                currentName.equalsIgnoreCase("Személyes mappák"))) {
            currentFolderPath = "";
        } else {
            currentFolderPath = parentPath.isEmpty() ? currentName : parentPath + " / " + currentName;
        }

        processMessages(folder, pstFileName, currentFolderPath, saveAttachments);
        if (folder.hasSubfolders()) {
            for (PSTFolder subFolder : folder.getSubFolders()) {
                processFolder(subFolder, pstFileName, currentFolderPath, saveAttachments);
            }
        }
    }

    private void processMessages(PSTFolder folder, String pstFileName, String currentFolderPath,
            boolean saveAttachments) throws Exception {
        PSTMessage message = (PSTMessage) folder.getNextChild();
        while (message != null) {
            checkPaused(); // Ellenőrizzük, hogy szüneteltetett-e a feldolgozás
            if (isSupportedMessageType(message)) {
                processMessage(message, pstFileName, currentFolderPath, saveAttachments);
            } else {
                CentralLogger.logWarn("Unsupported message type: " + message.getMessageClass() + " in " + pstFileName);
            }
            message = (PSTMessage) folder.getNextChild();
        }
    }

    private void processMessage(PSTMessage message, String pstFileName, String currentFolderPath,
            boolean saveAttachments) throws Exception {
        checkPaused(); // Ellenőrizzük, hogy szüneteltetett-e a feldolgozás
        String uniqueEntryId = generateUniqueEntryId(pstFileName, message.getDescriptorNodeId());

        java.util.Optional<Email> existingOpt = emailRepository.findByUniqueEntryId(uniqueEntryId);
        if (existingOpt.isPresent()) {
            Email existingEmail = existingOpt.get();
            if (!currentFolderPath.equals(existingEmail.getFolderPath())) {
                existingEmail.setFolderPath(currentFolderPath);
                emailRepository.save(existingEmail);
                CentralLogger.logInfo("Updated folder path for existing email: " + uniqueEntryId);
            } else {
                CentralLogger.logInfo("Skipping already processed email with ID: " + uniqueEntryId);
            }
            return;
        }

        Email email = createNewEmail(uniqueEntryId, pstFileName, currentFolderPath);
        updateEmailWithMessageDetails(email, message);
        email.setStrippedBody(textExtractionService.getEmailTextContent(email.getBody(), email.getHtmlContent()));
        emailRepository.save(email); // Save first to get the ID

        if (saveAttachments) {
            saveEmailWithAttachments(email, message, pstFileName);
            emailRepository.save(email); // Save again if attachments were added
        }
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

        // --- Extended Metadata Extraction ---
        email.setInternetMessageId(message.getInternetMessageId());
        email.setConversationTopic(message.getConversationTopic());

        byte[] conversationId = message.getConversationId();
        if (conversationId != null) {
            email.setConversationId(bytesToHex(conversationId));
        }

        email.setIsRead(message.isRead());
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private LocalDateTime convertToLocalDateTime(Date date) {
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    private void saveEmailWithAttachments(Email email, PSTMessage message, String pstFileName)
            throws Exception {
        try {
            List<String> attachmentPaths = saveAttachments(message, pstFileName, email);
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
                if (hex.length() == 1)
                    hexString.append('0');
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            CentralLogger.logError("Error generating unique entry ID", e);
            return pstFileName + "-" + descriptorNodeId;
        }
    }

    private boolean isSupportedMessageType(PSTMessage message) {
        String messageType = message.getMessageClass();
        return messageType.startsWith("IPM.Note");
    }

    public void processPstFilesSelected(List<ProcessFileRequest> requests) {
        List<FileInfo> fileInfoList = requests.stream()
                .map(r -> fileInfoRepository.findById(r.id()))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .collect(Collectors.toList());

        if (fileInfoList.isEmpty()) {
            CentralLogger.logInfo("No PST files found for selected IDs");
            return;
        }

        progressTracker.startOperation("Kijelölt PST fájlok feldolgozása", fileInfoList.size());
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

        Map<String, Boolean> saveAttachmentsMap = requests.stream()
                .collect(Collectors.toMap(ProcessFileRequest::id, ProcessFileRequest::saveAttachments));

        List<Callable<Void>> tasks = fileInfoList.stream()
                .map(fileInfo -> (Callable<Void>) () -> {
                    boolean saveAtts = saveAttachmentsMap.getOrDefault(fileInfo.getId(), false);
                    try {
                        String fileName = Paths.get(fileInfo.getPath()).getFileName().toString();
                        progressTracker.setStatusDetail("Feldolgozás: " + fileName);
                        if ("Processed".equals(fileInfo.getStatus())) {
                            if (saveAtts && !fileInfo.isAttachmentsSaved()) {
                                saveAttachmentsForPstFile(fileInfo.getPath().trim(), fileName);
                                fileInfo.setAttachmentsSaved(true);
                                fileInfoRepository.save(fileInfo);
                            }
                        } else {
                            processPstFile(fileInfo.getPath().trim(), saveAtts);
                            fileInfo.setStatus("Processed");
                            fileInfo.setAttachmentsSaved(saveAtts);
                            fileInfoRepository.save(fileInfo);
                        }
                    } catch (Exception e) {
                        CentralLogger.logError("Error processing selected PST file: " + fileInfo.getPath(), e);
                        updateStatusOnError(fileInfo, e);
                    } finally {
                        progressTracker.increment();
                    }
                    return null;
                })
                .collect(Collectors.toList());

        try {
            executorService.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CentralLogger.logError("Processing interrupted", e);
        } finally {
            progressTracker.stopOperation();
            executorService.shutdown();
        }
    }

    private void updateStatusOnError(FileInfo fileInfo, Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        boolean missingFile = msg.contains("does not exist") || msg.contains("cannot be read");
        boolean invalidPst = e.getCause() instanceof com.pff.PSTException
                || (e.getCause() != null && e.getCause().getMessage() != null
                    && e.getCause().getMessage().contains("Invalid"));
        if (missingFile) {
            fileInfo.setStatus("Missing");
            fileInfoRepository.save(fileInfo);
        } else if (invalidPst) {
            fileInfo.setStatus("Invalid");
            fileInfoRepository.save(fileInfo);
        }
    }

    public void saveAttachmentsFromDb() {
        List<FileInfo> fileInfoList = fileInfoRepository.findByStatusAndAttachmentsSavedFalse("Processed");
        if (fileInfoList.isEmpty()) {
            CentralLogger.logInfo("No processed PST files require attachment saving");
            return;
        }

        progressTracker.startOperation("Csatolmányok mentése", fileInfoList.size());
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

        List<Callable<Void>> tasks = fileInfoList.stream()
                .map(fileInfo -> (Callable<Void>) () -> {
                    try {
                        String fileName = Paths.get(fileInfo.getPath()).getFileName().toString();
                        progressTracker.setStatusDetail("Csatolmányok mentése: " + fileName);
                        saveAttachmentsForPstFile(fileInfo.getPath().trim(), fileName);
                        fileInfo.setAttachmentsSaved(true);
                        fileInfoRepository.save(fileInfo);
                    } catch (Exception e) {
                        CentralLogger.logError("Error saving attachments for: " + fileInfo.getPath(), e);
                    } finally {
                        progressTracker.increment();
                    }
                    return null;
                })
                .collect(Collectors.toList());

        try {
            executorService.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CentralLogger.logError("Processing interrupted", e);
        } finally {
            progressTracker.stopOperation();
            executorService.shutdown();
        }
    }

    private void saveAttachmentsForPstFile(String filePath, String pstFileName) throws IOException {
        File pstFile = new File(filePath);
        if (!pstFile.exists() || !pstFile.canRead()) {
            throw new IOException("File does not exist or cannot be read at " + filePath);
        }
        PSTFile pst = null;
        try {
            pst = new PSTFile(pstFile.getAbsolutePath());
            saveAttachmentsForFolder(pst.getRootFolder(), pstFileName);
        } catch (Exception e) {
            throw new IOException("Error processing PST file at " + filePath, e);
        } finally {
            if (pst != null) {
                try { pst.close(); } catch (Exception e) {
                    CentralLogger.logError("Error closing PST file", e);
                }
            }
        }
    }

    private void saveAttachmentsForFolder(PSTFolder folder, String pstFileName) throws Exception {
        saveAttachmentsForMessages(folder, pstFileName);
        if (folder.hasSubfolders()) {
            for (PSTFolder subFolder : folder.getSubFolders()) {
                saveAttachmentsForFolder(subFolder, pstFileName);
            }
        }
    }

    private void saveAttachmentsForMessages(PSTFolder folder, String pstFileName) throws Exception {
        PSTMessage message = (PSTMessage) folder.getNextChild();
        while (message != null) {
            checkPaused();
            if (isSupportedMessageType(message) && message.getNumberOfAttachments() > 0) {
                String uniqueEntryId = generateUniqueEntryId(pstFileName, message.getDescriptorNodeId());
                java.util.Optional<Email> existingOpt = emailRepository.findByUniqueEntryId(uniqueEntryId);
                if (existingOpt.isPresent()) {
                    Email email = existingOpt.get();
                    if (email.getAttachmentPaths() == null || email.getAttachmentPaths().isEmpty()) {
                        saveEmailWithAttachments(email, message, pstFileName);
                        emailRepository.save(email);
                    }
                }
            }
            message = (PSTMessage) folder.getNextChild();
        }
    }

    private List<String> saveAttachments(PSTMessage message, String pstFileName, Email email)
            throws Exception {
        List<String> attachmentPaths = new ArrayList<>();
        
        // Central directory for deduplicated files
        Path hashesDirPath = Paths.get(attachmentsDirectory, "hashes");
        if (!Files.exists(hashesDirPath)) {
            Files.createDirectories(hashesDirPath);
        }

        // Temp directory for streaming before we know the hash
        Path tempDirPath = Paths.get(attachmentsDirectory, "temp", email.getUniqueEntryId());
        if (!Files.exists(tempDirPath)) {
            Files.createDirectories(tempDirPath);
        }

        int attachmentCount = message.getNumberOfAttachments();
        for (int i = 0; i < attachmentCount; i++) {
            PSTAttachment attachment = message.getAttachment(i);
            String filename = attachment.getLongFilename();
            if (filename == null || filename.isEmpty()) {
                filename = "attachment" + i;
            }
            
            Path tempFilePath = tempDirPath.resolve(filename + ".tmp");
            File tempAttachmentFile = tempFilePath.toFile();
            String fileHash;

            try (OutputStream os = new FileOutputStream(tempAttachmentFile);
                 InputStream in = attachment.getFileInputStream()) {
                 
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                java.security.DigestOutputStream dos = new java.security.DigestOutputStream(os, digest);
                
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) > 0) {
                    dos.write(buffer, 0, len);
                }
                dos.flush();
                
                byte[] hashBytes = digest.digest();
                StringBuilder hexString = new StringBuilder();
                for (byte b : hashBytes) {
                    String hex = Integer.toHexString(0xff & b);
                    if (hex.length() == 1) hexString.append('0');
                    hexString.append(hex);
                }
                fileHash = hexString.toString();
            }
            
            Path finalFilePath = hashesDirPath.resolve(fileHash);
            if (!Files.exists(finalFilePath)) {
                Files.move(tempFilePath, finalFilePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(tempFilePath);
            }
            
            attachmentPaths.add(finalFilePath.toString());

            // Create and save Attachment entity
            hu.fmdev.backend.domain.Attachment attachmentEntity = new hu.fmdev.backend.domain.Attachment();
            attachmentEntity.setEmailId(email.getId());
            attachmentEntity.setFilename(filename);
            attachmentEntity.setSize(finalFilePath.toFile().length());
            attachmentEntity.setLocalPath(finalFilePath.toString());
            attachmentEntity.setHash(fileHash);
            attachmentEntity.setPstFileName(pstFileName);
            attachmentEntity.setCreationTime(LocalDateTime.now());
            attachmentEntity.setContentType(Files.probeContentType(finalFilePath));
            
            // Context from email
            attachmentEntity.setEmailSubject(email.getSubject());
            attachmentEntity.setSenderName(email.getSenderName());
            attachmentEntity.setReceivedTime(email.getReceivedTime());
            
            attachmentRepository.save(attachmentEntity);
        }
        return attachmentPaths;
    }
}

package hu.fmdev.backend.service;

import com.pff.PSTAttachment;
import com.pff.PSTFile;
import com.pff.PSTFolder;
import com.pff.PSTMessage;
import hu.fmdev.backend.domain.Email;
import hu.fmdev.backend.exceptionhandler.PstProcessingException;
import hu.fmdev.backend.repository.EmailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Service
public class PstService {
    private final EmailRepository emailRepository;
    @Value("${attachments.directory}")
    private String attachmentsDirectory;
    private static final Logger logger = LoggerFactory.getLogger(PstService.class);

    public PstService(EmailRepository emailRepository) {
        this.emailRepository = emailRepository;
    }

    public String processPstFileFromUpload(MultipartFile file) throws PstProcessingException {
        try {
            File pstFile = convertMultiPartToFile(file);
            PSTFile pst = new PSTFile(pstFile.getAbsolutePath());
            processFolder(pst.getRootFolder(), pstFile.getName());
            Files.deleteIfExists(pstFile.toPath());
            return "PST file processed successfully";
        } catch (Exception e) {
            throw new PstProcessingException("Error processing PST file", e);
        }
    }

    public void processPstFilesFromTxt(String txtFilePath) throws IOException {
        Files.lines(Paths.get(txtFilePath)).forEach(pstFilePath -> {
            try {
                String result = processPstFile(pstFilePath);
                logger.info(result);
            } catch (Exception e) {
                logger.error("Error processing PST file at path: " + pstFilePath, e);
            }
        });
    }


    public String processPstFile(String filePath) {
        File pstFile = new File(filePath);
        if (!pstFile.exists() || !pstFile.canRead()) {
            String errorMessage = "Error: File does not exist or cannot be read at " + filePath;
            logger.error(errorMessage);
            return errorMessage;
        }

        PSTFile pst = null;
        try {
            pst = new PSTFile(pstFile.getAbsolutePath());
            processFolder(pst.getRootFolder(), pstFile.getName());
            return "PST file processed successfully";
        } catch (Exception e) {
            String errorMessage = "Error processing PST file at " + filePath;
            logger.error(errorMessage, e);
            return errorMessage;
        } finally {
            if (pst != null) {
                try {
                    pst.close();
                } catch (Exception e) {
                    logger.error("Failed to close PST file at " + filePath, e);
                }
            }
        }
    }


    private File convertMultiPartToFile(MultipartFile file) throws Exception {
        File convFile = new File(file.getOriginalFilename());
        try (OutputStream os = new FileOutputStream(convFile);
             InputStream is = file.getInputStream()) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
        }
        return convFile;
    }

    private void processFolder(PSTFolder folder, String pstFileName) throws Exception {
        String currentFolderPath = folder.getDisplayName();
        processMessages(folder, pstFileName, currentFolderPath);
        if (folder.hasSubfolders()) {
            for (PSTFolder subFolder : folder.getSubFolders()) {
                processFolder(subFolder, pstFileName);
            }
        }
    }

    private void processMessage(PSTMessage message, String pstFileName, String currentFolderPath) throws Exception {
        String uniqueEntryId = generateUniqueEntryId(pstFileName, message.getDescriptorNodeId());
        Email email = emailRepository.findByUniqueEntryId(uniqueEntryId)
                .orElseGet(() -> createNewEmail(uniqueEntryId, pstFileName, currentFolderPath));
        updateEmailWithMessageDetails(email, message);
        saveEmailWithAttachments(email, message, pstFileName, uniqueEntryId);
        emailRepository.save(email);
    }


    private void processMessages(PSTFolder folder, String pstFileName, String currentFolderPath) throws Exception {
        PSTMessage message = (PSTMessage) folder.getNextChild();
        while (message != null) {
            if (isSupportedMessageType(message)) {
                processMessage(message, pstFileName, currentFolderPath);
            } else {
                logger.warn("Unsupported message type: {} in {}", message.getMessageClass(), pstFileName);
            }
            message = (PSTMessage) folder.getNextChild();
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
        email.setReceivedTime(message.getMessageDeliveryTime());
        email.setBody(message.getBody());
        email.setHtmlContent(message.getBodyHTML());
        email.setRecipients(splitStringToList(message.getDisplayTo()));
        email.setCc(splitStringToList(message.getDisplayCC()));
        email.setBcc(splitStringToList(message.getDisplayBCC()));
    }

    private void saveEmailWithAttachments(Email email, PSTMessage message, String pstFileName, String uniqueEntryId) throws Exception {
        try {
            List<String> attachmentPaths = saveAttachments(message, pstFileName, uniqueEntryId);
            email.setAttachmentPaths(attachmentPaths);
        } catch (Exception e) {
            throw new Exception("Failed to save attachments.", e);
        }
        emailRepository.save(email);
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
            e.printStackTrace();
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

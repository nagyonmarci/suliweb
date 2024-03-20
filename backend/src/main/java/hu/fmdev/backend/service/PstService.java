package hu.fmdev.backend.service;

import com.pff.PSTAttachment;
import com.pff.PSTFile;
import com.pff.PSTFolder;
import com.pff.PSTMessage;
import hu.fmdev.backend.domain.Email;
import hu.fmdev.backend.repository.EmailRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
@Slf4j
public class PstService {

    @Autowired
    private EmailRepository emailRepository;

    private static final Logger logger = LoggerFactory.getLogger(PstService.class);

    public String processPstFileFromUpload(MultipartFile file) {
        try {
            File pstFile = convertMultiPartToFile(file);
            PSTFile pst = new PSTFile(pstFile.getAbsolutePath());
            processFolder(pst.getRootFolder(), pstFile.getName(), "");
            pstFile.delete();
            return "PST file processed successfully";
        } catch (Exception e) {
            e.printStackTrace();
            return "Error processing PST file";
        }
    }

    public void processPstFilesFromTxt(String txtFilePath) {
        try {
            List<String> pstFilePaths = Files.readAllLines(Paths.get(txtFilePath));
            for (String pstFilePath : pstFilePaths) {
                String result = processPstFile(pstFilePath);
                System.out.println(result);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String processPstFile(String filePath) {
        File pstFile = new File(filePath);
        if (!pstFile.exists() || !pstFile.canRead()) {
            return "Error: File does not exist or cannot be read";
        }

        try {
            PSTFile pst = new PSTFile(pstFile.getAbsolutePath());
            processFolder(pst.getRootFolder(), pstFile.getName(), "");
            return "PST file processed successfully";
        } catch (Exception e) {
            e.printStackTrace();
            return "Error processing PST file";
        }
    }

    private File convertMultiPartToFile(MultipartFile file) throws Exception {
        File convFile = new File(file.getOriginalFilename());
        try (OutputStream os = new FileOutputStream(convFile)) {
            InputStream is = file.getInputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
        }
        return convFile;
    }

    private List<String> saveAttachments(PSTMessage message, String pstFileName, String descriptorNodeId) throws Exception {
        List<String> attachmentPaths = new ArrayList<>();
        String attachmentsDirPath = "C:/attachments/" + pstFileName + "/" + descriptorNodeId;
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

            try (OutputStream os = new FileOutputStream(attachmentFile)) {
                byte[] buffer = new byte[8192];
                InputStream in = attachment.getFileInputStream();
                int len;
                while ((len = in.read(buffer)) > 0) {
                    os.write(buffer, 0, len);
                }
            }
            attachmentPaths.add(filePath.toString());
        }
        return attachmentPaths;
    }

    private void processFolder(PSTFolder folder, String pstFileName, String parentFolderPath) throws Exception {
        String currentFolderPath = parentFolderPath.isEmpty() ? folder.getDisplayName() : parentFolderPath + "/" + folder.getDisplayName();
        if (folder.getContentCount() > 0) {
            PSTMessage message = (PSTMessage) folder.getNextChild();
            while (message != null) {
                try {
                    // Itt ellenőrizheted az üzenet típusát
                    if (isSupportedMessageType(message)) {
                        // Feldolgozás, ha az üzenettípus támogatott
                        processMessage(message, pstFileName, currentFolderPath);
                    } else {
                        // Naplózás, ha az üzenettípus nem támogatott
                        logger.warn("Ismeretlen vagy nem támogatott üzenettípus: {} a következőben: {}", message.getMessageClass(), pstFileName);
                    }
                } catch (Exception e) {
                    logger.error("Hiba az üzenet feldolgozása közben", e);
                }

                message = (PSTMessage) folder.getNextChild();
            }
        }
        if (folder.hasSubfolders()) {
            for (PSTFolder subFolder : folder.getSubFolders()) {
                processFolder(subFolder, pstFileName, currentFolderPath);
            }
        }
    }

    private boolean isSupportedMessageType(PSTMessage message) {
        String messageType = message.getMessageClass();
        return messageType.startsWith("IPM.Note");
    }

    private void processMessage(PSTMessage message, String pstFileName, String currentFolderPath) {

        String uniqueEntryId = generateUniqueEntryId(pstFileName, message.getDescriptorNodeId());


        Email email = emailRepository.findByUniqueEntryId(uniqueEntryId)
                .orElse(new Email());
        email.setUniqueEntryId(uniqueEntryId);
        email.setPstFileName(pstFileName);
        email.setFolderPath(currentFolderPath);
        email.setSenderEmailAddress(message.getSenderEmailAddress());
        email.setSenderName(message.getSenderName());
        email.setSubject(message.getSubject());
        email.setReceivedTime(message.getMessageDeliveryTime());
        email.setBody(message.getBody());

        email.setRecipients(Arrays.asList(message.getDisplayTo().split(";")));
        email.setCc(Arrays.asList(message.getDisplayCC().split(";")));
        email.setBcc(Arrays.asList(message.getDisplayBCC().split(";")));

        List<String> attachmentPaths = null;
        try {
            attachmentPaths = saveAttachments(message, pstFileName, uniqueEntryId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        email.setAttachmentPaths(attachmentPaths);
        emailRepository.save(email);
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

}

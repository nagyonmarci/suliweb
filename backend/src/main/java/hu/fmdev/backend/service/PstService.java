package hu.fmdev.backend.service;

import com.pff.PSTFile;
import com.pff.PSTFolder;
import com.pff.PSTMessage;
import hu.fmdev.backend.domain.Email;
import hu.fmdev.backend.repository.EmailRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

@Service
public class PstService {

    @Autowired
    private EmailRepository emailRepository;

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

    private void processFolder(PSTFolder folder, String pstFileName, String parentFolderPath) throws Exception {
        String currentFolderPath = parentFolderPath.isEmpty() ? folder.getDisplayName() : parentFolderPath + "/" + folder.getDisplayName();

        if (folder.getContentCount() > 0) {
            PSTMessage message = (PSTMessage) folder.getNextChild();
            while (message != null) {
                Email email = new Email();
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

                emailRepository.save(email);
                message = (PSTMessage) folder.getNextChild();
            }
        }
        if (folder.hasSubfolders()) {
            for (PSTFolder subFolder : folder.getSubFolders()) {
                processFolder(subFolder, pstFileName, currentFolderPath);
            }
        }
    }

}

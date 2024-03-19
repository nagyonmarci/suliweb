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

@Service
public class PstService {

    @Autowired
    private EmailRepository emailRepository;

    public String processPstFile(MultipartFile file) {
        try {
            // Ideiglenes fájl létrehozása a MultipartFile-ból
            File pstFile = convertMultiPartToFile(file);
            PSTFile pst = new PSTFile(pstFile.getAbsolutePath());
            processFolder(pst.getRootFolder());
            // Ideiglenes fájl törlése
            pstFile.delete();
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

    private void processFolder(PSTFolder folder) throws Exception {
        if (folder.getContentCount() > 0) {
            PSTMessage message = (PSTMessage) folder.getNextChild();
            while (message != null) {
                Email email = new Email();
                email.setSender(message.getSenderEmailAddress());
                email.setSubject(message.getSubject());
                emailRepository.save(email);
                message = (PSTMessage) folder.getNextChild();
            }
        }
        if (folder.hasSubfolders()) {
            for (PSTFolder subFolder : folder.getSubFolders()) {
                processFolder(subFolder);
            }
        }
    }
}

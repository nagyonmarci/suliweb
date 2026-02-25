package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.Email;
import hu.fmdev.backend.repository.EmailRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/emails")
public class EmailController {

    @Autowired
    private EmailRepository emailRepository;

    @Autowired
    private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    @GetMapping
    public List<Email> getAllEmails() {
        return emailRepository.findAll();
    }

    @GetMapping("/search")
    public List<Email> searchEmails(
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String sender,
            @RequestParam(required = false) String recipient,
            @RequestParam(required = false) String pstFile,
            @RequestParam(required = false) String folder,
            @RequestParam(required = false) Integer importance,
            @RequestParam(required = false) Boolean isRead,
            @RequestParam(defaultValue = "receivedTime") String sortBy,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "100") int limit) {

        org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query();

        if (subject != null && !subject.isEmpty()) {
            query.addCriteria(
                    org.springframework.data.mongodb.core.query.Criteria.where("subject").regex(subject, "i"));
        }
        if (sender != null && !sender.isEmpty()) {
            query.addCriteria(new org.springframework.data.mongodb.core.query.Criteria().orOperator(
                    org.springframework.data.mongodb.core.query.Criteria.where("senderName").regex(sender, "i"),
                    org.springframework.data.mongodb.core.query.Criteria.where("senderEmailAddress").regex(sender,
                            "i")));
        }
        if (recipient != null && !recipient.isEmpty()) {
            query.addCriteria(
                    org.springframework.data.mongodb.core.query.Criteria.where("recipients").regex(recipient, "i"));
        }
        if (pstFile != null && !pstFile.isEmpty()) {
            query.addCriteria(
                    org.springframework.data.mongodb.core.query.Criteria.where("pstFileName").regex(pstFile, "i"));
        }
        if (folder != null && !folder.isEmpty()) {
            query.addCriteria(
                    org.springframework.data.mongodb.core.query.Criteria.where("folderPath").regex(folder, "i"));
        }
        if (importance != null) {
            query.addCriteria(org.springframework.data.mongodb.core.query.Criteria.where("importance").is(importance));
        }
        if (isRead != null) {
            query.addCriteria(org.springframework.data.mongodb.core.query.Criteria.where("isRead").is(isRead));
        }

        org.springframework.data.domain.Sort.Direction dir = direction.equalsIgnoreCase("asc")
                ? org.springframework.data.domain.Sort.Direction.ASC
                : org.springframework.data.domain.Sort.Direction.DESC;

        query.with(org.springframework.data.domain.Sort.by(dir, sortBy));
        query.limit(limit);

        return mongoTemplate.find(query, Email.class);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Email> getEmailById(@PathVariable("id") String id) {
        Email email = emailRepository.findById(id).orElse(null);
        if (email != null) {
            return ResponseEntity.ok(email);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public ResponseEntity<Email> saveEmail(@RequestBody Email email) {
        Email savedEmail = emailRepository.save(email);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedEmail);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Email> updateEmail(@PathVariable("id") String id, @RequestBody Email email) {
        if (!emailRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        email.setId(id);
        Email updatedEmail = emailRepository.save(email);
        return ResponseEntity.ok(updatedEmail);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEmail(@PathVariable("id") String id) {
        if (!emailRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        emailRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}

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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EmailController.class);

    @Autowired
    private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    @GetMapping
    public List<Email> getAllEmails() {
        log.info("Lekérdezés: összes e-mail (limit 1000)");
        org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query();
        query.limit(1000);
        query.with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "receivedTime"));
        List<Email> emails = mongoTemplate.find(query, Email.class);
        log.info("E-mailek száma a válaszban: {}", emails.size());
        return emails;
    }

    @GetMapping("/count")
    public long countAllEmails() {
        return mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(), Email.class);
    }

    @GetMapping("/search")
    public List<Email> searchEmails(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String sender,
            @RequestParam(required = false) String recipient,
            @RequestParam(required = false) String pstFile,
            @RequestParam(required = false) String folder,
            @RequestParam(required = false) Integer importance,
            @RequestParam(required = false) Boolean isRead,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime startDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime endDate,
            @RequestParam(defaultValue = "receivedTime") String sortBy,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "100") int limit) {

        org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query();
        java.util.List<org.springframework.data.mongodb.core.query.Criteria> andCriteria = new java.util.ArrayList<>();

        if (q != null && !q.trim().isEmpty()) {
            org.springframework.data.mongodb.core.query.Criteria[] criteriaList = {
                org.springframework.data.mongodb.core.query.Criteria.where("subject").regex(q, "i"),
                org.springframework.data.mongodb.core.query.Criteria.where("senderName").regex(q, "i"),
                org.springframework.data.mongodb.core.query.Criteria.where("senderEmailAddress").regex(q, "i"),
                org.springframework.data.mongodb.core.query.Criteria.where("body").regex(q, "i"),
                org.springframework.data.mongodb.core.query.Criteria.where("htmlContent").regex(q, "i"),
                org.springframework.data.mongodb.core.query.Criteria.where("folderPath").regex(q, "i"),
                org.springframework.data.mongodb.core.query.Criteria.where("attachmentPaths").regex(q, "i")
            };
            andCriteria.add(new org.springframework.data.mongodb.core.query.Criteria().orOperator(criteriaList));
        }

        if (subject != null && !subject.isEmpty()) {
            andCriteria.add(
                    org.springframework.data.mongodb.core.query.Criteria.where("subject").regex(subject, "i"));
        }
        if (sender != null && !sender.isEmpty()) {
            andCriteria.add(new org.springframework.data.mongodb.core.query.Criteria().orOperator(
                    org.springframework.data.mongodb.core.query.Criteria.where("senderName").regex(sender, "i"),
                    org.springframework.data.mongodb.core.query.Criteria.where("senderEmailAddress").regex(sender, "i")));
        }
        if (recipient != null && !recipient.isEmpty()) {
            andCriteria.add(
                    org.springframework.data.mongodb.core.query.Criteria.where("recipients").regex(recipient, "i"));
        }
        if (pstFile != null && !pstFile.isEmpty()) {
            andCriteria.add(
                    org.springframework.data.mongodb.core.query.Criteria.where("pstFileName").regex(pstFile, "i"));
        }
        if (folder != null && !folder.isEmpty()) {
            andCriteria.add(
                    org.springframework.data.mongodb.core.query.Criteria.where("folderPath").regex(folder, "i"));
        }
        if (importance != null) {
            andCriteria.add(org.springframework.data.mongodb.core.query.Criteria.where("importance").is(importance));
        }
        if (isRead != null) {
            andCriteria.add(org.springframework.data.mongodb.core.query.Criteria.where("isRead").is(isRead));
        }
        
        if (startDate != null || endDate != null) {
            org.springframework.data.mongodb.core.query.Criteria dateCriteria = org.springframework.data.mongodb.core.query.Criteria.where("receivedTime");
            if (startDate != null) {
                dateCriteria.gte(startDate);
            }
            if (endDate != null) {
                dateCriteria.lte(endDate);
            }
            andCriteria.add(dateCriteria);
        }

        if (!andCriteria.isEmpty()) {
            query.addCriteria(new org.springframework.data.mongodb.core.query.Criteria().andOperator(andCriteria.toArray(new org.springframework.data.mongodb.core.query.Criteria[0])));
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

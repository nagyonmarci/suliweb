package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.Attachment;
import hu.fmdev.backend.repository.AttachmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentRepository attachmentRepository;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    @Value("${attachments.directory:/app/attachments}")
    private String attachmentsDirectory;

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "creationTime", "filename", "size", "emailSubject", "senderName", "pstFileName");

    private static String escapeRegex(String input) {
        return Pattern.quote(input);
    }

    @GetMapping("/search")
    public List<Attachment> searchAttachments(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String q,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String filename,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String extension,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Long minSize,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Long maxSize,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String emailSubject,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String sender,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String pstFile,
            @org.springframework.web.bind.annotation.RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime startDate,
            @org.springframework.web.bind.annotation.RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime endDate,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "creationTime") String sortBy,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "desc") String direction,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1000") int limit) {

        org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query();
        java.util.List<org.springframework.data.mongodb.core.query.Criteria> andCriteria = new java.util.ArrayList<>();

        if (q != null && !q.trim().isEmpty()) {
            String escapedQ = escapeRegex(q.trim());
            org.springframework.data.mongodb.core.query.Criteria[] criteriaList = {
                org.springframework.data.mongodb.core.query.Criteria.where("filename").regex(escapedQ, "i"),
                org.springframework.data.mongodb.core.query.Criteria.where("emailSubject").regex(escapedQ, "i"),
                org.springframework.data.mongodb.core.query.Criteria.where("senderName").regex(escapedQ, "i"),
                org.springframework.data.mongodb.core.query.Criteria.where("pstFileName").regex(escapedQ, "i")
            };
            andCriteria.add(new org.springframework.data.mongodb.core.query.Criteria().orOperator(criteriaList));
        }

        if (filename != null && !filename.isEmpty()) {
            andCriteria.add(org.springframework.data.mongodb.core.query.Criteria.where("filename").regex(escapeRegex(filename), "i"));
        }
        if (extension != null && !extension.isEmpty()) {
            // matches end of filename .ext - escape only the extension part
            andCriteria.add(org.springframework.data.mongodb.core.query.Criteria.where("filename").regex("\\." + Pattern.quote(extension) + "$", "i"));
        }
        if (minSize != null || maxSize != null) {
            org.springframework.data.mongodb.core.query.Criteria sizeCriteria = org.springframework.data.mongodb.core.query.Criteria.where("size");
            if (minSize != null) sizeCriteria.gte(minSize);
            if (maxSize != null) sizeCriteria.lte(maxSize);
            andCriteria.add(sizeCriteria);
        }
        if (emailSubject != null && !emailSubject.isEmpty()) {
            andCriteria.add(org.springframework.data.mongodb.core.query.Criteria.where("emailSubject").regex(escapeRegex(emailSubject), "i"));
        }
        if (sender != null && !sender.isEmpty()) {
            andCriteria.add(org.springframework.data.mongodb.core.query.Criteria.where("senderName").regex(escapeRegex(sender), "i"));
        }
        if (pstFile != null && !pstFile.isEmpty()) {
            andCriteria.add(org.springframework.data.mongodb.core.query.Criteria.where("pstFileName").regex(escapeRegex(pstFile), "i"));
        }
        if (startDate != null || endDate != null) {
            org.springframework.data.mongodb.core.query.Criteria dateCriteria = org.springframework.data.mongodb.core.query.Criteria.where("receivedTime");
            if (startDate != null) dateCriteria.gte(startDate);
            if (endDate != null) dateCriteria.lte(endDate);
            andCriteria.add(dateCriteria);
        }

        if (!andCriteria.isEmpty()) {
            query.addCriteria(new org.springframework.data.mongodb.core.query.Criteria().andOperator(andCriteria.toArray(new org.springframework.data.mongodb.core.query.Criteria[0])));
        }

        org.springframework.data.domain.Sort.Direction dir = direction.equalsIgnoreCase("asc")
                ? org.springframework.data.domain.Sort.Direction.ASC
                : org.springframework.data.domain.Sort.Direction.DESC;

        String validatedSortBy = ALLOWED_SORT_FIELDS.contains(sortBy) ? sortBy : "creationTime";
        query.with(org.springframework.data.domain.Sort.by(dir, validatedSortBy));
        query.limit(limit);

        return mongoTemplate.find(query, Attachment.class);
    }

    @GetMapping
    public List<Attachment> getAllAttachments() {
        return attachmentRepository.findAll(PageRequest.of(0, 1000, Sort.by(Sort.Direction.DESC, "creationTime"))).getContent();
    }

    @GetMapping("/count")
    public long getAttachmentCount() {
        return attachmentRepository.count();
    }

    @GetMapping("/email/{emailId}")
    public List<Attachment> getAttachmentsByEmailId(@PathVariable String emailId) {
        return attachmentRepository.findByEmailId(emailId);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> downloadAttachment(@PathVariable String id) {
        Optional<Attachment> attachmentOpt = attachmentRepository.findById(id);
        if (attachmentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Attachment attachment = attachmentOpt.get();
        Path path = Paths.get(attachment.getLocalPath());

        try {
            Path baseDir = Paths.get(attachmentsDirectory).toRealPath();
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(baseDir)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        if (!Files.exists(path)) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        try {
            byte[] content = Files.readAllBytes(path);
            String contentType = attachment.getContentType();
            if (contentType == null) {
                contentType = "application/octet-stream";
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + attachment.getFilename() + "\"")
                    .body(content);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}

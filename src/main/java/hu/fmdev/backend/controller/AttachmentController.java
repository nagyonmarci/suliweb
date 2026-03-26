package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.Attachment;
import hu.fmdev.backend.repository.AttachmentRepository;
import hu.fmdev.backend.service.FileAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentRepository attachmentRepository;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    private final FileAccessService fileAccessService;

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
            org.springframework.data.mongodb.core.query.Criteria[] criteriaList = {
                org.springframework.data.mongodb.core.query.Criteria.where("filename").regex(q, "i"),
                org.springframework.data.mongodb.core.query.Criteria.where("emailSubject").regex(q, "i"),
                org.springframework.data.mongodb.core.query.Criteria.where("senderName").regex(q, "i"),
                org.springframework.data.mongodb.core.query.Criteria.where("pstFileName").regex(q, "i")
            };
            andCriteria.add(new org.springframework.data.mongodb.core.query.Criteria().orOperator(criteriaList));
        }

        if (filename != null && !filename.isEmpty()) {
            andCriteria.add(org.springframework.data.mongodb.core.query.Criteria.where("filename").regex(filename, "i"));
        }
        if (extension != null && !extension.isEmpty()) {
            andCriteria.add(org.springframework.data.mongodb.core.query.Criteria.where("filename").regex("\\." + extension + "$", "i"));
        }
        if (minSize != null || maxSize != null) {
            org.springframework.data.mongodb.core.query.Criteria sizeCriteria = org.springframework.data.mongodb.core.query.Criteria.where("size");
            if (minSize != null) sizeCriteria.gte(minSize);
            if (maxSize != null) sizeCriteria.lte(maxSize);
            andCriteria.add(sizeCriteria);
        }
        if (emailSubject != null && !emailSubject.isEmpty()) {
            andCriteria.add(org.springframework.data.mongodb.core.query.Criteria.where("emailSubject").regex(emailSubject, "i"));
        }
        if (sender != null && !sender.isEmpty()) {
            andCriteria.add(org.springframework.data.mongodb.core.query.Criteria.where("senderName").regex(sender, "i"));
        }
        if (pstFile != null && !pstFile.isEmpty()) {
            andCriteria.add(org.springframework.data.mongodb.core.query.Criteria.where("pstFileName").regex(pstFile, "i"));
        }
        if (startDate != null || endDate != null) {
            org.springframework.data.mongodb.core.query.Criteria dateCriteria = org.springframework.data.mongodb.core.query.Criteria.where("receivedTime");
            if (startDate != null) dateCriteria.gte(startDate);
            if (endDate != null) dateCriteria.lte(endDate);
            andCriteria.add(dateCriteria);
        }

        // PST file access restriction
        Set<String> allowedPstNames = fileAccessService.getAllowedPstFileNames();
        if (allowedPstNames != null) {
            andCriteria.add(org.springframework.data.mongodb.core.query.Criteria.where("pstFileName").in(allowedPstNames));
        }

        if (!andCriteria.isEmpty()) {
            query.addCriteria(new org.springframework.data.mongodb.core.query.Criteria().andOperator(andCriteria.toArray(new org.springframework.data.mongodb.core.query.Criteria[0])));
        }

        org.springframework.data.domain.Sort.Direction dir = direction.equalsIgnoreCase("asc")
                ? org.springframework.data.domain.Sort.Direction.ASC
                : org.springframework.data.domain.Sort.Direction.DESC;

        query.with(org.springframework.data.domain.Sort.by(dir, sortBy));
        query.limit(limit);

        return mongoTemplate.find(query, Attachment.class);
    }

    @GetMapping
    public List<Attachment> getAllAttachments() {
        Set<String> allowedPstNames = fileAccessService.getAllowedPstFileNames();
        if (allowedPstNames == null) {
            return attachmentRepository.findAll(
                    org.springframework.data.domain.PageRequest.of(0, 1000, Sort.by(Sort.Direction.DESC, "creationTime"))
            ).getContent();
        }
        org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("pstFileName").in(allowedPstNames)
        );
        query.with(Sort.by(Sort.Direction.DESC, "creationTime")).limit(1000);
        return mongoTemplate.find(query, Attachment.class);
    }

    @GetMapping("/count")
    public long getAttachmentCount() {
        Set<String> allowedPstNames = fileAccessService.getAllowedPstFileNames();
        if (allowedPstNames == null) {
            return attachmentRepository.count();
        }
        org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("pstFileName").in(allowedPstNames)
        );
        return mongoTemplate.count(query, Attachment.class);
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

        Set<String> allowedPstNames = fileAccessService.getAllowedPstFileNames();
        if (allowedPstNames != null && !allowedPstNames.contains(attachment.getPstFileName())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Path path = Paths.get(attachment.getLocalPath());
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

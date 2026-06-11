package hu.fmdev.backend.service;

import hu.fmdev.backend.domain.Attachment;
import hu.fmdev.backend.domain.Email;
import hu.fmdev.backend.domain.graph.*;
import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.repository.AttachmentRepository;
import hu.fmdev.backend.repository.EmailRepository;
import hu.fmdev.backend.repository.graph.*;
import hu.fmdev.backend.service.rag.EntityExtractionService;
import hu.fmdev.backend.service.rag.TextExtractionService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Service
public class KnowledgeGraphIngestionService {

    private static final int PAGE_SIZE = 50;

    private final EmailRepository emailRepository;
    private final AttachmentRepository attachmentRepository;
    private final PersonNodeRepository personRepo;
    private final EmailNodeRepository emailNodeRepo;
    private final ThreadNodeRepository threadRepo;
    private final ConceptNodeRepository conceptRepo;
    private final EntityExtractionService entityExtraction;
    private final TextExtractionService textExtraction;
    private final ProgressTracker progressTracker;

    private volatile boolean running = false;
    private final AtomicLong totalEmails  = new AtomicLong();
    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong failedCount  = new AtomicLong();

    public KnowledgeGraphIngestionService(EmailRepository emailRepository,
                                          AttachmentRepository attachmentRepository,
                                          PersonNodeRepository personRepo,
                                          EmailNodeRepository emailNodeRepo,
                                          ThreadNodeRepository threadRepo,
                                          ConceptNodeRepository conceptRepo,
                                          EntityExtractionService entityExtraction,
                                          TextExtractionService textExtraction,
                                          ProgressTracker progressTracker) {
        this.emailRepository   = emailRepository;
        this.attachmentRepository = attachmentRepository;
        this.personRepo        = personRepo;
        this.emailNodeRepo     = emailNodeRepo;
        this.threadRepo        = threadRepo;
        this.conceptRepo       = conceptRepo;
        this.entityExtraction  = entityExtraction;
        this.textExtraction    = textExtraction;
        this.progressTracker   = progressTracker;
    }

    public boolean isRunning() { return running; }

    public KgStats getStats() {
        return new KgStats(totalEmails.get(), processedCount.get(), failedCount.get());
    }

    public void ingestAll() {
        if (running) {
            CentralLogger.logWarn("KG ingestion already running");
            return;
        }
        running = true;
        totalEmails.set(0); processedCount.set(0); failedCount.set(0);
        try {
            long total = emailRepository.count();
            totalEmails.set(total);
            progressTracker.startOperation("Knowledge Graph építés", (int) total);
            CentralLogger.logInfo("KG ingestion start. Emails: " + total);

            int page = 0;
            ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
            try {
                while (true) {
                    var emailPage = emailRepository.findAll(PageRequest.of(page, PAGE_SIZE));
                    if (emailPage.isEmpty()) break;

                    List<Callable<Void>> tasks = emailPage.stream()
                            .map(email -> (Callable<Void>) () -> {
                                processEmail(email);
                                return null;
                            })
                            .toList();
                    try {
                        exec.invokeAll(tasks);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        CentralLogger.logError("KG ingestion megszakítva", e);
                        break;
                    }

                    if (!emailPage.hasNext()) break;
                    page++;
                }
            } finally {
                exec.shutdown();
            }

            progressTracker.stopOperation();
            CentralLogger.logInfo("KG ingestion done. Processed: " + processedCount
                    + " Failed: " + failedCount);
        } finally {
            running = false;
        }
    }

    private static boolean isDeadlock(Exception e) {
        String msg = e.getMessage();
        return msg != null && msg.contains("DeadlockDetected");
    }

    private void processEmail(Email email) {
        progressTracker.increment();
        Exception lastEx = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                doProcessEmail(email);
                return;
            } catch (Exception e) {
                lastEx = e;
                if (!isDeadlock(e)) break;
                try { Thread.sleep(100L * (attempt + 1)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        failedCount.incrementAndGet();
        CentralLogger.logWarn("KG processEmail hiba [" + email.getId() + "]: "
                + (lastEx != null ? lastEx.getMessage() : "unknown"));
    }

    private void doProcessEmail(Email email) {
        // Skip if already in graph
        if (emailNodeRepo.existsByMessageId(resolveMessageId(email))) return;

            // 1. Person nodes
            PersonNode sender = mergePerson(email.getSenderEmailAddress(), email.getSenderName());

            // 2. Thread node
            ThreadNode thread = null;
            if (email.getConversationId() != null && !email.getConversationId().isBlank()) {
                thread = mergeThread(email.getConversationId(), email.getConversationTopic(),
                        email.getReceivedTime() != null ? email.getReceivedTime().toString() : "");
            }

            // 3. Email node
            EmailNode emailNode = new EmailNode();
            emailNode.setMessageId(resolveMessageId(email));
            emailNode.setMongoId(email.getId());
            emailNode.setSubject(email.getSubject());
            emailNode.setDate(email.getReceivedTime() != null ? email.getReceivedTime().toString() : "");
            emailNode.setPstFileName(email.getPstFileName());
            emailNode.setPstOwner(pstOwner(email.getPstFileName()));
            emailNode.setSender(sender);
            emailNode.setThread(thread);

            // TO recipients
            if (email.getRecipients() != null) {
                List<PersonNode> toList = email.getRecipients().stream()
                        .filter(r -> r != null && !r.isBlank())
                        .map(r -> mergePerson(r, null))
                        .toList();
                emailNode.setToRecipients(new ArrayList<>(toList));
            }

            // CC recipients
            if (email.getCc() != null) {
                List<PersonNode> ccList = email.getCc().stream()
                        .filter(r -> r != null && !r.isBlank())
                        .map(r -> mergePerson(r, null))
                        .toList();
                emailNode.setCcRecipients(new ArrayList<>(ccList));
            }

            // 4. Attachments
            List<Attachment> atts = attachmentRepository.findByEmailId(email.getId());
            List<AttachmentNode> attNodes = atts.stream()
                    .filter(a -> a.getHash() != null && !a.getHash().isBlank())
                    .map(a -> {
                        AttachmentNode n = new AttachmentNode();
                        n.setSha256(a.getHash());
                        n.setFilename(a.getFilename());
                        n.setMarkdownContent("");
                        return n;
                    }).toList();
            emailNode.setAttachments(new ArrayList<>(attNodes));

            // 5. Concept extraction (NER via Ollama)
            String bodyText = textExtraction.getEmailTextContent(email.getBody(), email.getHtmlContent());
            List<EntityExtractionService.ExtractedEntity> entities = entityExtraction.extract(bodyText);
            List<ConceptNode> concepts = entities.stream()
                    .map(e -> mergeConcept(e.name(), e.type()))
                    .toList();
            emailNode.setMentions(new ArrayList<>(concepts));

            // 6. Save email node
            EmailNode saved = emailNodeRepo.save(emailNode);

            // 7. REPLY_TO link (best-effort — requires the parent to already be in Neo4j)
            if (email.getConversationId() != null && !email.getConversationId().isBlank()) {
                emailNodeRepo.findByThreadId(email.getConversationId()).stream()
                        .filter(n -> !n.getMongoId().equals(email.getId()))
                        .findFirst()
                        .ifPresent(parent -> {
                            saved.setReplyTo(parent);
                            emailNodeRepo.save(saved);
                        });
            }

            // 8. COMMUNICATES_WITH counter update
            if (sender != null && email.getRecipients() != null) {
                for (String recipientEmail : email.getRecipients()) {
                    if (recipientEmail == null || recipientEmail.isBlank()) continue;
                    updateCommunicatesWith(sender, recipientEmail,
                            email.getReceivedTime() != null ? email.getReceivedTime().toString() : "");
                }
            }

            processedCount.incrementAndGet();
    }

    // -------------------------------------------------------------------------
    // Merge helpers (MERGE semantics: get-or-create)
    // -------------------------------------------------------------------------

    private synchronized PersonNode mergePerson(String email, String name) {
        if (email == null || email.isBlank()) return null;
        return personRepo.findByEmail(email.toLowerCase().trim()).orElseGet(() -> {
            PersonNode p = new PersonNode();
            p.setEmail(email.toLowerCase().trim());
            p.setName(name != null ? name : "");
            p.setOrganization(domainFromEmail(email));
            return personRepo.save(p);
        });
    }

    private synchronized ThreadNode mergeThread(String threadId, String subject, String date) {
        return threadRepo.findByThreadId(threadId).orElseGet(() -> {
            ThreadNode t = new ThreadNode();
            t.setThreadId(threadId);
            t.setSubject(subject != null ? subject : "");
            t.setLastActivity(date);
            return threadRepo.save(t);
        });
    }

    private synchronized ConceptNode mergeConcept(String name, String type) {
        return conceptRepo.findByNameIgnoreCase(name).orElseGet(() -> {
            ConceptNode c = new ConceptNode();
            c.setName(name);
            c.setType(type);
            return conceptRepo.save(c);
        });
    }

    private void updateCommunicatesWith(PersonNode sender, String recipientEmail, String date) {
        personRepo.findByEmail(recipientEmail.toLowerCase().trim()).ifPresent(recipient -> {
            List<CommunicatesWithRel> rels = sender.getCommunicatesWith();
            if (rels == null) rels = new ArrayList<>();
            boolean found = false;
            for (CommunicatesWithRel rel : rels) {
                if (rel.getTarget() != null && recipientEmail.equalsIgnoreCase(rel.getTarget().getEmail())) {
                    rel.setCount(rel.getCount() + 1);
                    rel.setLastDate(date);
                    found = true;
                    break;
                }
            }
            if (!found) {
                CommunicatesWithRel rel = new CommunicatesWithRel();
                rel.setTarget(recipient);
                rel.setCount(1);
                rel.setLastDate(date);
                rels.add(rel);
            }
            sender.setCommunicatesWith(rels);
            personRepo.save(sender);
        });
    }

    // -------------------------------------------------------------------------

    private String resolveMessageId(Email email) {
        String mid = email.getInternetMessageId();
        if (mid != null && !mid.isBlank()) {
            return mid.trim().toLowerCase().replaceAll("[<>\\s]", "");
        }
        return email.getUniqueEntryId() != null ? email.getUniqueEntryId() : email.getId();
    }

    private String pstOwner(String pstFileName) {
        if (pstFileName == null) return "";
        int dot = pstFileName.lastIndexOf('.');
        return dot > 0 ? pstFileName.substring(0, dot) : pstFileName;
    }

    private String domainFromEmail(String email) {
        int at = email.indexOf('@');
        return at >= 0 ? email.substring(at + 1) : "";
    }

    public record KgStats(long totalEmails, long processed, long failed) {}
}

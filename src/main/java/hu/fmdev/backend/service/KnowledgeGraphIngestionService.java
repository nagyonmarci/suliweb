package hu.fmdev.backend.service;

import hu.fmdev.backend.domain.Attachment;
import hu.fmdev.backend.domain.Email;
import hu.fmdev.backend.domain.graph.*;
import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.repository.AttachmentRepository;
import hu.fmdev.backend.repository.EmailRepository;
import hu.fmdev.backend.repository.graph.*;
import hu.fmdev.backend.service.rag.K1ExtractionOutput;
import hu.fmdev.backend.service.rag.NerExtractor;
import hu.fmdev.backend.service.rag.TextExtractionService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.function.Function;

@Service
public class KnowledgeGraphIngestionService {

    private final EmailRepository emailRepository;
    private final AttachmentRepository attachmentRepository;
    private final PersonNodeRepository personRepo;
    private final EmailNodeRepository emailNodeRepo;
    private final ThreadNodeRepository threadRepo;
    private final ConceptNodeRepository conceptRepo;
    private final ClaimNodeRepository claimRepo;
    private final EvidenceNodeRepository evidenceRepo;
    private final MechanismNodeRepository mechanismRepo;
    private final NerExtractor entityExtraction;
    private final TextExtractionService textExtraction;
    private final ProgressTracker progressTracker;
    private final AppSettingsService appSettingsService;

    private volatile boolean running = false;
    private volatile Instant startedAt = null;
    private final AtomicLong totalEmails    = new AtomicLong();
    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong failedCount    = new AtomicLong();

    public KnowledgeGraphIngestionService(EmailRepository emailRepository,
                                          AttachmentRepository attachmentRepository,
                                          PersonNodeRepository personRepo,
                                          EmailNodeRepository emailNodeRepo,
                                          ThreadNodeRepository threadRepo,
                                          ConceptNodeRepository conceptRepo,
                                          ClaimNodeRepository claimRepo,
                                          EvidenceNodeRepository evidenceRepo,
                                          MechanismNodeRepository mechanismRepo,
                                          NerExtractor entityExtraction,
                                          TextExtractionService textExtraction,
                                          ProgressTracker progressTracker,
                                          AppSettingsService appSettingsService) {
        this.emailRepository    = emailRepository;
        this.attachmentRepository = attachmentRepository;
        this.personRepo         = personRepo;
        this.emailNodeRepo      = emailNodeRepo;
        this.threadRepo         = threadRepo;
        this.conceptRepo        = conceptRepo;
        this.claimRepo          = claimRepo;
        this.evidenceRepo       = evidenceRepo;
        this.mechanismRepo      = mechanismRepo;
        this.entityExtraction   = entityExtraction;
        this.textExtraction     = textExtraction;
        this.progressTracker    = progressTracker;
        this.appSettingsService = appSettingsService;
    }

    private record K1Result(Email email, K1ExtractionOutput k1Output) {}

    public boolean isRunning() { return running; }

    public KgStats getStats() {
        long total = totalEmails.get();
        long done  = processedCount.get();
        long failed = failedCount.get();
        double ratePerMin = 0;
        Long etaSeconds = null;
        Instant start = startedAt;
        if (start != null && done > 0) {
            double elapsedMin = Duration.between(start, Instant.now()).toSeconds() / 60.0;
            if (elapsedMin > 0) {
                ratePerMin = done / elapsedMin;
                if (running && ratePerMin > 0) {
                    etaSeconds = (long) ((total - done) / ratePerMin * 60);
                }
            }
        }
        return new KgStats(total, done, failed, ratePerMin, etaSeconds);
    }

    public void ingestAll() {
        if (running) {
            CentralLogger.logWarn("KG ingestion already running");
            return;
        }
        running = true;
        startedAt = Instant.now();
        totalEmails.set(0); processedCount.set(0); failedCount.set(0);
        try {
            CentralLogger.logInfo("KG ingestion start. batchSize=" + appSettingsService.getEffectiveKgBatchSize()
                    + " maxConcurrentWrites=" + appSettingsService.getEffectiveKgMaxConcurrentWrites());

            runBatchedPipeline("Knowledge Graph építés",
                    this::extractK1Result,
                    r -> writeEmailToGraph(r.email(), r.k1Output()));

            progressTracker.stopOperation();
            CentralLogger.logInfo("KG ingestion done. Processed: " + processedCount
                    + " Failed: " + failedCount);
        } finally {
            running = false;
        }
    }

    public void ingestConceptsOnly() {
        if (running) {
            CentralLogger.logWarn("KG concept re-ingestion skipped: already running");
            return;
        }
        running = true;
        startedAt = Instant.now();
        totalEmails.set(0); processedCount.set(0); failedCount.set(0);
        try {
            CentralLogger.logInfo("KG concept re-ingestion start.");

            runBatchedPipeline("Koncepciók újraépítése",
                    this::extractK1ForExistingNode,
                    this::writeConceptsForEmail);

            progressTracker.stopOperation();
            CentralLogger.logInfo("KG concept re-ingest done. Processed: "
                    + processedCount + " Failed: " + failedCount);
        } finally {
            running = false;
        }
    }

    /**
     * Shared two-phase pipeline: pages through every email, runs {@code nerStep} on virtual
     * threads (I/O-bound, unbounded concurrency), then runs {@code writeStep} on a fixed-size
     * pool (bounds concurrent Neo4j writers). A null result from {@code nerStep} skips that email.
     */
    private <R> void runBatchedPipeline(String operationLabel, Function<Email, R> nerStep, Consumer<R> writeStep) {
        long total = emailRepository.count();
        totalEmails.set(total);
        progressTracker.startOperation(operationLabel, (int) total);

        int batchSize = appSettingsService.getEffectiveKgBatchSize();
        ExecutorService nerExec   = Executors.newVirtualThreadPerTaskExecutor();
        ExecutorService writeExec = Executors.newFixedThreadPool(appSettingsService.getEffectiveKgMaxConcurrentWrites());
        try {
            int page = 0;
            while (true) {
                var emailPage = emailRepository.findAll(PageRequest.of(page, batchSize));
                if (emailPage.isEmpty()) break;

                List<Callable<R>> nerTasks = emailPage.stream()
                        .map(email -> (Callable<R>) () -> nerStep.apply(email))
                        .toList();

                List<R> nerResults = new ArrayList<>();
                try {
                    for (var f : nerExec.invokeAll(nerTasks)) {
                        try {
                            R r = f.get();
                            if (r != null) nerResults.add(r);
                        } catch (ExecutionException e) {
                            CentralLogger.logError("K1 extraction hiba", e.getCause());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    CentralLogger.logError("KG ingestion megszakítva (NER fázis)", e);
                    break;
                }

                List<Callable<Void>> writeTasks = nerResults.stream()
                        .map(r -> (Callable<Void>) () -> { writeStep.accept(r); return null; })
                        .toList();

                try {
                    writeExec.invokeAll(writeTasks);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    CentralLogger.logError("KG ingestion megszakítva (write fázis)", e);
                    break;
                }

                if (!emailPage.hasNext()) break;
                page++;
            }
        } finally {
            nerExec.shutdown();
            writeExec.shutdown();
        }
    }

    private K1Result extractK1Result(Email email) {
        progressTracker.increment();
        if (emailNodeRepo.existsByMessageId(resolveMessageId(email))) return null;
        String bodyText = email.getStrippedBody() != null
                ? email.getStrippedBody()
                : textExtraction.getEmailTextContent(email.getBody(), email.getHtmlContent());
        K1ExtractionOutput k1Output = entityExtraction.extractK1(bodyText);
        return new K1Result(email, k1Output);
    }

    private K1Result extractK1ForExistingNode(Email email) {
        progressTracker.increment();
        if (!emailNodeRepo.existsByMessageId(resolveMessageId(email))) return null;
        String text = email.getStrippedBody() != null
                ? email.getStrippedBody()
                : textExtraction.getEmailTextContent(email.getBody(), email.getHtmlContent());
        K1ExtractionOutput k1Output = entityExtraction.extractK1(text);
        return k1Output.entities().isEmpty() ? null : new K1Result(email, k1Output);
    }

    private void writeConceptsForEmail(K1Result r) {
        List<Map<String, String>> conceptMaps = r.k1Output().entities().stream()
                .map(e -> Map.of("name", e.name(), "type", e.type()))
                .toList();
        try {
            writeWithRetry(r.email().getId(), conceptMaps);
            processedCount.incrementAndGet();
        } catch (Exception e) {
            failedCount.incrementAndGet();
            CentralLogger.logWarn("Concept write hiba [" + r.email().getId() + "]: " + e.getMessage());
        }
    }

    private void writeEmailToGraph(Email email, K1ExtractionOutput k1Output) {
        try {
            doWriteEmail(email, k1Output);
        } catch (Exception e) {
            failedCount.incrementAndGet();
            CentralLogger.logWarn("KG writeEmailToGraph hiba [" + email.getId() + "]: " + e.getMessage());
        }
    }

    private void doWriteEmail(Email email, K1ExtractionOutput k1Output) {
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

        if (email.getRecipients() != null) {
            List<PersonNode> toList = email.getRecipients().stream()
                    .filter(r -> r != null && !r.isBlank())
                    .map(r -> mergePerson(r, null))
                    .toList();
            emailNode.setToRecipients(new ArrayList<>(toList));
        }

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

        // 5. Concepts (ConceptNodes) from K1 entity extraction
        List<ConceptNode> concepts = k1Output.entities().stream()
                .map(e -> mergeConcept(e.name(), e.type()))
                .toList();
        emailNode.setMentions(new ArrayList<>(concepts));

        // 5b. K1 Claims, Evidence, Mechanisms → PROVES relationships on EmailNode
        if (!k1Output.claims().isEmpty()) {
            List<EvidenceNode> savedEvidence = k1Output.evidence().stream()
                    .map(evidenceRepo::save)
                    .toList();
            List<MechanismNode> savedMechs = k1Output.mechanisms().stream()
                    .map(mechanismRepo::save)
                    .toList();

            List<ClaimNode> savedClaims = k1Output.claims().stream()
                    .map(claim -> {
                        claim.setEvidence(new ArrayList<>(savedEvidence));
                        claim.setMechanisms(new ArrayList<>(savedMechs));
                        return claimRepo.save(claim);
                    })
                    .toList();

            emailNode.setProvedClaims(new ArrayList<>(savedClaims));
        }

        // 6. Save email node (creates MENTIONS + PROVES relationships)
        EmailNode saved = emailNodeRepo.save(emailNode);

        // 7. REPLY_TO link (best-effort)
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

    private void writeWithRetry(String mongoId, List<Map<String, String>> concepts) throws Exception {
        // Step 1: ensure concept nodes exist — may deadlock if two threads merge the same
        // concept simultaneously, so we retry with jitter
        int maxRetries = 5;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                emailNodeRepo.ensureConcepts(concepts);
                break;
            } catch (Exception e) {
                String msg = e.getMessage();
                boolean deadlock = msg != null && (msg.contains("DeadlockDetected") || msg.contains("can't acquire"));
                if (deadlock && attempt < maxRetries) {
                    long jitter = ThreadLocalRandom.current().nextLong(50);
                    Thread.sleep(50L * (1L << attempt) + jitter);
                } else {
                    throw e;
                }
            }
        }
        // Step 2: link email to existing concepts — pure MATCH, no lock upgrade, no deadlock
        List<String> names = concepts.stream().map(c -> c.get("name")).toList();
        emailNodeRepo.linkEmailToConcepts(mongoId, names);
    }

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

    public record KgStats(long totalEmails, long processed, long failed, double ratePerMin, Long etaSeconds) {}
}

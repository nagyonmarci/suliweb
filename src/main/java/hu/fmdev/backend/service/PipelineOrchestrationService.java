package hu.fmdev.backend.service;

import hu.fmdev.backend.domain.PipelineStatus;
import hu.fmdev.backend.domain.StageProgress;
import hu.fmdev.backend.domain.StageState;
import hu.fmdev.backend.dto.PipelineStartRequest;
import hu.fmdev.backend.logger.CentralLogger;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class PipelineOrchestrationService {

    private static final String STAGE_DISCOVERY  = "pst-discovery";
    private static final String STAGE_PST_READ   = "pst-read";
    private static final String STAGE_ES_INDEX   = "es-index";
    private static final String STAGE_KG_INGEST  = "kg-ingest";

    private final PstFinderService pstFinderService;
    private final PstProcessorService pstProcessorService;
    private final EDiscoveryIngestionService eDiscoveryIngestionService;
    private final KnowledgeGraphIngestionService kgIngestionService;
    private final ProgressTracker progressTracker;

    private volatile boolean pipelineRunning = false;
    private volatile int currentStageIdx = -1;
    private final AtomicReference<List<StageProgress>> stages =
            new AtomicReference<>(List.of());

    public PipelineOrchestrationService(PstFinderService pstFinderService,
                                        PstProcessorService pstProcessorService,
                                        EDiscoveryIngestionService eDiscoveryIngestionService,
                                        KnowledgeGraphIngestionService kgIngestionService,
                                        ProgressTracker progressTracker) {
        this.pstFinderService = pstFinderService;
        this.pstProcessorService = pstProcessorService;
        this.eDiscoveryIngestionService = eDiscoveryIngestionService;
        this.kgIngestionService = kgIngestionService;
        this.progressTracker = progressTracker;
    }

    public synchronized boolean start(PipelineStartRequest req) {
        if (pipelineRunning) return false;
        pipelineRunning = true;
        currentStageIdx = -1;
        stages.set(buildInitialStages(req));
        Thread.ofVirtual().start(() -> runPipeline(req));
        return true;
    }

    public PipelineStatus getStatus() {
        List<StageProgress> current = new ArrayList<>(stages.get());
        int idx = currentStageIdx;
        if (idx >= 0 && idx < current.size()) {
            current.set(idx, buildLiveProgress(idx, current.get(idx)));
        }
        return new PipelineStatus(pipelineRunning, current);
    }

    private void runPipeline(PipelineStartRequest req) {
        try {
            runStage(0, req.skipPstDiscovery(), () -> {
                List<String> dirs = req.directories() != null ? req.directories() : List.of();
                List<String> excl = req.excludedDirectories() != null ? req.excludedDirectories() : List.of();
                try {
                    pstFinderService.findAndSaveFiles(dirs, excl);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            runStage(1, req.skipPstProcessing(), () ->
                    pstProcessorService.processPstFilesFromDb(req.saveAttachments()));

            runStage(2, req.skipEsIndexing(), () ->
                    eDiscoveryIngestionService.ingestAll());

            runStage(3, req.skipKgIngestion(), () ->
                    kgIngestionService.ingestAll());

        } catch (Exception e) {
            CentralLogger.logWarn("Pipeline hiba: " + e.getMessage());
        } finally {
            pipelineRunning = false;
            currentStageIdx = -1;
        }
    }

    private void runStage(int idx, boolean skip, Runnable action) {
        List<StageProgress> current = stages.get();
        if (idx >= current.size()) return;

        if (skip) {
            setStage(idx, current.get(idx).withState(StageState.SKIPPED));
            return;
        }

        currentStageIdx = idx;
        setStage(idx, current.get(idx).withState(StageState.RUNNING));
        try {
            action.run();
            // Snapshot final progress from ProgressTracker before next stage resets it
            StageProgress done = snapshotDone(idx, stages.get().get(idx));
            setStage(idx, done);
        } catch (Exception e) {
            CentralLogger.logWarn("Pipeline stage " + idx + " sikertelen: " + e.getMessage());
            setStage(idx, stages.get().get(idx).withState(StageState.FAILED));
            currentStageIdx = -1;
            throw e;
        }
        currentStageIdx = -1;
    }

    private StageProgress snapshotDone(int idx, StageProgress base) {
        if (idx == 3) {
            var s = kgIngestionService.getStats();
            return new StageProgress(base.id(), base.name(), StageState.DONE,
                    s.totalEmails(), s.processed(), 100, null, null, null);
        }
        if (idx == 2) {
            var s = eDiscoveryIngestionService.getStats();
            return new StageProgress(base.id(), base.name(), StageState.DONE,
                    s.totalEmails(), s.indexed(), 100, null, null, null);
        }
        // For PST discovery and PST reading, use ProgressTracker snapshot
        var p = progressTracker.getProgress();
        long total = p.getTotalItems();
        long processed = p.getProcessedItems();
        return new StageProgress(base.id(), base.name(), StageState.DONE,
                total, total > 0 ? total : processed, 100, null, null, null);
    }

    private StageProgress buildLiveProgress(int idx, StageProgress base) {
        if (idx == 3) {
            var s = kgIngestionService.getStats();
            int pct = s.totalEmails() > 0
                    ? (int) (s.processed() * 100L / s.totalEmails()) : 0;
            return new StageProgress(base.id(), base.name(), StageState.RUNNING,
                    s.totalEmails(), s.processed(), pct, null,
                    s.ratePerMin() > 0 ? s.ratePerMin() : null,
                    s.etaSeconds());
        }
        if (idx == 2) {
            var s = eDiscoveryIngestionService.getStats();
            var p = progressTracker.getProgress();
            int pct = s.totalEmails() > 0
                    ? (int) (s.indexed() * 100L / s.totalEmails()) : p.getPercentage();
            return new StageProgress(base.id(), base.name(), StageState.RUNNING,
                    s.totalEmails(), s.indexed(), pct,
                    p.getStatusDetail(), null, null);
        }
        // PST Discovery (idx=0) and PST Reading (idx=1): use ProgressTracker
        var p = progressTracker.getProgress();
        return new StageProgress(base.id(), base.name(), StageState.RUNNING,
                p.getTotalItems(), p.getProcessedItems(), p.getPercentage(),
                p.getStatusDetail(), null, null);
    }

    private void setStage(int idx, StageProgress updated) {
        stages.updateAndGet(list -> {
            List<StageProgress> copy = new ArrayList<>(list);
            copy.set(idx, updated);
            return List.copyOf(copy);
        });
    }

    private List<StageProgress> buildInitialStages(PipelineStartRequest req) {
        List<StageProgress> list = new ArrayList<>();
        list.add(req.skipPstDiscovery()
                ? StageProgress.skipped(STAGE_DISCOVERY, "PST fájl keresés")
                : StageProgress.pending(STAGE_DISCOVERY, "PST fájl keresés"));
        list.add(req.skipPstProcessing()
                ? StageProgress.skipped(STAGE_PST_READ, "PST beolvasás")
                : StageProgress.pending(STAGE_PST_READ, "PST beolvasás"));
        list.add(req.skipEsIndexing()
                ? StageProgress.skipped(STAGE_ES_INDEX, "Elasticsearch indexelés")
                : StageProgress.pending(STAGE_ES_INDEX, "Elasticsearch indexelés"));
        list.add(req.skipKgIngestion()
                ? StageProgress.skipped(STAGE_KG_INGEST, "Knowledge Graph építés")
                : StageProgress.pending(STAGE_KG_INGEST, "Knowledge Graph építés"));
        return List.copyOf(list);
    }
}

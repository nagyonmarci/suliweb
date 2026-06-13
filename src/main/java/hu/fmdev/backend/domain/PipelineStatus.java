package hu.fmdev.backend.domain;

import java.util.List;

public record PipelineStatus(
        boolean running,
        List<StageProgress> stages
) {}

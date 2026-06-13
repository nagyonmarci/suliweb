package hu.fmdev.backend.dto;

import java.util.List;

public record PipelineStartRequest(
        List<String> directories,
        List<String> excludedDirectories,
        boolean saveAttachments,
        boolean skipPstDiscovery,
        boolean skipPstProcessing,
        boolean skipEsIndexing,
        boolean skipKgIngestion
) {}

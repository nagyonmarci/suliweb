package hu.fmdev.backend.service.rag;

import java.util.List;

public interface NerExtractor {
    List<ExtractedEntity> extract(String text);
    record ExtractedEntity(String name, String type) {}
}

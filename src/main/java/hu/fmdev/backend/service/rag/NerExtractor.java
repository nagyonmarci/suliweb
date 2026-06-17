package hu.fmdev.backend.service.rag;

import java.util.List;

public interface NerExtractor {
    List<ExtractedEntity> extract(String text);
    K1ExtractionOutput extractK1(String text);
    record ExtractedEntity(String name, String type) {}
}

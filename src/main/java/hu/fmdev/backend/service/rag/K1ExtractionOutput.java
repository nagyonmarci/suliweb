package hu.fmdev.backend.service.rag;

import hu.fmdev.backend.domain.graph.ClaimNode;
import hu.fmdev.backend.domain.graph.EvidenceNode;
import hu.fmdev.backend.domain.graph.MechanismNode;

import java.util.List;

public record K1ExtractionOutput(
        List<NerExtractor.ExtractedEntity> entities,
        List<ClaimNode> claims,
        List<EvidenceNode> evidence,
        List<MechanismNode> mechanisms,
        List<K1Relation> relations
) {
    public record K1Relation(String subject, String predicate, String object) {}

    public static K1ExtractionOutput empty() {
        return new K1ExtractionOutput(List.of(), List.of(), List.of(), List.of(), List.of());
    }
}

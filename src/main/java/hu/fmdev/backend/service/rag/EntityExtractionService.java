package hu.fmdev.backend.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hu.fmdev.backend.domain.graph.ClaimNode;
import hu.fmdev.backend.domain.graph.EvidenceNode;
import hu.fmdev.backend.domain.graph.MechanismNode;
import hu.fmdev.backend.logger.CentralLogger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts named entities, claims, evidence, mechanisms and relations from email text
 * via the Agents-K1 4B model (OpenAI-compatible endpoint).
 * Returns empty output on any failure — graph ingestion continues without enrichment.
 */
@Service
public class EntityExtractionService implements NerExtractor {

    private static final int MAX_INPUT_CHARS = 3_000;

    private static final String K1_PROMPT = """
            Extract structured knowledge from the following business email text.
            Return ONLY a valid JSON object. No explanation, no markdown, no code blocks.

            Schema:
            {
              "entities":   [{"name": "string", "type": "PERSON|ORG|TOPIC|LOCATION"}],
              "claims":     [{"text": "string", "claimType": "FACTUAL|CAUSAL|NORMATIVE|SPECULATIVE", "confidence": 0.85}],
              "evidence":   [{"text": "string", "evidenceType": "DIRECT_QUOTE|INFERENCE|CITATION", "sourceRef": ""}],
              "mechanisms": [{"name": "string", "description": "string", "mechanismType": "COMPUTATIONAL|LEGAL|FINANCIAL|ORGANIZATIONAL"}],
              "relations":  [{"subject": "string", "predicate": "PROVES|CONTRADICTS|INVOLVES|MENTIONS", "object": "string"}]
            }

            Rules:
            - PERSON: full names of real individuals
            - ORG: company or organization names
            - TOPIC: specific business concepts, project names, systems, standards
            - LOCATION: city, country, address
            - Return empty arrays [] for fields with no values.
            - If nothing qualifies: {"entities":[],"claims":[],"evidence":[],"mechanisms":[],"relations":[]}

            Email text:\s""";

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EntityExtractionService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public List<NerExtractor.ExtractedEntity> extract(String text) {
        return extractK1(text).entities();
    }

    @Override
    public K1ExtractionOutput extractK1(String text) {
        if (text == null || text.isBlank()) return K1ExtractionOutput.empty();
        String truncated = text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text;
        try {
            String response = chatClient.prompt()
                    .user(K1_PROMPT + truncated)
                    .call()
                    .content();
            return parseK1Response(response);
        } catch (Exception e) {
            CentralLogger.logWarn("K1 extraction failed (soft fail): " + e.getMessage());
            return K1ExtractionOutput.empty();
        }
    }

    private K1ExtractionOutput parseK1Response(String raw) {
        if (raw == null || raw.isBlank()) return K1ExtractionOutput.empty();
        try {
            String trimmed = raw.trim();
            // Strip markdown code fences if the model wraps output
            if (trimmed.startsWith("```")) {
                trimmed = trimmed.replaceAll("(?s)```(?:json)?\\s*", "").trim();
            }
            // If wrapped in an outer object, unwrap the first array-containing value
            if (!trimmed.startsWith("{")) return K1ExtractionOutput.empty();

            JsonNode root = objectMapper.readTree(trimmed);

            List<NerExtractor.ExtractedEntity> entities = new ArrayList<>();
            for (JsonNode n : iterOrEmpty(root, "entities")) {
                String name = n.path("name").asText("").trim();
                if (name.length() >= 3)
                    entities.add(new NerExtractor.ExtractedEntity(name, n.path("type").asText("TOPIC")));
            }

            List<ClaimNode> claims = new ArrayList<>();
            for (JsonNode n : iterOrEmpty(root, "claims")) {
                String text = n.path("text").asText("").trim();
                if (!text.isBlank()) {
                    ClaimNode c = new ClaimNode();
                    c.setText(text);
                    c.setClaimType(n.path("claimType").asText("FACTUAL"));
                    c.setConfidence(n.path("confidence").asDouble(0.8));
                    claims.add(c);
                }
            }

            List<EvidenceNode> evidence = new ArrayList<>();
            for (JsonNode n : iterOrEmpty(root, "evidence")) {
                String text = n.path("text").asText("").trim();
                if (!text.isBlank()) {
                    EvidenceNode e = new EvidenceNode();
                    e.setText(text);
                    e.setEvidenceType(n.path("evidenceType").asText("INFERENCE"));
                    e.setSourceRef(n.path("sourceRef").asText(""));
                    evidence.add(e);
                }
            }

            List<MechanismNode> mechanisms = new ArrayList<>();
            for (JsonNode n : iterOrEmpty(root, "mechanisms")) {
                String name = n.path("name").asText("").trim();
                if (!name.isBlank()) {
                    MechanismNode m = new MechanismNode();
                    m.setName(name);
                    m.setDescription(n.path("description").asText(""));
                    m.setMechanismType(n.path("mechanismType").asText("ORGANIZATIONAL"));
                    mechanisms.add(m);
                }
            }

            List<K1ExtractionOutput.K1Relation> relations = new ArrayList<>();
            for (JsonNode n : iterOrEmpty(root, "relations")) {
                String subject   = n.path("subject").asText("").trim();
                String predicate = n.path("predicate").asText("").trim();
                String object    = n.path("object").asText("").trim();
                if (!subject.isBlank() && !predicate.isBlank() && !object.isBlank())
                    relations.add(new K1ExtractionOutput.K1Relation(subject, predicate, object));
            }

            return new K1ExtractionOutput(entities, claims, evidence, mechanisms, relations);
        } catch (Exception e) {
            return K1ExtractionOutput.empty();
        }
    }

    private Iterable<JsonNode> iterOrEmpty(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isArray() ? node : List.of();
    }
}

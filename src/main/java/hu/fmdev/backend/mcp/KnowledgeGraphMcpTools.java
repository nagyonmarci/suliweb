package hu.fmdev.backend.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import hu.fmdev.backend.logger.CentralLogger;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Configuration(proxyBeanMethods = false)
public class KnowledgeGraphMcpTools {

    // ponytail: simple keyword guard — blocks obvious writes, not a SQL parser
    private static final Pattern WRITE_OP = Pattern.compile(
            "(?i)\\b(CREATE|MERGE|SET|DELETE|REMOVE|DROP|DETACH|LOAD\\s+CSV)\\b");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Neo4jClient neo4jClient;

    public KnowledgeGraphMcpTools(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    // -------------------------------------------------------------------------
    // MCP Tool
    // -------------------------------------------------------------------------

    @Tool(name = "query_k1_knowledge_graph", description = """
            Execute a read-only Cypher query against the SuliWeb K1 Knowledge Graph.
            Nodes: Email, Person, Thread, Concept, Claim, Evidence, Mechanism, MethodLineage.
            Relationships: MENTIONS, PROVES, CONTRADICTS, SUPPORTED_BY, INVOLVES, EXTENDS,
                           SENT, TO, CC, BELONGS_TO, REPLY_TO, HAS_ATTACHMENT, COMMUNICATES_WITH.
            Returns a JSON array of result rows. Use LIMIT to cap results (≤ 50 recommended).
            Only MATCH/RETURN/WITH/UNWIND/CALL read queries are permitted — no writes.
            """)
    public String queryK1KnowledgeGraph(String cypherQuery) {
        if (cypherQuery == null || cypherQuery.isBlank()) return "[]";
        if (WRITE_OP.matcher(cypherQuery).find()) {
            return "{\"error\":\"Only read-only Cypher queries are permitted.\"}";
        }
        try {
            List<Map<String, Object>> rows = new ArrayList<>(
                    neo4jClient.query(cypherQuery).fetch().all());
            return MAPPER.writeValueAsString(rows);
        } catch (Exception e) {
            CentralLogger.logWarn("KG MCP query error: " + e.getMessage());
            String msg = e.getMessage() == null ? "" : e.getMessage().replace("\"", "\\\"");
            return "{\"error\":\"" + msg + "\"}";
        }
    }

    @Bean
    public MethodToolCallbackProvider kgToolCallbackProvider() {
        return MethodToolCallbackProvider.builder().toolObjects(this).build();
    }

    // -------------------------------------------------------------------------
    // MCP Prompt: SCP-structured K1 graph context
    // -------------------------------------------------------------------------

    @Bean
    public List<McpServerFeatures.SyncPromptSpecification> k1ScpPrompt() {
        McpSchema.Prompt meta = new McpSchema.Prompt(
                "k1_scp_context",
                "K1 Knowledge Graph — Science Context Protocol",
                List.of(new McpSchema.PromptArgument(
                        "focus", "Topic, entity, or claim to focus the context on", false)));

        return List.of(new McpServerFeatures.SyncPromptSpecification(meta, (exchange, request) -> {
            Object focusArg = request.arguments() != null
                    ? request.arguments().get("focus") : null;
            String focus = focusArg instanceof String s ? s : "";
            return new McpSchema.GetPromptResult(
                    "K1 graph context for multi-hop SCP traversal",
                    List.of(new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            new McpSchema.TextContent(buildScpPrompt(focus)))));
        }));
    }

    private String buildScpPrompt(String focus) {
        String focusSection = focus.isBlank() ? ""
                : "\n## Focus\nFocus your analysis on: **" + focus + "**\n";
        return """
                # SuliWeb K1 Knowledge Graph — Science Context Protocol (SCP)

                ## Schema

                ### Nodes
                | Label         | Key Properties                                                    |
                |---------------|-------------------------------------------------------------------|
                | Email         | messageId, mongoId, subject, date, pstFileName, pstOwner         |
                | Person        | email, name, organization                                         |
                | Thread        | threadId, subject, lastActivity                                   |
                | Concept       | name, type (PERSON|ORG|TOPIC|LOCATION)                            |
                | Claim         | text, claimType (FACTUAL|CAUSAL|NORMATIVE|SPECULATIVE), confidence|
                | Evidence      | text, evidenceType (DIRECT_QUOTE|INFERENCE|CITATION), sourceRef   |
                | Mechanism     | name, description, mechanismType                                  |
                | MethodLineage | methodName, version, description                                  |

                ### Relationships
                | Relationship      | From          | To            | Semantics                        |
                |-------------------|---------------|---------------|----------------------------------|
                | SENT              | Person        | Email         | Email author                     |
                | TO / CC           | Email         | Person        | Recipients                       |
                | BELONGS_TO        | Email         | Thread        | Thread membership                |
                | REPLY_TO          | Email         | Email         | Reply chain                      |
                | HAS_ATTACHMENT    | Email         | Attachment    | File attachment                  |
                | MENTIONS          | Email         | Concept       | K1-extracted named entity        |
                | PROVES            | Email         | Claim         | Email asserts this claim         |
                | CONTRADICTS       | Email         | Claim         | Email contradicts this claim     |
                | SUPPORTED_BY      | Claim         | Evidence      | Evidence supporting the claim    |
                | INVOLVES          | Claim         | Mechanism     | Underlying mechanism             |
                | EXTENDS           | MethodLineage | MethodLineage | Lineage chain                    |
                | COMMUNICATES_WITH | Person        | Person        | Weighted (count, lastDate)       |

                ## SCP Multi-Hop Traversal Patterns

                **1. Claim resolution** — traverse Claim → Evidence → Mechanism:
                ```cypher
                MATCH (c:Claim)-[:SUPPORTED_BY]->(ev:Evidence),
                      (c)-[:INVOLVES]->(m:Mechanism)
                WHERE c.text CONTAINS $keyword
                RETURN c.text, c.confidence, collect(DISTINCT ev.text), collect(DISTINCT m.name)
                ```

                **2. Source attribution** — find who asserts a claim:
                ```cypher
                MATCH (p:Person)-[:SENT]->(e:Email)-[:PROVES]->(c:Claim)
                RETURN c.text, p.name, p.email, e.date ORDER BY e.date DESC LIMIT 20
                ```

                **3. Contradiction detection** — find conflicting evidence within a thread:
                ```cypher
                MATCH (t:Thread)<-[:BELONGS_TO]-(e1:Email)-[:PROVES]->(c:Claim),
                      (t)<-[:BELONGS_TO]-(e2:Email)-[:CONTRADICTS]->(c)
                RETURN t.subject, c.text, e1.subject AS proves_via, e2.subject AS contradicts_via
                ```

                **4. Communication network** — weighted graph traversal:
                ```cypher
                MATCH (p:Person)-[r:COMMUNICATES_WITH]->(q:Person)
                WHERE p.email = $email
                RETURN q.name, q.email, r.count, r.lastDate ORDER BY r.count DESC
                ```
                """ + focusSection;
    }
}

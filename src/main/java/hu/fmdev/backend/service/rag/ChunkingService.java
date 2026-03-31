package hu.fmdev.backend.service.rag;

import hu.fmdev.backend.config.RagConfig;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChunkingService {

    private final RagConfig ragConfig;

    // Sentence boundary: period/exclamation/question mark followed by whitespace,
    // but NOT after common Hungarian/Latin abbreviations.
    // Negative lookbehind for: Dr, Mr, Mrs, Ms, Prof, stb, pl, ill, ún, kb, max, min, etc, nr, No, vol
    private static final Pattern SENTENCE_SPLIT = Pattern.compile(
            "(?<!\\b(?:Dr|Mr|Mrs|Ms|Prof|stb|pl|ill|ún|kb|max|min|etc|nr|No|vol|ca|St|vs|dept|inc|corp|ltd|jan|feb|már|ápr|máj|jún|júl|aug|szept|okt|nov|dec))(?<![0-9])[.!?](?=\\s+[A-ZÁÉÍÓÖŐÚÜŰ]|\\s*$)");

    // Email-specific separators that should force a chunk boundary
    private static final Pattern EMAIL_SEPARATOR = Pattern.compile(
            "^\\s*(?:[-_=]{3,}|From:|Sent:|Subject:|Feladó:|Dátum:|Tárgy:|Címzett:|To:|Date:|CC:|BCC:)",
            Pattern.MULTILINE);

    public ChunkingService(RagConfig ragConfig) {
        this.ragConfig = ragConfig;
    }

    /**
     * Splits text into overlapping chunks at sentence boundaries.
     * Uses configured chunk size (max chars) and overlap (number of sentences).
     */
    public List<String> chunkText(String text) {
        return chunkText(text, ragConfig.getChunkSize(), ragConfig.getChunkOverlap());
    }

    /**
     * Sentence-aware chunking with greedy packing.
     *
     * @param text      the input text
     * @param chunkSize maximum characters per chunk
     * @param overlap   overlap parameter (used as sentence count for overlap: overlap/64, min 1)
     */
    public List<String> chunkText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        // Split into segments first by email separators, then by sentences
        List<String> sentences = splitIntoSentences(text);

        if (sentences.isEmpty()) {
            return chunks;
        }

        // If everything fits in one chunk, return as-is
        String joined = String.join(" ", sentences);
        if (joined.length() <= chunkSize) {
            chunks.add(joined.trim());
            return chunks;
        }

        // Overlap in number of sentences (derive from char overlap: roughly overlap/64, min 1)
        int overlapSentences = Math.max(1, overlap / 64);

        // Greedy packing: fill chunks with sentences up to chunkSize
        int i = 0;
        while (i < sentences.size()) {
            StringBuilder sb = new StringBuilder();
            int sentencesInChunk = 0;
            int chunkStart = i;

            while (i < sentences.size()) {
                String sentence = sentences.get(i);
                int newLen = sb.isEmpty() ? sentence.length() : sb.length() + 1 + sentence.length();

                if (newLen > chunkSize && sentencesInChunk > 0) {
                    // This sentence doesn't fit, stop here
                    break;
                }

                // Add sentence (even if it exceeds chunkSize on its own – we never skip content)
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(sentence);
                sentencesInChunk++;
                i++;

                // If a single sentence exceeded chunkSize, break after adding it
                if (sb.length() >= chunkSize) {
                    break;
                }
            }

            String chunk = sb.toString().trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            // Overlap: move back by overlapSentences for the next chunk
            if (i < sentences.size()) {
                int overlapBack = Math.min(overlapSentences, sentencesInChunk - 1);
                if (overlapBack > 0) {
                    i = i - overlapBack;
                }
                // Safety: ensure forward progress
                if (i <= chunkStart) {
                    i = chunkStart + 1;
                }
            }
        }

        return chunks;
    }

    /**
     * Splits text into sentences, respecting paragraph boundaries and email separators.
     * Long passages without sentence boundaries fall back to word-boundary splitting.
     */
    List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();

        // First split by paragraphs and email separators
        List<String> segments = splitByParagraphsAndSeparators(text);

        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) continue;

            // Try to split by sentence boundaries
            List<String> segSentences = splitBySentenceBoundary(trimmed);
            for (String s : segSentences) {
                String clean = s.replaceAll("\\s+", " ").trim();
                if (!clean.isEmpty()) {
                    sentences.add(clean);
                }
            }
        }

        return sentences;
    }

    private List<String> splitByParagraphsAndSeparators(String text) {
        List<String> segments = new ArrayList<>();

        // Split on double newlines (paragraphs) first
        String[] paragraphs = text.split("\\n\\s*\\n");

        for (String para : paragraphs) {
            // Further split on email separators
            Matcher m = EMAIL_SEPARATOR.matcher(para);
            int lastEnd = 0;
            while (m.find()) {
                if (m.start() > lastEnd) {
                    segments.add(para.substring(lastEnd, m.start()));
                }
                lastEnd = m.start();
            }
            if (lastEnd < para.length()) {
                segments.add(para.substring(lastEnd));
            }
        }

        return segments;
    }

    private List<String> splitBySentenceBoundary(String text) {
        List<String> sentences = new ArrayList<>();
        Matcher m = SENTENCE_SPLIT.matcher(text);
        int lastEnd = 0;

        while (m.find()) {
            // Include the punctuation in the sentence
            int sentenceEnd = m.end();
            String sentence = text.substring(lastEnd, sentenceEnd).trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
            lastEnd = sentenceEnd;
        }

        // Remainder after last sentence boundary
        if (lastEnd < text.length()) {
            String remainder = text.substring(lastEnd).trim();
            if (!remainder.isEmpty()) {
                sentences.add(remainder);
            }
        }

        // If no sentence boundaries were found, return the whole text as one "sentence"
        if (sentences.isEmpty() && !text.isBlank()) {
            sentences.add(text.trim());
        }

        return sentences;
    }
}

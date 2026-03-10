package com.developerev.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Project Processing Engine — Step 2.
 *
 * Takes the flat list produced by {@link ZipExtractorService} and enriches
 * each entry with:
 * <ul>
 * <li>Detected programming language (from extension)</li>
 * <li>MD5 content hash (for cache lookups)</li>
 * <li>Line count</li>
 * </ul>
 */
@Slf4j
@Service("aiDirectoryScannerService")
public class DirectoryScannerService {

    /** Maps file extension → human-readable language name. */
    private static final Map<String, String> EXT_TO_LANG = Map.ofEntries(
            Map.entry(".java", "Java"),
            Map.entry(".js", "JavaScript"),
            Map.entry(".ts", "TypeScript"),
            Map.entry(".py", "Python"),
            Map.entry(".go", "Go"),
            Map.entry(".kt", "Kotlin"),
            Map.entry(".cs", "C#"),
            Map.entry(".cpp", "C++"),
            Map.entry(".c", "C"),
            Map.entry(".rs", "Rust"),
            Map.entry(".rb", "Ruby"),
            Map.entry(".php", "PHP"),
            Map.entry(".swift", "Swift"));

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Enriches each {@link ZipExtractorService.ExtractedFile} with language,
     * hash, and line-count metadata.
     *
     * @param files extracted files from the ZIP
     * @return enriched {@link ScannedFile} list
     */
    public List<ScannedFile> scan(List<ZipExtractorService.ExtractedFile> files) {
        return files.stream()
                .map(this::enrich)
                .toList();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private ScannedFile enrich(ZipExtractorService.ExtractedFile f) {
        String lang = EXT_TO_LANG.getOrDefault(f.extension(), "Unknown");
        String hash = md5(f.content());
        int lines = countLines(f.content());
        return new ScannedFile(f.path(), f.filename(), f.content(), f.extension(), lang, hash, lines);
    }

    private String md5(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // MD5 is always available in the JDK; this branch is unreachable.
            return "unknown";
        }
    }

    private int countLines(String content) {
        if (content == null || content.isEmpty())
            return 0;
        int count = 1;
        for (char c : content.toCharArray()) {
            if (c == '\n')
                count++;
        }
        return count;
    }

    // ─── Inner Record ─────────────────────────────────────────────────────────

    /**
     * Enriched file value object produced by DirectoryScannerService.
     */
    public record ScannedFile(
            String path,
            String filename,
            String content,
            String extension,
            String language,
            String fileHash,
            int lineCount) {
    }
}

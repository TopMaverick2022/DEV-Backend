package com.developerev.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Project Processing Engine — Step 1.
 *
 * Responsible for extracting ZIP uploads, normalising paths, and filtering
 * out non-source directories (node_modules, build outputs, etc.).
 * Returns a flat list of {@link ExtractedFile} records ready for scanning.
 */
@Slf4j
@Service("aiZipExtractorService")
public class ZipExtractorService {

    /** Path segments that are always skipped. */
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            "node_modules", "target", "build", ".git", ".idea",
            "__pycache__", "dist", ".gradle", "out", "bin", ".mvn");

    /** Supported source-file extensions. */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".java", ".js", ".ts", ".py", ".go", ".kt", ".cs",
            ".cpp", ".c", ".rs", ".rb", ".php", ".swift");

    /** Max characters read per entry to keep AI prompts in budget. */
    private static final int MAX_FILE_CHARS = 8000;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts all supported, non-ignored source files from {@code zipFile}.
     *
     * @param zipFile multipart upload containing the project ZIP
     * @return flat list of extracted files (path, filename, content, extension)
     */
    public List<ExtractedFile> extract(MultipartFile zipFile) throws IOException {
        List<ExtractedFile> result = new ArrayList<>();

        try (ZipInputStream zis = new ZipInputStream(zipFile.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {

                String entryPath = entry.getName().replace("\\", "/");

                if (entry.isDirectory() || isIgnored(entryPath)) {
                    zis.closeEntry();
                    continue;
                }

                String ext = getExtension(entryPath);
                if (!SUPPORTED_EXTENSIONS.contains(ext)) {
                    zis.closeEntry();
                    continue;
                }

                String content = readEntry(zis);
                if (content.isBlank()) {
                    zis.closeEntry();
                    continue;
                }

                String filename = extractFilename(entryPath);
                result.add(new ExtractedFile(entryPath, filename, content, ext));
                log.debug("Extracted: {}", entryPath);
                zis.closeEntry();
            }
        }

        log.info("ZIP extraction complete: {} source files extracted", result.size());
        return result;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private boolean isIgnored(String path) {
        for (String ignored : IGNORED_DIRECTORIES) {
            if (path.contains("/" + ignored + "/") || path.startsWith(ignored + "/")) {
                return true;
            }
        }
        return false;
    }

    private String getExtension(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot).toLowerCase() : "";
    }

    private String extractFilename(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private String readEntry(ZipInputStream zis) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(zis, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
            if (sb.length() >= MAX_FILE_CHARS) {
                sb.append("\n... [truncated] ...");
                break;
            }
        }
        return sb.toString();
    }

    // ─── Inner Record ─────────────────────────────────────────────────────────

    /**
     * Immutable value object passed from ZipExtractorService to
     * DirectoryScannerService.
     */
    public record ExtractedFile(
            String path,
            String filename,
            String content,
            String extension) {
    }
}

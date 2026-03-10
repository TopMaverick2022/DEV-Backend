package com.developerev.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Recursively scans an extracted project directory and returns
 * all reviewable (non-binary, non-ignored) source file paths.
 */
@Slf4j
@Service
public class DirectoryScannerService {

    /** Directory segments to skip entirely. */
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            "node_modules", "target", "build", ".git", ".idea",
            "__pycache__", "dist", ".gradle", "vendor", ".venv", "venv",
            ".next", "out", "coverage", ".nyc_output", "bin", "obj");

    /** File extensions that are binary/asset — cannot be code-reviewed. */
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".webp", ".bmp", ".tiff",
            ".zip", ".tar", ".gz", ".bz2", ".rar", ".7z",
            ".jar", ".war", ".ear", ".class",
            ".exe", ".dll", ".so", ".dylib", ".lib", ".a", ".o",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".mp3", ".mp4", ".wav", ".avi", ".mov", ".mkv",
            ".ttf", ".woff", ".woff2", ".eot", ".otf",
            ".lock", ".DS_Store");

    /**
     * Walks the project directory and returns paths to all source files
     * that are not in ignored directories and not binary assets.
     *
     * @param root root directory of the extracted project
     * @return list of source file paths ready for language detection and review
     */
    public List<Path> scan(Path root) throws IOException {
        log.info("Scanning directory: {}", root);

        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isInIgnoredDirectory(root, p))
                    .filter(p -> !isBinary(p))
                    .collect(Collectors.toList());

            log.info("Files found: {}", files.size());
            return files;
        }
    }

    /** Returns true if any path segment is an ignored directory name. */
    private boolean isInIgnoredDirectory(Path root, Path file) {
        Path relative = root.relativize(file);
        for (Path part : relative) {
            if (IGNORED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /** Returns true if the file has a binary/asset extension. */
    private boolean isBinary(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot < 0)
            return false;
        return BINARY_EXTENSIONS.contains(name.substring(dot));
    }
}

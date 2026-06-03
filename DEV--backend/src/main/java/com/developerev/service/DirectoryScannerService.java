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

    /** File extensions that are source code and should be reviewed. */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".java", ".ts", ".tsx", ".js", ".jsx", ".py", ".go", ".sql", ".php", ".cs", ".html", ".css", ".cpp", ".c", ".h");

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
                    .filter(this::isAllowedSourceFile)
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

    /** Returns true if the file has an allowed source code extension. */
    private boolean isAllowedSourceFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return ALLOWED_EXTENSIONS.contains(name.substring(dot));
    }

    /**
     * Scans the project directory and returns a formatted list of all regular files
     * (excluding ignored directories and binary extensions) relative to the root.
     */
    public String getProjectStructure(Path root) {
        if (!Files.exists(root)) {
            return "No existing files (empty project).";
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<String> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isInIgnoredDirectory(root, p))
                    .filter(p -> !isBinaryFile(p))
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .limit(500) // safety limit
                    .collect(Collectors.toList());

            if (files.isEmpty()) {
                return "No existing files.";
            }
            return String.join("\n", files);
        } catch (IOException e) {
            log.error("Failed to scan project structure at " + root, e);
            return "Error scanning project structure.";
        }
    }

    private boolean isBinaryFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot);
        return BINARY_EXTENSIONS.contains(ext);
    }

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".ico", ".pdf", ".zip", ".gz", ".tar", ".jar", ".war", 
            ".class", ".exe", ".dll", ".so", ".dylib", ".woff", ".woff2", ".eot", ".ttf", ".mp3", 
            ".mp4", ".wav", ".avi", ".mov", ".flv", ".svg", ".db", ".sqlite");
}


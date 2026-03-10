package com.developerev.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads source file content from disk with a character limit
 * to avoid sending massive files to the AI within one request.
 */
@Slf4j
@Service
public class FileContentService {

    /** Maximum characters read per file — keeps AI token usage manageable. */
    private static final int MAX_FILE_CHARS = 4000;

    /**
     * Reads the content of the given file as a UTF-8 string.
     * Truncates to MAX_FILE_CHARS if the file is too large.
     *
     * @param file path to the source file
     * @return file content, or empty string if the file cannot be read
     */
    public String readFile(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.length() > MAX_FILE_CHARS) {
                log.debug("Truncating file '{}' from {} to {} chars",
                        file.getFileName(), content.length(), MAX_FILE_CHARS);
                return content.substring(0, MAX_FILE_CHARS) + "\n\n... [truncated for review] ...";
            }
            return content;
        } catch (IOException e) {
            log.warn("Cannot read file '{}': {}", file.getFileName(), e.getMessage());
            return "";
        }
    }
}

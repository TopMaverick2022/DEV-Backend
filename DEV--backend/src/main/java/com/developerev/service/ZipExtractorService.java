package com.developerev.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts an uploaded ZIP file into a temporary directory on disk.
 * The caller is responsible for deleting the temp directory after use.
 */
@Slf4j
@Service
public class ZipExtractorService {

    /**
     * Extracts the given ZIP multipart file into a newly created temp directory.
     *
     * @param zipFile the uploaded ZIP file
     * @return path to the temp directory containing the extracted project
     * @throws IOException if extraction fails
     */
    public Path extractZip(MultipartFile zipFile) throws IOException {

        Path tempDir = Files.createTempDirectory("codereview_");
        log.info("Extracting ZIP '{}' to temp dir: {}", zipFile.getOriginalFilename(), tempDir);

        try (ZipInputStream zis = new ZipInputStream(zipFile.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {

                // Sanitize path to prevent zip-slip attacks
                Path filePath = tempDir.resolve(entry.getName()).normalize();
                if (!filePath.startsWith(tempDir)) {
                    log.warn("Skipping malicious zip entry: {}", entry.getName());
                    zis.closeEntry();
                    continue;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(filePath);
                } else {
                    Files.createDirectories(filePath.getParent());
                    Files.copy(zis, filePath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }

        log.info("ZIP extraction complete: {}", tempDir);
        return tempDir;
    }

    /**
     * Deletes the temp directory and all its contents after review is complete.
     */
    public void cleanup(Path tempDir) {
        try {
            if (tempDir != null && Files.exists(tempDir)) {
                Files.walk(tempDir)
                        .sorted((a, b) -> -a.compareTo(b)) // delete children before parent
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException ignored) {
                            }
                        });
                log.info("Cleaned up temp dir: {}", tempDir);
            }
        } catch (IOException e) {
            log.warn("Failed to clean up temp dir {}: {}", tempDir, e.getMessage());
        }
    }
}

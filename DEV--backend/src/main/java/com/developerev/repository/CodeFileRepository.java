package com.developerev.repository;

import com.developerev.model.CodeFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodeFileRepository extends JpaRepository<CodeFile, Long> {

    /**
     * All files belonging to a project (for polling results and architecture
     * analysis).
     */
    List<CodeFile> findByProjectId(Long projectId);

    /**
     * Cache lookup: returns an existing CodeFile record if the same content hash
     * was
     * previously processed in any project.
     */
    Optional<CodeFile> findFirstByFileHash(String fileHash);

    /**
     * Robust cache lookup: returns a list of CodeFile records that match the given content hash
     * AND have at least one successfully completed CodeReview attached.
     * This prevents picking up a CodeFile from a previous failed batch analysis.
     */
    @Query(value = "SELECT cf FROM CodeFile cf WHERE cf.fileHash = :fileHash AND EXISTS (SELECT 1 FROM CodeReview cr WHERE cr.fileId = cf.id)")
    List<CodeFile> findByFileHashWithReview(@Param("fileHash") String fileHash);
}

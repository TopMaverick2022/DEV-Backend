package com.developerev.repository;

import com.developerev.model.CodeFile;
import org.springframework.data.jpa.repository.JpaRepository;
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
}

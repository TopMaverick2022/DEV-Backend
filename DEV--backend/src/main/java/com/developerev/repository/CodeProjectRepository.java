package com.developerev.repository;

import com.developerev.model.CodeProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CodeProjectRepository extends JpaRepository<CodeProject, Long> {

    /** Used to find PENDING/PROCESSING projects (e.g. for recovery on restart). */
    List<CodeProject> findByStatus(String status);
}

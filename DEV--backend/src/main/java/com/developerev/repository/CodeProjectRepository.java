package com.developerev.repository;

import com.developerev.model.CodeProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CodeProjectRepository extends JpaRepository<CodeProject, Long> {
}

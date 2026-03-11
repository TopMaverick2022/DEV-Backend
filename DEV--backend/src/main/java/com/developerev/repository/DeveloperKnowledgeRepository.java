package com.developerev.repository;

import com.developerev.model.DeveloperKnowledge;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeveloperKnowledgeRepository extends JpaRepository<DeveloperKnowledge, Long> {
}

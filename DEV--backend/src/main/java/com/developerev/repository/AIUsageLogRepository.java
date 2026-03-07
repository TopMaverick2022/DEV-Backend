package com.developerev.repository;

import com.developerev.model.AIUsageLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AIUsageLogRepository extends JpaRepository<AIUsageLog, Long> {
}

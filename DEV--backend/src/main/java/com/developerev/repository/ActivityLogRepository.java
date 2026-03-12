package com.developerev.repository;

import com.developerev.model.ActivityLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {
    List<ActivityLog> findByProjectIdOrderByTimestampDesc(Long projectId);
    List<ActivityLog> findByUserIdOrderByTimestampDesc(Long userId);
}

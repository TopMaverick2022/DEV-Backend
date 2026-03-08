package com.developerev.repository;

import com.developerev.model.TaskDependency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskDependencyRepository extends JpaRepository<TaskDependency, Long> {

    /**
     * Find all dependencies where the dependent task belongs to the given feature.
     * Useful for fetching all detected dependencies for a feature in one query.
     */
    List<TaskDependency> findByDependentTask_FeatureId(Long featureId);
}

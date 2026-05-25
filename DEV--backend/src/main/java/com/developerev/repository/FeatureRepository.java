package com.developerev.repository;

import com.developerev.model.Feature;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeatureRepository extends JpaRepository<Feature, Long> {

    /** Returns all features linked to a specific project, ordered most-recent first. */
    List<Feature> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}

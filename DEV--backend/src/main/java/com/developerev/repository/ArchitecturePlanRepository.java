package com.developerev.repository;

import com.developerev.model.ArchitecturePlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ArchitecturePlanRepository extends JpaRepository<ArchitecturePlan, Long> {
}

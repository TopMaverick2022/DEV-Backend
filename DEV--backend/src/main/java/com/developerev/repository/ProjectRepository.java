package com.developerev.repository;

import com.developerev.entity.User;
import com.developerev.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findByOwner(User owner);

    Optional<Project> findByIdAndOwner(Long id, User owner);
}

package com.developerev.service;

import com.developerev.dto.ActivityLogResponseDto;
import com.developerev.entity.User;
import com.developerev.model.ActivityLog;
import com.developerev.model.Project;
import com.developerev.repository.ActivityLogRepository;
import com.developerev.repository.ProjectRepository;
import com.developerev.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ActivityLogService {

    private final ActivityLogRepository activityLogRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;

    @Transactional
    public void logActivity(User user, Project project, String action, String details) {
        ActivityLog logRecord = ActivityLog.builder()
                .user(user)
                .project(project)
                .action(action)
                .details(details)
                .build();
        activityLogRepository.save(logRecord);
    }

    @Transactional
    public void logCurrentUserActivity(Long projectId, String action, String details) {
        try {
            String username = SecurityContextHolder.getContext().getAuthentication().getName();
            User user = userRepository.findByUsername(username).orElse(null);
            if (user != null) {
                Project project = (projectId != null) ? projectRepository.findById(projectId).orElse(null) : null;
                logActivity(user, project, action, details);
            }
        } catch (Exception e) {
            // Context might be null during tests or system calls
        }
    }

    public List<ActivityLogResponseDto> getProjectActivity(Long projectId) {
        return activityLogRepository.findByProjectIdOrderByTimestampDesc(projectId).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    public List<ActivityLogResponseDto> getUserActivity(Long userId) {
        return activityLogRepository.findByUserIdOrderByTimestampDesc(userId).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    private ActivityLogResponseDto mapToDto(ActivityLog logRecord) {
        return ActivityLogResponseDto.builder()
                .id(logRecord.getId())
                .username(logRecord.getUser().getUsername())
                .projectName(logRecord.getProject() != null ? logRecord.getProject().getName() : "Global")
                .action(logRecord.getAction())
                .details(logRecord.getDetails())
                .timestamp(logRecord.getTimestamp())
                .build();
    }
}

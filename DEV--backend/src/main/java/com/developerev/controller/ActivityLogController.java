package com.developerev.controller;

import com.developerev.dto.ActivityLogResponseDto;
import com.developerev.service.ActivityLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/activity")
@RequiredArgsConstructor
public class ActivityLogController {

    private final ActivityLogService activityLogService;

    @GetMapping("/project/{projectId}")
    public ResponseEntity<List<ActivityLogResponseDto>> getProjectActivity(@PathVariable Long projectId) {
        return ResponseEntity.ok(activityLogService.getProjectActivity(projectId));
    }
}

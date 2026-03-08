package com.developerev.controller;

import com.developerev.dto.FeatureDetailResponseDto;
import com.developerev.model.Feature;
import com.developerev.model.Task;
import com.developerev.repository.FeatureRepository;
import com.developerev.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/features")
@RequiredArgsConstructor
public class FeatureController {

    private final FeatureRepository featureRepository;
    private final TaskRepository taskRepository;

    // GET /features — Return all features
    @GetMapping
    public List<Feature> getAllFeatures() {
        return featureRepository.findAll();
    }

    // GET /features/{id} — Return feature + tasks together
    @GetMapping("/{id}")
    public ResponseEntity<FeatureDetailResponseDto> getFeatureDetail(@PathVariable Long id) {
        return featureRepository.findById(id)
                .map(feature -> {
                    List<Task> tasks = taskRepository.findByFeatureId(id);
                    FeatureDetailResponseDto dto = new FeatureDetailResponseDto(
                            feature.getId(),
                            feature.getName(),
                            feature.getComplexity(),
                            feature.getTotalEstimatedHours(),
                            tasks);
                    return ResponseEntity.ok(dto);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // GET /features/{id}/tasks — Return tasks of a feature
    @GetMapping("/{id}/tasks")
    public ResponseEntity<List<Task>> getFeatureTasks(@PathVariable Long id) {
        if (!featureRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(taskRepository.findByFeatureId(id));
    }
}

package com.developerev.controller;

import com.developerev.dto.CriticalPathResponseDto;
import com.developerev.dto.FeatureDetailResponseDto;
import com.developerev.model.Feature;
import com.developerev.model.Task;
import com.developerev.repository.FeatureRepository;
import com.developerev.repository.TaskRepository;
import com.developerev.service.AntiGravityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@SuppressWarnings("null")
@RestController
@RequestMapping("/api/features")
@RequiredArgsConstructor
public class FeatureController {

    private final FeatureRepository featureRepository;
    private final TaskRepository taskRepository;
    private final AntiGravityService antiGravityService;

    // GET /features — Return all features
    @GetMapping
    public List<Feature> getAllFeatures() {
        return featureRepository.findAll();
    }

    /**
     * GET /features/project/{projectId}
     *
     * Returns all features (with tasks) that belong to a specific project.
     * Used by the Dashboard "Project Plans" tab to render all saved AI plans.
     */
    @GetMapping("/project/{projectId}")
    public ResponseEntity<List<FeatureDetailResponseDto>> getFeaturesByProject(@PathVariable Long projectId) {
        List<Feature> features = featureRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        List<FeatureDetailResponseDto> result = features.stream()
                .map(feature -> {
                    List<Task> tasks = taskRepository.findByFeatureId(feature.getId());
                    return new FeatureDetailResponseDto(
                            feature.getId(),
                            feature.getName(),
                            feature.getComplexity(),
                            feature.getTotalEstimatedHours(),
                            tasks,
                            feature.getDetectedNeeds());
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // GET /features/{id} — Return feature + tasks together
    @GetMapping("/{id}")
    public ResponseEntity<FeatureDetailResponseDto> getFeatureDetail(@PathVariable long id) {
        return featureRepository.findById(id)
                .map(feature -> {
                    List<Task> tasks = taskRepository.findByFeatureId(id);
                    FeatureDetailResponseDto dto = new FeatureDetailResponseDto(
                            feature.getId(),
                            feature.getName(),
                            feature.getComplexity(),
                            feature.getTotalEstimatedHours(),
                            tasks,
                            feature.getDetectedNeeds());
                    return ResponseEntity.ok(dto);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // GET /features/{id}/tasks — Return tasks of a feature
    @GetMapping("/{id}/tasks")
    public ResponseEntity<List<Task>> getFeatureTasks(@PathVariable long id) {
        if (!featureRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(taskRepository.findByFeatureId(id));
    }

    /**
     * GET /features/{id}/critical-path
     *
     * Calculates the critical path for a feature using the saved task dependencies.
     * The critical path is the longest chain of dependent tasks (by estimated
     * hours),
     * which determines the minimum time needed to complete the feature.
     *
     * Pre-condition: run POST /ai/detect-dependencies/{featureId} first to populate
     * the dependency graph.
     */
    @GetMapping("/{id}/critical-path")
    public ResponseEntity<CriticalPathResponseDto> getCriticalPath(@PathVariable long id) {
        if (!featureRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        try {
            CriticalPathResponseDto result = antiGravityService.computeCriticalPath(id);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // DELETE /api/features/{id} — Delete feature + all associated tasks
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFeature(@PathVariable long id) {
        if (!featureRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        List<Task> tasks = taskRepository.findByFeatureId(id);
        taskRepository.deleteAll(tasks);
        featureRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    // PUT /api/features/{id} — Update feature details (name, complexity)
    @PutMapping("/{id}")
    public ResponseEntity<Feature> updateFeature(@PathVariable long id, @RequestBody Feature updatedFeature) {
        return featureRepository.findById(id).map(feature -> {
            feature.setName(updatedFeature.getName());
            feature.setComplexity(updatedFeature.getComplexity());
            if (updatedFeature.getDescription() != null) {
                feature.setDescription(updatedFeature.getDescription());
            }
            if (updatedFeature.getTotalEstimatedHours() != null) {
                feature.setTotalEstimatedHours(updatedFeature.getTotalEstimatedHours());
            }
            if (updatedFeature.getDetectedNeeds() != null) {
                feature.setDetectedNeeds(updatedFeature.getDetectedNeeds());
            }
            return ResponseEntity.ok(featureRepository.save(feature));
        }).orElse(ResponseEntity.notFound().build());
    }
}

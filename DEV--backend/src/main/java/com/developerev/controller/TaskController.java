package com.developerev.controller;

import com.developerev.model.Task;
import com.developerev.model.TaskStatus;
import com.developerev.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskRepository taskRepository;

    // GET /tasks/{id}
    @GetMapping("/{id}")
    public ResponseEntity<Task> getTask(@PathVariable long id) {
        return taskRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // PATCH /tasks/{id}/status
    // Body: { "status": "IN_PROGRESS" }
    @PatchMapping("/{id}/status")
    public ResponseEntity<Task> updateStatus(@PathVariable long id,
            @RequestBody(required = false) Map<String, String> body) {
        if (body == null || !body.containsKey("status")) {
            return ResponseEntity.badRequest().build();
        }
        return taskRepository.findById(id).<ResponseEntity<Task>>map(task -> {
            try {
                TaskStatus newStatus = TaskStatus.valueOf(body.get("status").toUpperCase());
                task.setStatus(newStatus);
                return ResponseEntity.ok(taskRepository.save(task));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    // PATCH /tasks/{id}/assignee
    // Body: { "assignee": "Arun" }
    @PatchMapping("/{id}/assignee")
    public ResponseEntity<Task> updateAssignee(@PathVariable long id,
            @RequestBody Map<String, String> body) {
        return taskRepository.findById(id).map(task -> {
            task.setAssignee(body.get("assignee"));
            return ResponseEntity.ok(taskRepository.save(task));
        }).orElse(ResponseEntity.notFound().build());
    }
}

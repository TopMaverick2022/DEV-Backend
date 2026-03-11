package com.developerev.service;

import com.developerev.dto.KnowledgeRequestDto;
import com.developerev.dto.KnowledgeResponseDto;
import com.developerev.model.DeveloperKnowledge;
import com.developerev.repository.DeveloperKnowledgeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final DeveloperKnowledgeRepository repository;

    public KnowledgeResponseDto saveKnowledge(KnowledgeRequestDto request) {
        // For simplicity, handle a single active knowledge record (update if exists, else create)
        DeveloperKnowledge knowledge;
        List<DeveloperKnowledge> existing = repository.findAll();
        if (!existing.isEmpty()) {
            knowledge = existing.get(0);
        } else {
            knowledge = new DeveloperKnowledge();
        }

        knowledge.setProjectFrameworks(request.getProjectFrameworks());
        knowledge.setArchitecturePattern(request.getArchitecturePattern());
        knowledge.setCodingConventions(request.getCodingConventions());

        knowledge = repository.save(knowledge);
        log.info("Saved Developer Knowledge Memory config, id={}", knowledge.getId());

        return KnowledgeResponseDto.builder()
                .id(knowledge.getId())
                .projectFrameworks(knowledge.getProjectFrameworks())
                .architecturePattern(knowledge.getArchitecturePattern())
                .codingConventions(knowledge.getCodingConventions())
                .status("SUCCESS")
                .build();
    }

    public KnowledgeResponseDto getKnowledge() {
        List<DeveloperKnowledge> existing = repository.findAll();
        if (!existing.isEmpty()) {
            DeveloperKnowledge knowledge = existing.get(0);
            return KnowledgeResponseDto.builder()
                    .id(knowledge.getId())
                    .projectFrameworks(knowledge.getProjectFrameworks())
                    .architecturePattern(knowledge.getArchitecturePattern())
                    .codingConventions(knowledge.getCodingConventions())
                    .status("SUCCESS")
                    .build();
        }
        return KnowledgeResponseDto.builder()
                .status("NO_DATA")
                .build();
    }

    public String buildKnowledgePromptString() {
        List<DeveloperKnowledge> existing = repository.findAll();
        if (existing.isEmpty()) {
            return "";
        }
        DeveloperKnowledge k = existing.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== DEVELOPER KNOWLEDGE CONTEXT ===\n");
        if (k.getProjectFrameworks() != null && !k.getProjectFrameworks().isBlank()) {
            sb.append("Project Frameworks: ").append(k.getProjectFrameworks()).append("\n");
        }
        if (k.getArchitecturePattern() != null && !k.getArchitecturePattern().isBlank()) {
            sb.append("Architecture Pattern: ").append(k.getArchitecturePattern()).append("\n");
        }
        if (k.getCodingConventions() != null && !k.getCodingConventions().isBlank()) {
            sb.append("Coding Conventions: ").append(k.getCodingConventions()).append("\n");
        }
        sb.append("===================================");
        return sb.toString();
    }
}

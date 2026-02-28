package com.developerev.ai.controller;

import com.developerev.ai.dto.AIRequest;
import com.developerev.ai.prompt.PromptType;
import com.developerev.ai.service.AIService;
import com.developerev.entity.User;
import com.developerev.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class DeveloperAssistantController {

    private final AIService aiService;
    private final UserRepository userRepository; // To fetch the actual user entity

    @PostMapping("/code-review")
    public ResponseEntity<String> performCodeReview(
            @RequestBody AIRequest request,
            Authentication authentication) {

        User user = getUserFromAuth(authentication);

        String aiResponse = aiService.processRequest(
                user,
                PromptType.CODE_REVIEW,
                request.getUserContext(),
                request.getPreferredProvider());

        return ResponseEntity.ok(aiResponse);
    }

    @PostMapping("/debug")
    public ResponseEntity<String> performDebugAnalysis(
            @RequestBody AIRequest request,
            Authentication authentication) {

        User user = getUserFromAuth(authentication);

        String aiResponse = aiService.processRequest(
                user,
                PromptType.DEBUG_ASSISTANT,
                request.getUserContext(),
                request.getPreferredProvider());

        return ResponseEntity.ok(aiResponse);
    }

    private User getUserFromAuth(Authentication authentication) {
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found in DB"));
    }
}

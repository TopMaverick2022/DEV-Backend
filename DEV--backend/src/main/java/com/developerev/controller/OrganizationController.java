package com.developerev.controller;

import com.developerev.dto.AddMemberRequestDto;
import com.developerev.dto.OrganizationRequestDto;
import com.developerev.dto.OrganizationResponseDto;
import com.developerev.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;

    @PostMapping
    public ResponseEntity<OrganizationResponseDto> createOrganization(
            @RequestBody OrganizationRequestDto request,
            Authentication authentication) {
        String username = authentication.getName();
        return ResponseEntity.ok(organizationService.createOrganization(username, request));
    }

    @PostMapping("/{orgId}/members")
    public ResponseEntity<String> addMember(
            @PathVariable Long orgId,
            @RequestBody AddMemberRequestDto request,
            Authentication authentication) {
        String username = authentication.getName();
        organizationService.addMember(orgId, username, request);
        return ResponseEntity.ok("Member added successfully");
    }

    @GetMapping("/{orgId}/members")
    public ResponseEntity<List<String>> getMembers(
            @PathVariable Long orgId,
            Authentication authentication) {
        String username = authentication.getName();
        return ResponseEntity.ok(organizationService.getMembers(orgId, username));
    }
}

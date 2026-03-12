package com.developerev.service;

import com.developerev.dto.AddMemberRequestDto;
import com.developerev.dto.OrganizationRequestDto;
import com.developerev.dto.OrganizationResponseDto;
import com.developerev.entity.Organization;
import com.developerev.entity.OrganizationMember;
import com.developerev.entity.User;
import com.developerev.repository.OrganizationMemberRepository;
import com.developerev.repository.OrganizationRepository;
import com.developerev.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository organizationMemberRepository;
    private final UserRepository userRepository;

    @Transactional
    public OrganizationResponseDto createOrganization(String username, OrganizationRequestDto request) {
        User owner = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (organizationRepository.findByName(request.getName()).isPresent()) {
            throw new RuntimeException("Organization name already exists");
        }

        Organization org = Organization.builder()
                .name(request.getName())
                .description(request.getDescription())
                .owner(owner)
                .build();

        org = organizationRepository.save(org);

        OrganizationMember member = OrganizationMember.builder()
                .organization(org)
                .user(owner)
                .role("ADMIN")
                .build();
        
        organizationMemberRepository.save(member);

        return OrganizationResponseDto.builder()
                .id(org.getId())
                .name(org.getName())
                .description(org.getDescription())
                .ownerUsername(owner.getUsername())
                .createdAt(org.getCreatedAt())
                .build();
    }

    @Transactional
    public void addMember(Long organizationId, String adminUsername, AddMemberRequestDto request) {
        Organization org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new RuntimeException("Organization not found"));

        User adminUser = userRepository.findByUsername(adminUsername)
                .orElseThrow(() -> new RuntimeException("Admin not found"));

        OrganizationMember adminMember = organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, adminUser.getId())
                .orElseThrow(() -> new RuntimeException("You are not a member of this organization"));

        if (!"ADMIN".equals(adminMember.getRole())) {
            throw new RuntimeException("Only admins can add members");
        }

        User targetUser = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User to add not found"));

        if (organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, targetUser.getId()).isPresent()) {
            throw new RuntimeException("User is already a member");
        }

        OrganizationMember newMember = OrganizationMember.builder()
                .organization(org)
                .user(targetUser)
                .role(request.getRole())
                .build();

        organizationMemberRepository.save(newMember);
    }

    public List<String> getMembers(Long organizationId, String requesterUsername) {
        // Verify requester is part of org
        User requester = userRepository.findByUsername(requesterUsername).orElseThrow();
        organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, requester.getId())
                .orElseThrow(() -> new RuntimeException("Access denied"));

        return organizationMemberRepository.findByOrganizationId(organizationId).stream()
                .map(member -> member.getUser().getUsername() + " (" + member.getRole() + ")")
                .collect(Collectors.toList());
    }
}

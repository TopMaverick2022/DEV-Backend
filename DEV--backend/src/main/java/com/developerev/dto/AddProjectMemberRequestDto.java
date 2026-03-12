package com.developerev.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddProjectMemberRequestDto {
    private String email;
    private String role; // "ADMIN", "MEMBER", "VIEWER"
}

package com.developerev.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Full response returned by POST /ai/generate-architecture.
 *
 * Contains the saved plan ID plus the structured architecture
 * produced by the AI (services, APIs, event flows).
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArchitectureResponseDto {

    /** Database ID of the persisted ArchitecturePlan record. */
    private Long planId;

    private List<ServiceDto> services;
    private List<ApiDto> apis;
    private List<EventDto> events;

    // ── Inner DTOs ────────────────────────────────────────────────────────────

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ServiceDto {
        private String name;
        private String description;
        private String database;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApiDto {
        private String method;
        private String endpoint;
        private String description;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EventDto {
        private String name;
        private String producer;
        private String consumer;
    }
}

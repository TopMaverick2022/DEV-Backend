package com.developerev.dto;

import lombok.Data;

@Data
public class RecommendDependenciesRequestDto {
    private String language;
    private String languageVersion;
    private String framework;
    private String frameworkVersion;
    private String database;
    private String databaseVersion;
}

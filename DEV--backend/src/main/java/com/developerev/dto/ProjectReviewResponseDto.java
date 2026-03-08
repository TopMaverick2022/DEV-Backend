package com.developerev.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Top-level response for POST /ai/code-review.
 *
 * Contains the project ID, name, total files reviewed,
 * and the per-file reviews.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectReviewResponseDto {

    private Long projectId;
    private String projectName;
    private int totalFilesReviewed;

    private List<FileReviewDto> fileReviews;
}

package com.developerev.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseSchemaResponseDto {
    private List<Table> tables;
    private List<String> relationships;
    private List<String> indexes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Table {
        private String name;
        private List<Column> columns;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Column {
        private String name;
        private String type;
        private Boolean primaryKey;
    }
}

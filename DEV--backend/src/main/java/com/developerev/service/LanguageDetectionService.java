package com.developerev.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Detects the programming language of a source file using:
 * 1. File extension (primary method — 50+ languages)
 * 2. Content heuristics - shebang lines and keywords (fallback for unknown
 * extensions)
 */
@Service
public class LanguageDetectionService {

    /** Mapping of lowercase file extensions to human-readable language names. */
    private static final Map<String, String> EXTENSION_TO_LANGUAGE = Map.ofEntries(
            Map.entry("java", "Java"),
            Map.entry("py", "Python"),
            Map.entry("js", "JavaScript"),
            Map.entry("mjs", "JavaScript"),
            Map.entry("cjs", "JavaScript"),
            Map.entry("ts", "TypeScript"),
            Map.entry("tsx", "TypeScript/React"),
            Map.entry("jsx", "JavaScript/React"),
            Map.entry("cs", "C#"),
            Map.entry("php", "PHP"),
            Map.entry("rb", "Ruby"),
            Map.entry("go", "Go"),
            Map.entry("kt", "Kotlin"),
            Map.entry("kts", "Kotlin Script"),
            Map.entry("swift", "Swift"),
            Map.entry("rs", "Rust"),
            Map.entry("cpp", "C++"),
            Map.entry("cxx", "C++"),
            Map.entry("cc", "C++"),
            Map.entry("c", "C"),
            Map.entry("h", "C/C++ Header"),
            Map.entry("hpp", "C++ Header"),
            Map.entry("html", "HTML"),
            Map.entry("htm", "HTML"),
            Map.entry("css", "CSS"),
            Map.entry("scss", "SCSS"),
            Map.entry("sass", "SASS"),
            Map.entry("less", "LESS"),
            Map.entry("sql", "SQL"),
            Map.entry("sh", "Shell"),
            Map.entry("bash", "Bash"),
            Map.entry("yaml", "YAML/Config"),
            Map.entry("yml", "YAML/Config"),
            Map.entry("xml", "XML/Config"),
            Map.entry("tf", "Terraform"),
            Map.entry("vue", "Vue.js"),
            Map.entry("dart", "Dart/Flutter"),
            Map.entry("lua", "Lua"),
            Map.entry("r", "R"),
            Map.entry("scala", "Scala"),
            Map.entry("ex", "Elixir"),
            Map.entry("exs", "Elixir Script"),
            Map.entry("erl", "Erlang"),
            Map.entry("hs", "Haskell"),
            Map.entry("clj", "Clojure"),
            Map.entry("groovy", "Groovy"),
            Map.entry("gradle", "Gradle/Groovy"),
            Map.entry("json", "JSON/Config"),
            Map.entry("md", "Markdown"),
            Map.entry("ai", "Unknown"),
            Map.entry("toml", "TOML/Config"),
            Map.entry("ini", "INI/Config"),
            Map.entry("env", "Environment Config"));

    /**
     * Detects the programming language for a file.
     * Falls back to content heuristics if extension is unknown.
     *
     * @param file path to the source file
     * @return language name, e.g. "Python", "Java", or "Unknown"
     */
    public String detectLanguage(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1);
            String lang = EXTENSION_TO_LANGUAGE.get(ext);
            if (lang != null && !lang.equals("Unknown")) {
                return lang;
            }
        }
        // Fallback: inspect first line of file content
        return detectByContent(file);
    }

    /**
     * Reads the first line of the file and checks for shebang lines
     * or distinctive keywords to guess the language.
     */
    private String detectByContent(Path file) {
        try {
            String firstLine = Files.lines(file).findFirst().orElse("").trim();
            if (firstLine.contains("python"))
                return "Python";
            if (firstLine.contains("/bin/bash") ||
                    firstLine.contains("/bin/sh"))
                return "Shell";
            if (firstLine.contains("node"))
                return "JavaScript";
            if (firstLine.contains("ruby"))
                return "Ruby";
            if (firstLine.contains("perl"))
                return "Perl";
            if (firstLine.startsWith("<?php"))
                return "PHP";
        } catch (IOException ignored) {
            // Cannot read file — return Unknown
        }
        return "Unknown";
    }
}

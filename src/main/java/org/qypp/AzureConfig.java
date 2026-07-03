package org.qypp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Shared configuration helper for the sample Azure management utility.
 *
 * <p>This class is not executable. It loads {@code conf/conf.properties}, resolves
 * required values from either the properties file or environment variables, and
 * contains small parsing helpers used by the creator/deleter/graph classes.</p>
 */
final class AzureConfig {
    static final Path CONFIG_PATH = Path.of("conf", "conf.properties");

    private AzureConfig() {
    }

    static Properties load() throws IOException {
        Properties properties = new Properties();
        if (!Files.exists(CONFIG_PATH)) {
            throw new IllegalStateException("Missing config file: " + CONFIG_PATH.toAbsolutePath());
        }
        try (InputStream input = Files.newInputStream(CONFIG_PATH)) {
            properties.load(input);
        }
        return properties;
    }

    static String required(Properties config, String key) {
        String property = value(config, key);
        if (!property.isBlank()) {
            return property;
        }

        String environmentVariable = key.toUpperCase();
        String environmentValue = System.getenv(environmentVariable);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue;
        }

        throw new IllegalStateException("Missing required config value: " + key
                + " in " + CONFIG_PATH.toAbsolutePath()
                + " or environment variable " + environmentVariable);
    }

    static String firstPresent(Properties config, String... keys) {
        List<String> missingKeys = new ArrayList<>();
        for (String key : keys) {
            try {
                return required(config, key);
            } catch (IllegalStateException ignored) {
                missingKeys.add(key);
            }
        }
        throw new IllegalStateException("Missing one of required config values: " + missingKeys);
    }

    static String value(Properties config, String key) {
        return config.getProperty(key, "").trim();
    }

    static List<String> csvValues(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isBlank())
                .collect(Collectors.toList());
    }

    static String endpointName(int index, String target) {
        String sanitized = target.replaceAll("[^A-Za-z0-9-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "")
                .toLowerCase();
        if (sanitized.isBlank()) {
            sanitized = "target";
        }
        if (sanitized.length() > 40) {
            sanitized = sanitized.substring(0, 40).replaceAll("-$", "");
        }
        return "external-" + index + "-" + sanitized;
    }

    static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

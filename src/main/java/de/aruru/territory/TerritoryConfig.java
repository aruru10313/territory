package de.aruru.territory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

final class TerritoryConfig {
    private static final Path FILE = Path.of("config", "territory", "territory.properties");
    private final Set<String> enabledWorlds;

    private TerritoryConfig(Set<String> enabledWorlds) {
        this.enabledWorlds = enabledWorlds;
    }

    static TerritoryConfig load() {
        try {
            Files.createDirectories(FILE.getParent());
            java.util.Properties properties = new java.util.Properties();
            if (Files.exists(FILE)) {
                try (var reader = Files.newBufferedReader(FILE)) {
                    properties.load(reader);
                }
            } else {
                properties.setProperty("enabled-worlds", "minecraft:overworld");
                try (var writer = Files.newBufferedWriter(FILE)) {
                    properties.store(writer, "Territory settings");
                }
            }
            Set<String> worlds = Arrays.stream(properties.getProperty(
                            "enabled-worlds", "minecraft:overworld").split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .collect(Collectors.toUnmodifiableSet());
            return new TerritoryConfig(worlds);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load territory settings", ex);
        }
    }

    boolean isEnabled(String dimension) {
        return enabledWorlds.contains(dimension);
    }
}

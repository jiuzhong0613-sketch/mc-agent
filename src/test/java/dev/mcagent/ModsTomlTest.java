package dev.mcagent;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forge 1.20.1 still expects the legacy "mandatory" dependency field.
 * "type = required" parses as a missing mandatory field and crashes at launch.
 */
class ModsTomlTest {
    @Test
    void dependenciesUseMandatoryField() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/META-INF/mods.toml")) {
            assertNotNull(in, "mods.toml missing from resources");
            String toml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(toml.contains("mandatory = true"), "missing mandatory field");
            assertFalse(toml.contains("type = \"required\""), "Forge 1.20.1 rejects type=required");
        }
    }
}

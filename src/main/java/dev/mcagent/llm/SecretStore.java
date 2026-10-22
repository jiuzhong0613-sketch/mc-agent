package dev.mcagent.llm;

import dev.mcagent.AgentConfig;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * Key resolution order: environment variable, secret file, config value.
 * The key is never logged, never returned by the bridge, never placed in prompts.
 */
public final class SecretStore {
    public static final String ENV = "MCAGENT_API_KEY";
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("mcagent").resolve("secret.txt");

    private SecretStore() {
    }

    public static String key() {
        String env = System.getenv(ENV);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String fromFile = readFile();
        if (!fromFile.isEmpty()) {
            return fromFile;
        }
        return "";
    }

    public static boolean present() {
        return !key().isEmpty();
    }

    public static String masked() {
        String key = key();
        if (key.isEmpty()) {
            return "none";
        }
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }

    public static Path file() {
        return FILE;
    }

    public static void save(String key) throws IOException {
        Files.createDirectories(FILE.getParent());
        Files.writeString(FILE, key.trim() + System.lineSeparator());
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(FILE, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows: ACL inheritance applies.
        }
    }

    private static String readFile() {
        try {
            if (Files.isRegularFile(FILE)) {
                return Files.readString(FILE).trim();
            }
        } catch (IOException ignored) {
        }
        return "";
    }
}

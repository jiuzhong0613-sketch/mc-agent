package dev.mcagent.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.mcagent.AgentConfig;
import dev.mcagent.ClientThread;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Per-world memory: durable facts, lessons, skills and recent episodes.
 *
 * Stored as JSON under config/mcagent/memory/<world>.json. Writes are atomic and
 * debounced; entries are capped so the file cannot grow without bound.
 */
public final class MemoryStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final MemoryStore INSTANCE = new MemoryStore();
    private static final int MAX_FACTS = 1200;
    private static final int MAX_EPISODES = 600;
    private static final int MAX_LESSONS = 150;
    private static final int MAX_SKILLS = 80;
    private static final long FLUSH_INTERVAL_MS = 5000;

    private final Object lock = new Object();
    private JsonObject root;
    private Path file;
    private String worldKey;
    private boolean dirty;
    private long lastFlush;
    private int nextId = 1;

    private MemoryStore() {
    }

    public static MemoryStore get() {
        return INSTANCE;
    }

    public String worldKey() {
        synchronized (lock) {
            root();
            return worldKey;
        }
    }

    public void remember(String kind, String text, String tags) {
        if (!AgentConfig.MEMORY_ENABLED.get() || text == null || text.isBlank()) {
            return;
        }
        synchronized (lock) {
            JsonObject root = root();
            boolean lesson = "lesson".equalsIgnoreCase(kind);
            JsonArray target = lesson ? root.getAsJsonArray("lessons") : root.getAsJsonArray("facts");
            String normalized = text.trim().toLowerCase(Locale.ROOT);
            for (JsonElement element : target) {
                JsonObject entry = element.getAsJsonObject();
                if (entry.get("text").getAsString().trim().toLowerCase(Locale.ROOT).equals(normalized)) {
                    entry.addProperty("confidence", Math.min(1.0, entry.get("confidence").getAsDouble() + 0.1));
                    entry.addProperty("updated", now());
                    dirty = true;
                    return;
                }
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", nextId++);
            entry.addProperty("text", text.trim());
            entry.add("tags", tags(tags));
            entry.addProperty("confidence", 0.7);
            entry.addProperty("created", now());
            entry.addProperty("updated", now());
            target.add(entry);
            trim(target, lesson ? MAX_LESSONS : MAX_FACTS, true);
            dirty = true;
        }
    }

    public void recordEpisode(String action, JsonObject args, JsonObject result) {
        if (!AgentConfig.MEMORY_ENABLED.get()) {
            return;
        }
        synchronized (lock) {
            JsonObject root = root();
            JsonObject episode = new JsonObject();
            episode.addProperty("t", now());
            episode.addProperty("action", action);
            episode.addProperty("ok", result.has("ok") && result.get("ok").getAsBoolean());
            if (result.has("error")) {
                episode.addProperty("error", truncate(result.get("error").getAsString(), 160));
            }
            if (args != null) {
                episode.addProperty("args", truncate(args.toString(), 200));
            }
            episode.addProperty("id", nextId++);
            JsonArray episodes = root.getAsJsonArray("episodes");
            episodes.add(episode);
            trim(episodes, MAX_EPISODES, false);
            dirty = true;
        }
    }

    public JsonObject recall(String query, int limit) {
        synchronized (lock) {
            JsonObject root = root();
            List<String> tokens = tokens(query);
            JsonArray out = new JsonArray();
            collect(root.getAsJsonArray("facts"), "fact", tokens, limit, out);
            collect(root.getAsJsonArray("lessons"), "lesson", tokens, limit, out);
            collect(root.getAsJsonArray("skills"), "skill", tokens, limit, out);
            JsonObject result = new JsonObject();
            result.add("entries", out);
            return result;
        }
    }

    public String brief(String goal) {
        if (!AgentConfig.MEMORY_ENABLED.get()) {
            return "(memory disabled)";
        }
        synchronized (lock) {
            JsonObject root = root();
            List<String> tokens = tokens(goal);
            StringBuilder sb = new StringBuilder();
            append(sb, "facts", root.getAsJsonArray("facts"), tokens, 6, false);
            append(sb, "lessons", root.getAsJsonArray("lessons"), tokens, 4, false);
            append(sb, "skills", root.getAsJsonArray("skills"), tokens, 3, false);
            if (sb.length() == 0) {
                return "(nothing learned yet in this world)";
            }
            if (sb.length() > 900) {
                return sb.substring(0, 900) + " ...";
            }
            return sb.toString();
        }
    }

    public void noteSkill(String name, boolean success) {
        if (!AgentConfig.MEMORY_ENABLED.get() || name == null || name.isBlank()) {
            return;
        }
        synchronized (lock) {
            JsonArray skills = root().getAsJsonArray("skills");
            for (JsonElement element : skills) {
                JsonObject entry = element.getAsJsonObject();
                if (entry.get("text").getAsString().equalsIgnoreCase(name)) {
                    String key = success ? "ok" : "fail";
                    entry.addProperty(key, (entry.has(key) ? entry.get(key).getAsInt() : 0) + 1);
                    entry.addProperty("updated", now());
                    dirty = true;
                    return;
                }
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", nextId++);
            entry.addProperty("text", name);
            entry.add("tags", tags("skill"));
            entry.addProperty("confidence", 0.5);
            entry.addProperty("ok", success ? 1 : 0);
            entry.addProperty("fail", success ? 0 : 1);
            entry.addProperty("created", now());
            entry.addProperty("updated", now());
            skills.add(entry);
            trim(skills, MAX_SKILLS, true);
            dirty = true;
        }
    }

    public void flushIfDirty() {
        synchronized (lock) {
            if (!dirty || System.currentTimeMillis() - lastFlush < FLUSH_INTERVAL_MS) {
                return;
            }
            flush();
        }
    }

    public void flush() {
        synchronized (lock) {
            if (!dirty || root == null || file == null) {
                return;
            }
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Set<PosixFilePermission> perms = EnumSet.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
                    Files.setPosixFilePermissions(file, perms);
                } catch (UnsupportedOperationException ignored) {
                    // Windows: default ACLs apply.
                }
                dirty = false;
                lastFlush = System.currentTimeMillis();
            } catch (IOException e) {
                LOGGER.warn("[mcagent] memory flush failed: {}", e.getMessage());
            }
        }
    }

    private JsonObject root() {
        if (root != null) {
            return root;
        }
        worldKey = computeKey();
        file = FMLPaths.CONFIGDIR.get().resolve("mcagent").resolve("memory").resolve(worldKey + ".json");
        root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("world", worldKey);
        root.add("facts", new JsonArray());
        root.add("lessons", new JsonArray());
        root.add("skills", new JsonArray());
        root.add("episodes", new JsonArray());
        if (Files.isRegularFile(file)) {
            try {
                JsonObject loaded = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                if (loaded.has("facts")) {
                    root = loaded;
                }
            } catch (Exception e) {
                LOGGER.warn("[mcagent] memory file is corrupt, starting fresh: {}", e.getMessage());
            }
        }
        ensureArray("facts");
        ensureArray("lessons");
        ensureArray("skills");
        ensureArray("episodes");
        for (JsonArray array : new JsonArray[]{
                root.getAsJsonArray("facts"), root.getAsJsonArray("lessons"),
                root.getAsJsonArray("skills"), root.getAsJsonArray("episodes")}) {
            for (JsonElement element : array) {
                if (element.isJsonObject() && element.getAsJsonObject().has("id")) {
                    nextId = Math.max(nextId, element.getAsJsonObject().get("id").getAsInt() + 1);
                }
            }
        }
        return root;
    }

    private void ensureArray(String name) {
        if (!root.has(name) || !root.get(name).isJsonArray()) {
            root.add(name, new JsonArray());
        }
    }

    private static String computeKey() {
        try {
            return ClientThread.call(() -> {
                Minecraft mc = Minecraft.getInstance();
                ServerData server = mc.getCurrentServer();
                if (server != null && server.ip != null && !server.ip.isBlank()) {
                    return "mp_" + sanitize(server.ip);
                }
                String name = mc.level != null ? mc.level.getLevelData().getLevelName() : "unknown";
                return "sp_" + sanitize(name);
            }, 1500);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String sanitize(String raw) {
        String clean = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
        if (clean.length() > 64) {
            clean = clean.substring(0, 64);
        }
        return clean.isEmpty() ? "unknown" : clean;
    }

    private static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if (Character.isLetterOrDigit(c) || c == '_') {
                current.append(c);
            } else {
                if (current.length() >= 2) {
                    out.add(current.toString());
                }
                current.setLength(0);
            }
        }
        if (current.length() >= 2) {
            out.add(current.toString());
        }
        return out;
    }

    private static double score(JsonObject entry, List<String> tokens) {
        double score = entry.has("confidence") ? entry.get("confidence").getAsDouble() : 0.4;
        String blob = entry.get("text").getAsString().toLowerCase(Locale.ROOT);
        if (entry.has("tags")) {
            blob += " " + entry.get("tags").toString().toLowerCase(Locale.ROOT);
        }
        for (String w : tokens) {
            if (blob.contains(w)) {
                score += 0.8;
            }
        }
        long updated = entry.has("updated") ? entry.get("updated").getAsLong() : 0L;
        double ageDays = Math.max(0, now() - updated) / 86400.0;
        score += Math.max(0, 0.35 - ageDays * 0.01);
        return score;
    }

    private static List<JsonObject> top(JsonArray array, List<String> tokens, int limit) {
        List<JsonObject> ranked = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                ranked.add(element.getAsJsonObject());
            }
        }
        ranked.sort(Comparator.comparingDouble((JsonObject e) -> score(e, tokens)).reversed());
        return ranked.subList(0, Math.min(limit, ranked.size()));
    }

    private static void append(StringBuilder sb, String label, JsonArray array,
                               List<String> tokens, int limit, boolean withConfidence) {
        List<JsonObject> entries = top(array, tokens, limit);
        if (entries.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(label).append(": ");
        for (int i = 0; i < entries.size(); i++) {
            JsonObject entry = entries.get(i);
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(entry.get("text").getAsString());
        }
    }

    private static void collect(JsonArray array, String kind, List<String> tokens, int limit, JsonArray out) {
        for (JsonObject entry : top(array, tokens, limit)) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", kind);
            o.addProperty("text", entry.get("text").getAsString());
            if (entry.has("tags")) {
                o.add("tags", entry.get("tags"));
            }
            if (entry.has("confidence")) {
                o.addProperty("confidence", entry.get("confidence").getAsDouble());
            }
            out.add(o);
        }
    }

    private static void trim(JsonArray array, int max, boolean byScore) {
        while (array.size() > max) {
            int index = 0;
            if (byScore) {
                double worst = Double.MAX_VALUE;
                for (int i = 0; i < array.size(); i++) {
                    JsonObject entry = array.get(i).getAsJsonObject();
                    double value = score(entry, List.of());
                    if (value < worst) {
                        worst = value;
                        index = i;
                    }
                }
            }
            array.remove(index);
        }
    }

    private static JsonArray tags(String raw) {
        JsonArray array = new JsonArray();
        if (raw == null) {
            return array;
        }
        for (String tag : raw.split("[,\\s]+")) {
            if (!tag.isBlank()) {
                array.add(tag.trim());
            }
        }
        return array;
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}

package dev.mcagent.plan;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Checkpoint storage. Plans are written atomically after every node.
 */
public final class PlanStore {
    private final Path dir;

    public PlanStore(Path dir) {
        this.dir = dir;
    }

    public String save(TaskPlan plan) throws IOException {
        Files.createDirectories(dir);
        String id = "plan_" + System.currentTimeMillis();
        Path file = dir.resolve(id + ".json");
        JsonObject root = plan.toJson();
        root.addProperty("savedAt", System.currentTimeMillis());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return id;
    }

    public void saveResult(String id, PlanResult result) throws IOException {
        Path file = dir.resolve(id + ".result.json");
        Files.writeString(file, result.toJson().toString(), StandardCharsets.UTF_8);
    }

    public TaskPlan load(Path file) throws IOException, PlanException {
        return TaskPlan.parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public List<Path> list() throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> out = new ArrayList<>();
            stream.filter(p -> p.getFileName().toString().endsWith(".json")
                            && !p.getFileName().toString().endsWith(".result.json"))
                    .sorted()
                    .forEach(out::add);
            return out;
        }
    }

    public static String slug(String goal) {
        String clean = goal.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
        return clean.length() > 40 ? clean.substring(0, 40) : (clean.isEmpty() ? "plan" : clean);
    }
}

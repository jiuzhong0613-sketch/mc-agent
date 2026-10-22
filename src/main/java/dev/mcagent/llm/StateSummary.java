package dev.mcagent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcagent.ClientThread;
import dev.mcagent.obs.Observation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Token-friendly state. The full JSON stays available on the HTTP bridge.
 */
public final class StateSummary {
    private StateSummary() {
    }

    public static String build() throws Exception {
        return ClientThread.call(StateSummary::render, 3000);
    }

    private static String render() {
        JsonObject full = Observation.snapshot(0, new JsonArray());
        if (!full.has("player")) {
            return "not in game";
        }
        StringBuilder sb = new StringBuilder();
        JsonObject p = full.getAsJsonObject("player");
        sb.append("player: ").append(p.get("pos")).append(" dim=").append(full.get("dimension").getAsString())
                .append(" hp=").append(p.get("health")).append("/").append(p.get("maxHealth"))
                .append(" food=").append(p.get("food"))
                .append(" yaw=").append(p.get("yaw")).append(" pitch=").append(p.get("pitch"))
                .append(" onGround=").append(p.get("onGround"))
                .append(" dead=").append(p.get("dead"))
                .append('|');

        JsonObject inv = full.getAsJsonObject("inventory");
        int selected = inv.get("selected").getAsInt();
        JsonArray slots = inv.getAsJsonArray("slots");
        String hand = "empty";
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonElement element : slots) {
            JsonObject slot = element.getAsJsonObject();
            String item = slot.get("item").getAsString();
            int count = slot.get("count").getAsInt();
            counts.merge(item, count, Integer::sum);
            if (slot.get("slot").getAsInt() == selected) {
                hand = item + " x" + count;
            }
        }
        sb.append("hand=").append(hand).append('|');
        sb.append("inv: ");
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(12)
                .forEach(e -> sb.append(e.getKey()).append(" x").append(e.getValue()).append(", "));
        sb.append('|');

        JsonObject look = full.getAsJsonObject("lookingAt");
        sb.append("looking: ").append(look.toString()).append('|');

        Map<String, Integer> blockCounts = new LinkedHashMap<>();
        Map<String, String> nearest = new LinkedHashMap<>();
        JsonArray blocks = full.getAsJsonArray("blocks");
        for (JsonElement element : blocks) {
            JsonObject block = element.getAsJsonObject();
            String id = block.get("id").getAsString();
            blockCounts.merge(id, 1, Integer::sum);
            nearest.putIfAbsent(id, block.get("x") + "," + block.get("y") + "," + block.get("z"));
        }
        sb.append("blocks: ");
        blockCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> sb.append(e.getKey()).append(" x").append(e.getValue())
                        .append("@").append(nearest.get(e.getKey())).append(", "));
        sb.append('|');

        List<JsonObject> entities = new ArrayList<>();
        for (JsonElement element : full.getAsJsonArray("entities")) {
            entities.add(element.getAsJsonObject());
        }
        entities.sort(Comparator.comparingDouble(e -> e.get("dist").getAsDouble()));
        sb.append("entities: ");
        for (int i = 0; i < Math.min(entities.size(), 8); i++) {
            JsonObject e = entities.get(i);
            sb.append(e.get("type").getAsString()).append('#').append(e.get("id").getAsInt())
                    .append(" d=").append(e.get("dist"));
            if (e.has("health")) {
                sb.append(" hp=").append(e.get("health"));
            }
            sb.append(", ");
        }
        sb.append('|');

        JsonObject menu = full.getAsJsonObject("menu");
        if (menu != null && menu.has("open") && menu.get("open").getAsBoolean()) {
            sb.append("menu: open|");
        }
        JsonArray events = full.getAsJsonArray("events");
        int from = Math.max(0, events.size() - 3);
        sb.append("events: ");
        for (int i = from; i < events.size(); i++) {
            sb.append(events.get(i).getAsJsonObject().get("text").getAsString()).append(" / ");
        }
        return sb.toString();
    }
}

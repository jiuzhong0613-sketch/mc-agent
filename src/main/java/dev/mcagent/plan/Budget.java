package dev.mcagent.plan;

import com.google.gson.JsonObject;

/**
 * Hard limits for one plan. Pure: no Minecraft types.
 */
public final class Budget {
    public final int maxLlmCalls;
    public final int maxActions;
    public final long maxMillis;

    private int llmCalls;
    private int actions;
    private final long startedAt = System.currentTimeMillis();

    public Budget(int maxLlmCalls, int maxActions, long maxMillis) {
        this.maxLlmCalls = maxLlmCalls;
        this.maxActions = maxActions;
        this.maxMillis = maxMillis;
    }

    public static Budget from(JsonObject plan) {
        JsonObject b = plan.has("budget") ? plan.getAsJsonObject("budget") : new JsonObject();
        return new Budget(
                b.has("llmCalls") ? b.get("llmCalls").getAsInt() : 8,
                b.has("actions") ? b.get("actions").getAsInt() : 400,
                b.has("seconds") ? b.get("seconds").getAsLong() * 1000L : 600_000L);
    }

    public boolean spendLlm() {
        if (llmCalls >= maxLlmCalls) {
            return false;
        }
        llmCalls++;
        return true;
    }

    public boolean spendAction() {
        if (actions >= maxActions) {
            return false;
        }
        actions++;
        return true;
    }

    public boolean expired() {
        return System.currentTimeMillis() - startedAt > maxMillis;
    }

    public boolean exhausted() {
        return expired() || actions >= maxActions || llmCalls >= maxLlmCalls;
    }

    public JsonObject snapshot() {
        JsonObject o = new JsonObject();
        o.addProperty("llmCalls", llmCalls);
        o.addProperty("maxLlmCalls", maxLlmCalls);
        o.addProperty("actions", actions);
        o.addProperty("maxActions", maxActions);
        o.addProperty("elapsedMs", System.currentTimeMillis() - startedAt);
        o.addProperty("maxMillis", maxMillis);
        return o;
    }
}

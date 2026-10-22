package dev.mcagent;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client config. Everything is opt-in and bound to loopback by default.
 */
public final class AgentConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.IntValue PORT;
    public static final ForgeConfigSpec.ConfigValue<String> TOKEN;
    public static final ForgeConfigSpec.IntValue OBS_RADIUS;
    public static final ForgeConfigSpec.DoubleValue ACTION_RADIUS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_MOVE;
    public static final ForgeConfigSpec.BooleanValue ALLOW_BREAK;
    public static final ForgeConfigSpec.BooleanValue ALLOW_PLACE;
    public static final ForgeConfigSpec.BooleanValue ALLOW_ATTACK;
    public static final ForgeConfigSpec.BooleanValue ALLOW_CHAT;
    public static final ForgeConfigSpec.IntValue MAX_QUEUE;
    public static final ForgeConfigSpec.ConfigValue<String> BASE_URL;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL;
    public static final ForgeConfigSpec.IntValue MAX_STEPS;
    public static final ForgeConfigSpec.IntValue MAX_QUERIES;
    public static final ForgeConfigSpec.IntValue MAX_TOKENS;
    public static final ForgeConfigSpec.DoubleValue TEMPERATURE;
    public static final ForgeConfigSpec.IntValue REQUEST_TIMEOUT;
    public static final ForgeConfigSpec.DoubleValue TURN_SPEED;
    public static final ForgeConfigSpec.IntValue STEP_DELAY;
    public static final ForgeConfigSpec.BooleanValue MEMORY_ENABLED;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Local HTTP bridge used by an AI agent. Bind address is always 127.0.0.1.").push("bridge");
        ENABLED = b.comment("Start the bridge when a world is loaded.").define("enabled", false);
        PORT = b.comment("Local port.").defineInRange("port", 25580, 1024, 65535);
        TOKEN = b.comment("Optional shared token. If set, requests must send header X-McAgent-Token.")
                .define("token", "");
        MAX_QUEUE = b.comment("Maximum number of queued actions.").defineInRange("maxQueue", 32, 1, 256);
        b.pop();

        b.comment("Observation limits. Smaller is cheaper and friendlier to prompts.").push("observation");
        OBS_RADIUS = b.comment("Radius (blocks) for the nearby block scan.").defineInRange("radius", 5, 1, 12);
        b.pop();

        b.comment("Hosted LLM API. Local and self-hosted endpoints are rejected.")
                .push("llm");
        BASE_URL = b.comment("OpenAI-compatible base URL. Must be https and non-local.")
                .define("baseUrl", "https://api.openai.com/v1");
        MODEL = b.comment("Model name.").define("model", "gpt-4o-mini");
        MAX_STEPS = b.comment("Hard cap on model calls per run.").defineInRange("maxSteps", 20, 1, 200);
        MAX_QUERIES = b.comment("Read-only queries allowed per model step.").defineInRange("maxQueriesPerStep", 6, 1, 32);
        MAX_TOKENS = b.comment("max_tokens per request.").defineInRange("maxTokens", 700, 64, 8192);
        TEMPERATURE = b.comment("Sampling temperature.").defineInRange("temperature", 0.2, 0.0, 2.0);
        REQUEST_TIMEOUT = b.comment("Per-request timeout in seconds.").defineInRange("timeoutSeconds", 60, 5, 300);
        b.pop();

        b.comment("Human-like pacing. Lower turn speed looks less robotic and reduces instant 180s.").push("agent");
        TURN_SPEED = b.comment("Max camera rotation per tick, in degrees.").defineInRange("turnSpeed", 18.0, 2.0, 90.0);
        STEP_DELAY = b.comment("Pause between model steps, in milliseconds.").defineInRange("stepDelayMs", 250, 0, 3000);
        MEMORY_ENABLED = b.comment("Persist facts and lessons per world.").define("memory", true);
        b.pop();

        b.comment("Action permissions. Turn off anything you do not want an agent to do.").push("permissions");
        ACTION_RADIUS = b.comment("Maximum distance for block/entity interactions.").defineInRange("reach", 4.5, 1.0, 6.0);
        ALLOW_MOVE = b.define("allowMove", true);
        ALLOW_BREAK = b.define("allowBreak", true);
        ALLOW_PLACE = b.define("allowPlace", true);
        ALLOW_ATTACK = b.define("allowAttack", false);
        ALLOW_CHAT = b.define("allowChat", false);
        b.pop();

        SPEC = b.build();
    }

    private AgentConfig() {
    }
}

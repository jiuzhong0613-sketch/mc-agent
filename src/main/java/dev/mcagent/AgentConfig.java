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

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Local HTTP bridge used by an AI agent. Bind address is always 127.0.0.1.").push("bridge");
        ENABLED = b.comment("Start the bridge when a world is loaded.").define("enabled", true);
        PORT = b.comment("Local port.").defineInRange("port", 25580, 1024, 65535);
        TOKEN = b.comment("Optional shared token. If set, requests must send header X-McAgent-Token.")
                .define("token", "");
        MAX_QUEUE = b.comment("Maximum number of queued actions.").defineInRange("maxQueue", 32, 1, 256);
        b.pop();

        b.comment("Observation limits. Smaller is cheaper and friendlier to prompts.").push("observation");
        OBS_RADIUS = b.comment("Radius (blocks) for the nearby block scan.").defineInRange("radius", 5, 1, 12);
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

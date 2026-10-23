package dev.mcagent.client;

import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class AgentKeys {
    public static final KeyMapping OPEN = new KeyMapping(
            "key.mcagent.open", GLFW.GLFW_KEY_K, "key.categories.mcagent");

    private AgentKeys() {
    }
}

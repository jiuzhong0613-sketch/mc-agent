package dev.mcagent.act;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

public interface Action {
    String type();

    /** Called once per client tick. Return a result object when finished, or null to keep running. */
    JsonObject tick(Minecraft mc) throws Exception;

    /** Release keys, stop digging, and otherwise leave the player in a neutral state. */
    default void cancel(Minecraft mc) {
    }

    default int timeoutTicks() {
        return 400;
    }
}

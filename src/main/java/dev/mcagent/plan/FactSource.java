package dev.mcagent.plan;

import com.google.gson.JsonObject;

/**
 * Read-only facts the verifier is allowed to trust. Implemented by the client;
 * faked in unit tests.
 */
public interface FactSource {
    int inventoryCount(String item);

    String blockAt(int x, int y, int z);

    int blocksCount(String blockId, int radius);

    boolean positionNear(double x, double y, double z, double tolerance);

    int entityCount(String type, int radius);

    JsonObject query(String name, JsonObject args) throws Exception;
}

package dev.mcagent.plan;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

final class FakeFacts implements FactSource {
    int inventory;
    String block = "minecraft:air";
    int blocks;
    boolean near;
    int entities;
    JsonObject queryResult = new JsonObject();
    String lastName;
    JsonObject lastArgs;

    @Override
    public int inventoryCount(String item) {
        return inventory;
    }

    @Override
    public String blockAt(int x, int y, int z) {
        return block;
    }

    @Override
    public int blocksCount(String blockId, int radius) {
        return blocks;
    }

    @Override
    public boolean positionNear(double x, double y, double z, double tolerance) {
        return near;
    }

    @Override
    public int entityCount(String type, int radius) {
        return entities;
    }

    @Override
    public JsonObject query(String name, JsonObject args) {
        lastName = name;
        lastArgs = args;
        return queryResult;
    }
}

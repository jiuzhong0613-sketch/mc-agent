package dev.mcagent.plan;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifierTest {
    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void inventoryCount() {
        FakeFacts facts = new FakeFacts();
        facts.inventory = 3;
        assertTrue(Verifier.verify(json("{\"type\":\"inventory_count\",\"item\":\"minecraft:oak_log\",\"min\":3}"), facts, null).ok);
        assertFalse(Verifier.verify(json("{\"type\":\"inventory_count\",\"item\":\"minecraft:oak_log\",\"min\":4}"), facts, null).ok);
    }

    @Test
    void blockAt() {
        FakeFacts facts = new FakeFacts();
        facts.block = "minecraft:stone";
        assertTrue(Verifier.verify(json("{\"type\":\"block_at\",\"x\":1,\"y\":2,\"z\":3,\"id\":\"minecraft:stone\"}"), facts, null).ok);
    }

    @Test
    void positionNear() {
        FakeFacts facts = new FakeFacts();
        facts.near = true;
        assertTrue(Verifier.verify(json("{\"type\":\"position_near\",\"x\":0,\"y\":0,\"z\":0,\"tolerance\":2}"), facts, null).ok);
    }

    @Test
    void allAnyNot() {
        FakeFacts facts = new FakeFacts();
        facts.inventory = 5;
        assertTrue(Verifier.verify(json("""
                {"type":"all","of":[
                  {"type":"inventory_count","item":"x","min":5},
                  {"type":"not","of":{"type":"inventory_count","item":"x","min":6}}
                ]}
                """), facts, null).ok);
    }

    @Test
    void queryTrueWithPath() {
        FakeFacts facts = new FakeFacts();
        facts.queryResult = json("{\"types\":[{\"count\":4}]}");
        assertTrue(Verifier.verify(json("{\"type\":\"query_true\",\"tool\":\"query_scan\",\"args\":{},\"path\":\"types.0.count\",\"min\":3}"), facts, null).ok);
        assertFalse(Verifier.verify(json("{\"type\":\"query_true\",\"tool\":\"query_scan\",\"args\":{},\"path\":\"types.0.count\",\"min\":5}"), facts, null).ok);
    }

    @Test
    void actionOkReadsResult() {
        assertTrue(Verifier.verify(json("{\"type\":\"action_ok\"}"), new FakeFacts(),
                json("{\"ok\":true}")).ok);
        assertFalse(Verifier.verify(json("{\"type\":\"action_ok\"}"), new FakeFacts(),
                json("{\"ok\":false}")).ok);
    }
}

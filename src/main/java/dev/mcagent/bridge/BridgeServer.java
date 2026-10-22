package dev.mcagent.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mcagent.AgentConfig;
import dev.mcagent.AgentRuntime;
import dev.mcagent.query.QueryRegistry;
import net.minecraft.SharedConstants;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;

/**
 * Loopback-only HTTP bridge. JSON in, JSON out.
 *
 * No auth unless a token is configured; the socket never leaves 127.0.0.1.
 */
public final class BridgeServer {
    public static final String PROTOCOL = "mc-agent/1";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_BODY = 64 * 1024;
    private static final JsonArray CAPABILITIES = new JsonArray();

    static {
        for (String capability : new String[]{
                "obs.player.v1", "obs.inventory.v1", "obs.blocks.v1", "obs.entities.v1",
                "act.look.v1", "act.move.v1", "act.walk_to.v1", "act.mine.v1", "act.place.v1",
                "act.use.v1", "act.attack.v1", "act.chat.v1", "act.select_slot.v1", "act.drop.v1",
                "query.block.v1", "query.scan.v1", "query.entities.v1", "query.inventory.v1",
                "query.raycast.v1", "query.can_stand.v1", "query.surface.v1", "query.route.v1", "query.env.v1"
        }) {
            CAPABILITIES.add(capability);
        }
    }

    @FunctionalInterface
    private interface Handler {
        Reply handle(HttpExchange exchange) throws Exception;
    }

    private record Reply(int status, JsonObject body) {
    }

    private final HttpServer server;

    public BridgeServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/health", exchange -> dispatch(exchange, "GET", this::health));
        server.createContext("/state", exchange -> dispatch(exchange, "GET", this::state));
        server.createContext("/result", exchange -> dispatch(exchange, "GET", this::result));
        server.createContext("/act", exchange -> dispatch(exchange, "POST", this::act));
        server.createContext("/cancel", exchange -> dispatch(exchange, "POST", this::cancel));
        server.createContext("/query", exchange -> dispatch(exchange, "POST", this::query));
        server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "mcagent-bridge");
            thread.setDaemon(true);
            return thread;
        }));
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    private void dispatch(HttpExchange exchange, String method, Handler handler) {
        try {
            if (!exchange.getRequestMethod().equalsIgnoreCase(method)) {
                write(exchange, 405, error("method not allowed"));
                return;
            }
            if (!tokenOk(exchange)) {
                write(exchange, 401, error("bad token"));
                return;
            }
            Reply reply = handler.handle(exchange);
            write(exchange, reply.status(), reply.body());
        } catch (IllegalArgumentException e) {
            write(exchange, 400, error(e.getMessage()));
        } catch (Exception e) {
            LOGGER.warn("[mcagent] request failed", e);
            write(exchange, 500, error(e.getClass().getSimpleName() + ": " + e.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private Reply health(HttpExchange exchange) {
        JsonObject out = new JsonObject();
        out.addProperty("status", "ok");
        out.addProperty("protocol", PROTOCOL);
        out.addProperty("mod", "mcagent");
        out.addProperty("mcVersion", SharedConstants.getCurrentVersion().getName());
        out.addProperty("running", AgentRuntime.get().running());
        out.add("capabilities", CAPABILITIES);
        return new Reply(200, out);
    }

    private Reply state(HttpExchange exchange) throws Exception {
        JsonObject out = AgentRuntime.get().state();
        out.add("capabilities", CAPABILITIES);
        JsonElement last = AgentRuntime.get().lastResult();
        if (last != null) {
            out.add("lastResult", last);
        }
        return new Reply(200, out);
    }

    private Reply result(HttpExchange exchange) {
        JsonElement last = AgentRuntime.get().lastResult();
        JsonObject out = new JsonObject();
        out.addProperty("ok", last != null);
        if (last != null) {
            out.add("result", last);
        }
        return new Reply(200, out);
    }

    private Reply act(HttpExchange exchange) throws Exception {
        JsonObject request = JsonParser.parseString(readBody(exchange)).getAsJsonObject();
        JsonObject out = AgentRuntime.get().submit(request);
        boolean accepted = out.has("ok") && out.get("ok").getAsBoolean();
        return new Reply(accepted ? 202 : 409, out);
    }

    private Reply query(HttpExchange exchange) throws Exception {
        JsonObject request = JsonParser.parseString(readBody(exchange)).getAsJsonObject();
        String name = request.has("name") ? request.get("name").getAsString() : "";
        JsonObject args = request.has("args") && request.get("args").isJsonObject()
                ? request.getAsJsonObject("args") : new JsonObject();
        return new Reply(200, QueryRegistry.run(name, args, false));
    }

    private Reply cancel(HttpExchange exchange) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("type", "stop");
        return new Reply(200, AgentRuntime.get().submit(request));
    }

    private boolean tokenOk(HttpExchange exchange) {
        String expected = AgentConfig.TOKEN.get();
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        String actual = exchange.getRequestHeaders().getFirst("X-McAgent-Token");
        return actual != null && MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BODY) {
                    throw new IllegalArgumentException("body too large");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static JsonObject error(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("error", message == null ? "unknown error" : message);
        return out;
    }

    private static void write(HttpExchange exchange, int status, JsonObject body) {
        try {
            byte[] bytes = Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException e) {
            LOGGER.debug("[mcagent] response failed: {}", e.getMessage());
        }
    }
}

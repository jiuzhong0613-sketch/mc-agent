package dev.mcagent.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;

public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }

    public static JsonArray vec(double x, double y, double z) {
        JsonArray a = new JsonArray(3);
        a.add(round(x));
        a.add(round(y));
        a.add(round(z));
        return a;
    }

    public static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}

package com.jessetech.recipebalancer.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jessetech.recipebalancer.core.io.ScenarioJson;
import com.jessetech.recipebalancer.core.model.Scenario;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 测试夹具读取：场景输入与 Python 金标准都从 test resources 加载。 */
final class TestSupport {

    static final String[] SCENARIOS = {
            "simple", "autocatalytic", "multi_product", "ebf_cycle", "deficit",
            "multi_loop", "dual_line", "mek_fission", "uu"
    };

    private TestSupport() {
    }

    static Scenario scenario(String name) {
        return ScenarioJson.load(read("/scenarios/" + name + ".json"));
    }

    static JsonObject golden(String name) {
        return JsonParser.parseString(read("/golden/" + name + ".json")).getAsJsonObject();
    }

    static String read(String resource) {
        try (InputStream in = TestSupport.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("缺少测试资源: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

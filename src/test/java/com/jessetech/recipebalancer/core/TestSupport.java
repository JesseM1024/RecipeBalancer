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
        // 与运行期 Gson 2.8.0 对齐：用实例 parse，而不是 2.8.6+ 的静态 parseString
        return new JsonParser().parse(read("/golden/" + name + ".json")).getAsJsonObject();
    }

    static String read(String resource) {
        try (InputStream in = TestSupport.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("缺少测试资源: " + resource);
            }
            // 1.16.5 线是 Java 8：没有 InputStream.readAllBytes()
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}

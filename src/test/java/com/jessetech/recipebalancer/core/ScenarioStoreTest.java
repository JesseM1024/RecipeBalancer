package com.jessetech.recipebalancer.core;

import com.jessetech.recipebalancer.core.io.ScenarioStore;
import com.jessetech.recipebalancer.core.model.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 场景文件仓库：保存/载入/枚举/删除 + 文件名清洗（防目录穿越）。 */
class ScenarioStoreTest {

    @TempDir
    Path directory;

    @Test
    void saveLoadListDelete() {
        ScenarioStore store = new ScenarioStore(directory);
        Scenario scenario = TestSupport.scenario("ebf_cycle");

        Path saved = store.save("默认场景", scenario);
        assertTrue(Files.isRegularFile(saved));
        assertEquals("默认场景.json", saved.getFileName().toString());
        assertTrue(store.exists("默认场景"));
        assertEquals(List.of("默认场景"), store.names());

        Scenario loaded = store.load("默认场景");
        assertEquals(scenario.recipes(), loaded.recipes());
        assertEquals(scenario.targetProducts(), loaded.targetProducts());
        assertEquals(scenario.options(), loaded.options());

        assertTrue(store.delete("默认场景"));
        assertFalse(store.exists("默认场景"));
        assertEquals(List.of(), store.names());
    }

    @Test
    void namesAreSorted() {
        ScenarioStore store = new ScenarioStore(directory);
        Scenario scenario = TestSupport.scenario("simple");
        store.save("b", scenario);
        store.save("a", scenario);
        store.save("c", scenario);

        assertEquals(List.of("a", "b", "c"), store.names());
    }

    @Test
    void sanitizeRejectsTraversalAndReservedNames() {
        assertEquals("default", ScenarioStore.sanitize(null));
        assertEquals("default", ScenarioStore.sanitize("   "));
        assertEquals("default", ScenarioStore.sanitize("../../"));
        assertEquals("evil", ScenarioStore.sanitize("../../evil"));
        assertEquals("a_b-c 1", ScenarioStore.sanitize("a_b-c 1"));
        assertEquals("_CON", ScenarioStore.sanitize("CON"));
        assertEquals("hidden", ScenarioStore.sanitize("..hidden"));
    }

    @Test
    void loadMissingThrows() {
        ScenarioStore store = new ScenarioStore(directory);
        assertThrows(IllegalArgumentException.class, () -> store.load("nope"));
    }

    @Test
    void saveCreatesDirectoryOnDemand() {
        Path nested = directory.resolve("deep").resolve("scenarios");
        ScenarioStore store = new ScenarioStore(nested);
        store.save("x", TestSupport.scenario("simple"));

        assertTrue(Files.isDirectory(nested));
        assertTrue(store.exists("x"));
    }
}

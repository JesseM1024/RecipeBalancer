package com.jessetech.recipebalancer.core;

import com.jessetech.recipebalancer.core.util.J8;

import com.jessetech.recipebalancer.core.io.ScenarioJson;
import com.jessetech.recipebalancer.core.model.Objective;
import com.jessetech.recipebalancer.core.model.Recipe;
import com.jessetech.recipebalancer.core.model.Scenario;
import com.jessetech.recipebalancer.core.model.ScenarioDraft;
import com.jessetech.recipebalancer.core.model.SolveOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 可编辑草稿的状态迁移（删除配方时的禁用下标平移是本类的核心不变量）。 */
class ScenarioDraftTest {

    private static Recipe recipe(String equipment) {
        return new Recipe(J8.map("a", 1.0), J8.map("b", 1.0), 1.0, equipment);
    }

    @Test
    void removeRecipeShiftsDisabledIndexes() {
        ScenarioDraft draft = ScenarioDraft.blank();
        draft.addRecipe(recipe("A"));
        draft.addRecipe(recipe("B"));
        draft.addRecipe(recipe("C"));

        draft.toggleRecipeEnabled(2);
        draft.toggleRecipeEnabled(1);
        draft.removeRecipe(0);

        assertEquals(J8.set(0, 1), draft.options().disabledRecipes());
        assertEquals(J8.list("B", "C"),
                draft.recipes().stream().map(Recipe::equType).collect(java.util.stream.Collectors.toList()));
        assertFalse(draft.isRecipeEnabled(0));
        assertFalse(draft.isRecipeEnabled(1));
    }

    @Test
    void removingDisabledRecipeDropsIt() {
        ScenarioDraft draft = ScenarioDraft.blank();
        draft.addRecipe(recipe("A"));
        draft.addRecipe(recipe("B"));
        draft.toggleRecipeEnabled(0);

        draft.removeRecipe(0);

        assertEquals(J8.set(), draft.options().disabledRecipes());
        assertEquals(1, draft.recipeCount());
    }

    @Test
    void targetsAreValidatedAndOrdered() {
        ScenarioDraft draft = ScenarioDraft.blank();
        draft.putTarget("铁锭", 2.0);
        draft.putTarget("铜锭", 1.5);

        assertEquals(J8.list("铁锭", "铜锭"), J8.listCopy(draft.targets().keySet()));
        // 草稿内部每 tick；写出场景 JSON 时恢复为每秒
        assertEquals(2.0, draft.targets().get("铁锭"), 1e-12);
        assertEquals(40.0, draft.toScenario().targetProducts().get("铁锭"), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> draft.putTarget(" ", 1.0));
        assertThrows(IllegalArgumentException.class, () -> draft.putTarget("铁锭", 0.0));
        assertThrows(IllegalArgumentException.class, () -> draft.putTarget("铁锭", Double.NaN));

        draft.removeTarget("铁锭");
        assertEquals(J8.set("铜锭"), draft.targets().keySet());
    }

    @Test
    void togglesRoundTripThroughOptions() {
        ScenarioDraft draft = ScenarioDraft.blank();
        Objective original = draft.options().objective();
        draft.toggleObjective();
        assertEquals(original == Objective.MACHINE ? Objective.RECIPES : Objective.MACHINE,
                draft.options().objective());

        boolean raw = draft.options().autoDetectRaw();
        draft.toggleAutoDetectRaw();
        assertEquals(!raw, draft.options().autoDetectRaw());

        boolean bootstrap = draft.options().autoBootstrap();
        draft.toggleAutoBootstrap();
        assertEquals(!bootstrap, draft.options().autoBootstrap());
    }

    @Test
    void scenarioSurvivesDumpAndReload() {
        Scenario source = TestSupport.scenario("simple");
        ScenarioDraft draft = ScenarioDraft.from(source);

        Scenario roundTrip = ScenarioJson.load(ScenarioJson.dump(draft.toScenario()));

        assertEquals(source.recipes(), roundTrip.recipes());
        assertEquals(source.targetProducts(), roundTrip.targetProducts());
        assertEquals(source.options(), roundTrip.options());
    }

    @Test
    void clearRestoresDefaults() {
        ScenarioDraft draft = ScenarioDraft.from(TestSupport.scenario("simple"));
        draft.setOptions(new SolveOptions(J8.set("x"), J8.map("x", 1.0), J8.set(0), J8.set("y"),
                Objective.RECIPES, false, false, true));

        draft.clear();

        assertEquals(0, draft.recipeCount());
        assertEquals(0, draft.targets().size());
        assertEquals(SolveOptions.defaults(), draft.options());
    }
}

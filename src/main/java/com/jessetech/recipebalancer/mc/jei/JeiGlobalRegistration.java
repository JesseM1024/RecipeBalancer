package com.jessetech.recipebalancer.mc.jei;

import com.jessetech.recipebalancer.RecipeBalancerMod;
import com.jessetech.recipebalancer.mc.screen.BalancerScreen;
import net.minecraft.client.Minecraft;

import java.awt.Rectangle;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;

/**
 * {@code IGlobalGuiHandler} 的注册（JEI 4.14+ 才有）。
 *
 * <p>为什么用动态代理而不是直接实现接口：编译基线是 4.8.5.160，该版本<b>没有</b>
 * {@code IGlobalGuiHandler}，直接 import 编不过；而一旦加载 4.14 才有的接口，
 * 低版本实例会 {@code NoClassDefFoundError}。这里在运行期探测到接口存在后，
 * 用 {@link Proxy} 造一个实现，再反射调用 {@code IModRegistry.addGlobalGuiHandlers(...)}。
 *
 * <p>功能本身是「兜底」：一级界面已有 {@link RbContainerGuiHandler}（4.x 全版本可用），
 * 本类只是在某些裁剪了容器侧回调的构建上让 R/U/A 仍然可用。
 */
public final class JeiGlobalRegistration {

    private static final String HANDLER = "mezz.jei.api.gui.IGlobalGuiHandler";

    private JeiGlobalRegistration() {
    }

    public static void register(Object registry) {
        Class<?> iface = JeiReflect.type(HANDLER);
        if (iface == null) {
            return;
        }
        Object handler = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                new Handler());
        // 可变参数在字节码里就是数组：必须把代理**放进**数组再传，否则 JEI 收到 [null] 会 NPE
        Object array = java.lang.reflect.Array.newInstance(iface, 1);
        java.lang.reflect.Array.set(array, 0, handler);
        try {
            Method add = registry.getClass().getMethod("addGlobalGuiHandlers", array.getClass());
            add.setAccessible(true);
            add.invoke(registry, array);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        RecipeBalancerMod.LOGGER.info("[RecipeBalancer] JEI 全局悬停兜底：已注册（4.14+）");
    }

    /** 代理实现：只处理悬停查询，额外区域交给容器处理器。 */
    private static final class Handler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("getIngredientUnderMouse".equals(name) && args != null && args.length == 2) {
                int mouseX = ((Number) args[0]).intValue();
                int mouseY = ((Number) args[1]).intValue();
                return hovered(mouseX, mouseY);
            }
            if ("getGuiExtraAreas".equals(name)) {
                return new ArrayList<Rectangle>();
            }
            if ("toString".equals(name)) {
                return "RecipeBalancerGlobalGuiHandler";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name) && args != null && args.length == 1) {
                return proxy == args[0];
            }
            // 其它新增的 default 方法：返回 null（JEI 对 null 有兜底）
            return null;
        }

        private Object hovered(int mouseX, int mouseY) {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (!(minecraft.currentScreen instanceof BalancerScreen)) {
                return null;
            }
            BalancerScreen screen = (BalancerScreen) minecraft.currentScreen;
            BalancerScreen.HoveredKey hovered = screen.jeiHoveredKey(mouseX, mouseY);
            return hovered == null ? null : JeiHoverKeys.ingredient(hovered.key());
        }

        @SuppressWarnings("unused")
        private Collection<Rectangle> noAreas() {
            return new ArrayList<>();
        }
    }
}

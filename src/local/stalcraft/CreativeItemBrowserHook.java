package local.stalcraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

/** Small reflection-only bridge for the bundled NEI 1.6.4 classes. */
public final class CreativeItemBrowserHook {
    private CreativeItemBrowserHook() {
    }

    /**
     * Called after NEIClientConfig.loadWorld(String) has initialized NEI and
     * installed the current world config. This clears both mod/API exclusions
     * and saved hidden entries, then asks NEI to rebuild the visible item list.
     */
    @SuppressWarnings("unchecked")
    public static boolean revealAllRegisteredItems() {
        try {
            ClassLoader loader = CreativeItemBrowserHook.class.getClassLoader();
            Class<?> itemInfo = Class.forName("codechicken.nei.api.ItemInfo", false, loader);
            Set<Integer> excluded = (Set<Integer>) itemInfo.getField("excludeIds").get(null);
            excluded.clear();

            Class<?> config = Class.forName("codechicken.nei.NEIClientConfig", false, loader);
            Object visibility = config.getField("vishash").get(null);
            if (visibility != null) {
                Field hiddenField = visibility.getClass().getField("hiddenitems");
                ((Map<?, ?>) hiddenField.get(visibility)).clear();
                visibility.getClass().getMethod("save").invoke(visibility);
            }

            Class<?> itemList = Class.forName("codechicken.nei.ItemList", false, loader);
            itemList.getMethod("loadItems").invoke(null);
            return true;
        } catch (ReflectiveOperationException | LinkageError exception) {
            return false;
        }
    }

    /**
     * True only when this server player is in native Creative mode. Unknown
     * reflection layouts fail closed. In this 1.6.4 build the verified path is
     * player.field_71134_c._b(), also used by NEIServerUtils.getCreativeMode.
     */
    public static boolean isCreativePlayer(Object player) {
        if (player == null) {
            return false;
        }
        try {
            Object manager = player.getClass().getField("field_71134_c").get(player);
            if (manager == null) {
                return false;
            }
            Object creative = manager.getClass().getMethod("_b").invoke(manager);
            return Boolean.TRUE.equals(creative);
        } catch (ReflectiveOperationException | LinkageError exception) {
            return false;
        }
    }

    /**
     * Entry guard for NEIServerConfig.authenticatePacket(player, packet).
     * NEI packet types 1 (give item) and 5 (set slot) are creative-only; all
     * other packet types keep their original NEI permission checks.
     */
    public static boolean rejectNonCreativeItemPacket(Object player, Object packet) {
        if (packet == null) {
            return true;
        }
        try {
            Method getType = packet.getClass().getMethod("getType");
            Object result = getType.invoke(packet);
            if (!(result instanceof Number)) {
                return true;
            }
            int type = ((Number) result).intValue();
            return (type == 1 || type == 5) && !isCreativePlayer(player);
        } catch (ReflectiveOperationException | LinkageError exception) {
            return true;
        }
    }
}

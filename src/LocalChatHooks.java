import java.lang.reflect.Field;
import net.minecraft.client.qlfw;
import local.stalcraft.OfflineHook;

/** Preserve the supplied integrated chat path and enable remote Packet3Chat. */
public final class LocalChatHooks {
    public static void sendLocalChat(Object screen) {
        qlfw client = qlfw._I();
        if (client._H != null) {
            OfflineHook.sendLocalChat(screen);
            return;
        }
        try {
            Field field = screen.getClass().getDeclaredField("messageField");
            field.setAccessible(true);
            Object input = field.get(screen);
            String message = ((String) input.getClass().getMethod("getText").invoke(input)).trim();
            if (!message.isEmpty() && client._t != null) client._t._f(message);
            screen.getClass().getMethod("closeScreen").invoke(screen);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Remote chat forwarding", failure);
        }
    }
}

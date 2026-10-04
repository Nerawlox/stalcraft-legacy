import net.minecraft.client.qlfw;
import local.stalcraft.OfflineHook;

/** Tick hook for bounded diagnostic clients; remote clients avoid integrated-world edits. */
public final class LocalClientProbe {
    private static boolean itemCatalogReported;
    private static long started;
    private static long lastReport;
    private static long reloadRequested;
    private static long shotRequested;
    private static boolean wrappedReload;
    private static boolean respawnRequested;
    private static boolean chatSent;
    private static boolean chatReceived;

    public static String serverAddress() {
        qlfw client = qlfw._I();
        if (client != null && client._H != null) return "memory";
        oiuh handler = client == null ? null : client._D();
        if (handler != null && handler.field_72555_g != null) {
            return String.valueOf(handler.field_72555_g.func_74430_c());
        }
        return "remote";
    }

    public static void tick(Object value) {
        qlfw client = (qlfw) value;
        if (client._H != null) OfflineHook.tick(value);
        if (!Boolean.getBoolean("reconstruction.clientProbe")) return;
        if (!itemCatalogReported && client._r != null && !codechicken.nei.ItemList.items.isEmpty()) {
            itemCatalogReported = true;
            System.out.println("[RECONSTRUCTION ITEM CATALOG] entries=" + codechicken.nei.ItemList.items.size()
                + " excluded=" + codechicken.nei.api.ItemInfo.excludeIds.size());
        }
        LocalCombatClientProbe.tick(client);
        long now = System.currentTimeMillis();
        if (started == 0) started = now;
        if (Boolean.getBoolean("reconstruction.chatProbe") && client._t != null && client._r != null) {
            String marker = "RECON_CHAT_" + client._T()._a();
            if (!chatSent && now - started > 10000 && client._r.field_73010_i.size() >= 2) {
                chatSent = true;
                try {
                    Object chat = mods.chat.ChatMod.instance.chatHud.getChat();
                    chat.getClass().getField("defaultText").set(chat, marker);
                    mods.chat.ChatMod.instance.chatHud.displayActiveChat();
                    java.lang.reflect.Field textField = chat.getClass().getDeclaredField("messageField");
                    textField.setAccessible(true);
                    Object input = textField.get(chat);
                    input.getClass().getMethod("setText", String.class).invoke(input, marker);
                    java.lang.reflect.Method send = chat.getClass().getDeclaredMethod("sendMessage");
                    send.setAccessible(true);
                    send.invoke(chat);
                    System.out.println("[RECONSTRUCTION CHAT] Sent through actual GUI: " + marker);
                } catch (ReflectiveOperationException failure) {
                    System.out.println("[RECONSTRUCTION CHAT] GUI probe failed: " + failure);
                }
            }
            if (!chatReceived) {
                String peer = "RECON_CHAT_" + ("ProbeAlpha".equals(client._T()._a()) ? "ProbeBeta" : "ProbeAlpha");
                for (Object message : mods.chat.client.MessageStorage.RECEIVED.all()) {
                    try {
                        if (String.valueOf(message.getClass().getField("_e").get(message)).contains(peer)) {
                            chatReceived = true;
                            System.out.println("[RECONSTRUCTION CHAT] Received peer message in actual chat storage: " + peer);
                            break;
                        }
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException("Chat storage probe", failure);
                    }
                }
            }
        }
        voib held = client._t == null ? null : client._t.func_70694_bm();
        if (Boolean.getBoolean("reconstruction.gameplayProbe") && client._t != null && client._t._f != null
            && client._t.field_70173_aa > 40 && client._t.func_110143_aJ() <= 0 && !respawnRequested) {
            respawnRequested = true;
            client._t._f.func_72552_c(new vmow(1));
            System.out.println("[RECONSTRUCTION GAMEPLAY] Sent native respawn button request");
        }
        if (Boolean.getBoolean("reconstruction.gameplayProbe") && client._t != null && "ProbeAlpha".equals(client._T()._a())
            && client._t.func_110143_aJ() > 0 && held != null && held._a() instanceof grkk) {
            if (reloadRequested == 0 && ognf._a(client._t, ((grkk) held._a())._Y(held)) > 0) {
                client._a((ywla) null);
                new cfal(-1).sendToServer();
                reloadRequested = now;
                System.out.println("[RECONSTRUCTION GAMEPLAY] Sent native automatic reload request");
            } else if (!Boolean.getBoolean("reconstruction.combatProbe") && reloadRequested != 0 && shotRequested == 0 && now - reloadRequested > 4000 && grkk._H(held) > 0) {
                new rakn().sendToServer();
                shotRequested = now;
                System.out.println("[RECONSTRUCTION GAMEPLAY] Sent native shoot request");
            } else if (!wrappedReload && shotRequested != 0 && now - shotRequested > 4000) {
                new gloomyfolken.mods.bundle.amxp(new cfal(-1)).sendToServer();
                wrappedReload = true;
                System.out.println("[RECONSTRUCTION GAMEPLAY] Sent backend-wrapped automatic reload request");
            }
        }
        if (now - lastReport >= 2000) {
            lastReport = now;
            System.out.println("[RECONSTRUCTION CLIENT] player=" + client._T()._a()
                + " world=" + (client._r != null) + " entity=" + (client._t != null)
                + " screen=" + (client._B == null ? "none" : client._B.getClass().getName())
                + (client._t == null ? "" : " id=" + client._t.field_70157_k
                    + " hp=" + client._t.func_110143_aJ() + " x=" + client._t.field_70165_t + " y=" + client._t.field_70163_u + " z=" + client._t.field_70161_v)
                + (client._r == null ? "" : " players=" + client._r.field_73010_i.size()
                    + " testBlock=" + client._r.func_72798_a(445, 4, -625))
                + (held != null && held._a() instanceof grkk ? " weapon=" + held._d + " bullets=" + grkk._H(held)
                    + " ammo=" + grkk._I(held) + " reserve=" + ognf._b(client._t, grkk._I(held)) : ""));
        }
        if (now - started >= Integer.getInteger("reconstruction.clientSeconds", 100) * 1000L) {
            System.out.println("[RECONSTRUCTION CLIENT] Requesting own client shutdown");
            client._r();
        }
    }
}

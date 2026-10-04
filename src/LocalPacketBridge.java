import gloomyfolken.bundle.common.core.dfaj;
import gloomyfolken.core.network.AbstractPacket;
import gloomyfolken.core.network.PacketRegistry;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** A local backend for the supplied offline handler; preserves the packet sender. */
public final class LocalPacketBridge {
    private static final Map<String, Integer> unsupported = new HashMap<String, Integer>();
    private static long lastSummary;

    public static void handle(dfaj packet, swfs sender) {
        if (packet == null || sender == null) return;
        try {
            int depth = 0;
            while (packet instanceof gloomyfolken.mods.bundle.amxp) {
                if (++depth > 4) throw new IOException("Backend wrapper nesting exceeds local limit");
                byte[] bytes = ((gloomyfolken.mods.bundle.amxp) packet)._a;
                if (bytes == null || bytes.length < 2 || bytes.length > 1048576) {
                    throw new IOException("Invalid backend wrapper payload length");
                }
                DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
                AbstractPacket inner = PacketRegistry.readNextPacket(input);
                if (!(inner instanceof dfaj) || input.available() != 0) {
                    throw new IOException("Invalid backend packet payload");
                }
                packet = (dfaj) inner;
                if (Boolean.getBoolean("reconstruction.serverProbe")) {
                    System.out.println("[RECONSTRUCTION PACKET] unwrapped=" + packet.getClass().getName());
                }
            }
            if (!supported(packet)) {
                recordUnsupported(packet);
                return;
            }
            // The real client uses -1 for automatic compatible-ammo selection.
            if (packet instanceof cfal && ((cfal) packet)._a == -1) ((cfal) packet)._a = 0;
            if (Boolean.getBoolean("reconstruction.gameplayProbe")) {
                jlas player = sender.getPlayer();
                voib held = player == null ? null : player.func_70694_bm();
                System.out.println("[RECONSTRUCTION PACKET] accepted=" + packet.getClass().getName()
                    + " sender=" + (player == null ? "none" : player.func_70005_c_())
                    + " beforeBullets=" + (held != null && held._a() instanceof grkk ? grkk._H(held) : -1));
            }
            // The build tool redirects this call to the retained original method.
            ServerPacketHandler.handle(packet, sender);
        } catch (IOException failure) {
            System.err.println("[RECONSTRUCTION PACKET] Rejected backend wrapper: " + failure.getMessage());
        }
    }

    private static boolean supported(dfaj value) {
        return value instanceof nvsj || value instanceof cfal || value instanceof rrmj
            || value instanceof rakn || value instanceof tgqy || value instanceof fokk
            || value instanceof ozfd
            || value instanceof mods.gameobjects.packet.server.PacketGameObjectSet
            || value instanceof mods.gameobjects.packet.server.PacketGameObjectUpdate
            || value instanceof mods.gameobjects.packet.server.PacketGameObjectKillRequest
            || value instanceof mods.gameobjects.packet.server.PacketGameObjectHistoryAction
            || value instanceof mods.gameobjects.packet.server.PacketGameObjectTeleportToCamera
            || value instanceof mods.gameobjects.packet.server.PacketGameObjectInteract
            || value instanceof mods.gameobjects.packet.server.PacketSwitchLootable
            || value instanceof mods.gameobjects.packet.server.PacketLootableConfigure
            || value instanceof mods.gameobjects.packet.server.PacketGameObjectMarkerUpdate
            || value instanceof mods.gameobjects.packet.server.PacketGameObjectPlaceAttempt;
    }

    private static synchronized void recordUnsupported(dfaj packet) {
        String name = packet.getClass().getName();
        Integer count = unsupported.get(name);
        unsupported.put(name, count == null ? 1 : count + 1);
        if (count == null) System.out.println("[RECONSTRUCTION PACKET] Unsupported request=" + name);
        long now = System.currentTimeMillis();
        if (now - lastSummary >= 10000) {
            lastSummary = now;
            System.out.println("[RECONSTRUCTION PACKET] Unsupported request totals=" + unsupported);
        }
    }
}

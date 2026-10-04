import java.util.LinkedList;

import codechicken.lib.packet.PacketCustom;
import codechicken.nei.NEIServerConfig;
import codechicken.nei.NEIServerUtils;

/** Opt-in server-side NEI creative-give guard checks for ReconstructionTest. */
public final class LocalItemBrowserProbe {
    private static final String ENABLE_PROPERTY = "reconstruction.readinessProbe";
    private static final int[] CANDIDATE_ITEM_IDS = {266, 265, 264, 331};
    private static String field = "startup";

    private LocalItemBrowserProbe() {
    }

    /** Called only by the existing synthetic readiness probe; no live-world mutation is intended. */
    public static void run(gsye player) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) return;
        if (player == null || player.field_71134_c == null) {
            throw new IllegalStateException("NEI item-browser probe requires a live synthetic player");
        }
        if (player.field_71134_c._a()._a() != 2) {
            throw new IllegalStateException("NEI item-browser probe expects the player to start in Adventure");
        }

        testPacketAuthorization(player);
        testDirectGive(player, false);
        testDirectGive(player, true);
        System.out.println("[RECONSTRUCTION ITEM BROWSER] PASS"
                + " type1AdventureDenied=true type5AdventureDenied=true"
                + " type1CreativeAllowed=true type5CreativeAllowed=true"
                + " giveAdventureUnchanged=true giveCreativeDelta=2 inventoryRestored=true");
    }

    private static void testPacketAuthorization(gsye player) {
        mark("adventure-item-packet-denial");
        require(!NEIServerConfig.authenticatePacket(player, packet(1)), field,
                "NEI item-give packet was accepted in Adventure");
        mark("adventure-set-slot-packet-denial");
        require(!NEIServerConfig.authenticatePacket(player, packet(5)), field,
                "NEI set-slot packet was accepted in Adventure");

        int oldMode = player.field_71134_c._a()._a();
        try {
            player.func_71033_a(enhr._c);
            require(player.field_71134_c._b(), "creative-mode-setup", "native game mode did not become Creative");
            mark("creative-item-packet-authorization");
            require(NEIServerConfig.authenticatePacket(player, packet(1)), field,
                    "NEI item-give packet was not authorized in Creative");
            mark("creative-set-slot-packet-authorization");
            require(NEIServerConfig.authenticatePacket(player, packet(5)), field,
                    "NEI set-slot packet was not authorized in Creative");
        } finally {
            player.func_71033_a(enhr._a(oldMode));
        }
        require(player.field_71134_c._a()._a() == 2, "creative-mode-restoration",
                "player game mode did not return to Adventure");
    }

    private static PacketCustom packet(int type) {
        return new PacketCustom("NEI", type);
    }

    private static void testDirectGive(gsye player, boolean creative) {
        final String testName = creative ? "creative-direct-give" : "adventure-direct-give-denial";
        InventorySnapshot snapshot = null;
        int oldMode = player.field_71134_c._a()._a();
        try {
            snapshot = new InventorySnapshot(player);
            TestItem item = findUnusedItem(player);
            require(item != null, testName, "no unused test item and empty main-inventory slot were found");
            int before = countItem(player, item.itemId, item.damage);

            if (creative) player.func_71033_a(enhr._c);
            else player.func_71033_a(enhr._a(2));
            mark(testName);
            NEIServerUtils.givePlayerItem(player, new voib(item.itemId, 2, item.damage),
                    false, new LinkedList<String>(), true);
            int after = countItem(player, item.itemId, item.damage);
            require(creative ? after - before == 2 : after == before, testName,
                    "inventory item count changed unexpectedly: before=" + before + " after=" + after);
        } finally {
            if (snapshot != null) snapshot.restore(player);
            player.func_71033_a(enhr._a(oldMode));
        }
        require(player.field_71134_c._a()._a() == oldMode, testName + "-mode-restoration",
                "player game mode changed after direct-give check");
    }

    private static TestItem findUnusedItem(gsye player) {
        for (int itemId : CANDIDATE_ITEM_IDS) {
            voib probe = new voib(itemId, 1, 0);
            if (probe._a() == null) continue;
            int damage = probe._j();
            if (countItem(player, itemId, damage) != 0) continue;
            for (int slot = 0; slot < 36; slot++) {
                if (player.field_71071_by.func_70301_a(slot) == null) {
                    return new TestItem(itemId, damage);
                }
            }
        }
        return null;
    }

    private static int countItem(gsye player, int itemId, int damage) {
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            voib stack = player.field_71071_by.func_70301_a(slot);
            if (stack != null && stack._d == itemId && stack._j() == damage) total += stack._b;
        }
        return total;
    }

    private static void require(boolean passed, String name, String detail) {
        field = name;
        if (!passed) throw new IllegalStateException(detail);
    }

    private static void mark(String name) {
        field = name;
    }

    private static final class TestItem {
        final int itemId;
        final int damage;

        TestItem(int itemId, int damage) {
            this.itemId = itemId;
            this.damage = damage;
        }
    }

    private static final class InventorySnapshot {
        private final voib[] stacks;
        private final int[] counts;

        InventorySnapshot(gsye player) {
            int size = player.field_71071_by.func_70302_i_();
            stacks = new voib[size];
            counts = new int[size];
            for (int slot = 0; slot < size; slot++) {
                stacks[slot] = player.field_71071_by.func_70301_a(slot);
                counts[slot] = stacks[slot] == null ? 0 : stacks[slot]._b;
            }
        }

        void restore(gsye player) {
            for (int slot = 0; slot < stacks.length; slot++) {
                voib original = stacks[slot];
                if (original != null) original._b = counts[slot];
                player.field_71071_by.func_70299_a(slot, original);
            }
            if (player.field_71070_bA != null) player.field_71070_bA.func_75142_b();
        }
    }
}

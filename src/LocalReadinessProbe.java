import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

/** Opt-in integration checks for permissions and inventory hooks in ReconstructionTest. */
public final class LocalReadinessProbe {
    private static final String ENABLE_PROPERTY = "reconstruction.readinessProbe";
    private static boolean running;
    private static boolean complete;
    private static String outcome = "NOT_RUN";
    private static String field = "startup";

    private LocalReadinessProbe() {
    }

    public static void tick(jlrg server) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY) || complete || running || server == null
                || server._j == null || server._j.length == 0 || server._j[0] == null
                || server.__ag() == null || !"ReconstructionTest".equals(server._j())) return;

        gsye ordinary = null;
        int living = 0;
        for (Object value : server.__ag()._e) {
            if (!(value instanceof gsye)) continue;
            gsye player = (gsye) value;
            if (player.func_110143_aJ() <= 0.0f) continue;
            living++;
            if (!LocalPermissionHooks.isAdministrator(player) && ordinary == null) ordinary = player;
        }
        if (living < 2 || ordinary == null) return;

        running = true;
        try {
            run(server, ordinary);
            complete = true;
            outcome = "PASS";
            System.out.println("[RECONSTRUCTION READINESS] PASS joinAdventure=true"
                    + " selfGamemode12=true targetAndSurvivalDenied=true gamemodeConsoleAllowed=true"
                    + " adminNrwlx=true temporaryOpRestored=true unknownDeniedWithCreative=true"
                    + " inventoryOwner=true validSwap=true badWindowDenied=true"
                    + " aliasDenied=true badIndexDenied=true negativeSplitDenied=true"
                    + " emptyAdvancedNoFallback=true");
        } catch (RuntimeException failure) {
            complete = true;
            outcome = "FAIL field=" + field + " detail=" + clean(failure.getMessage());
            System.err.println("[RECONSTRUCTION READINESS] " + outcome);
            // Keep the synthetic server alive long enough for external readiness polling to
            // observe the structured failure and exit cleanly.
        } catch (Error failure) {
            complete = true;
            outcome = "FAIL field=" + field + " detail=" + clean(failure.getMessage());
            System.err.println("[RECONSTRUCTION READINESS] " + outcome);
            throw failure;
        } finally {
            running = false;
        }
    }

    /** Persistent outcome for the primary probe to assert after this tick returns. */
    public static String getOutcome() {
        return outcome;
    }

    private static void run(jlrg server, gsye ordinary) {
        mark("all-players-adventure");
        for (Object value : server.__ag()._e) {
            if (!(value instanceof gsye)) continue;
            gsye player = (gsye) value;
            require(player.field_71134_c != null && player.field_71134_c._a()._a() == 2,
                    "all-players-adventure", player.func_70005_c_());
        }

        laun manager = server.__ag();
        mark("selected-admin-roster");
        require(manager._g("nrwlx"), "selected-admin-roster", "ops.txt must authorize nrwlx");

        mark("non-admin-command-gate");
        require(!ordinary.func_70003_b(2, "time"), "non-admin-command-gate", ordinary.func_70005_c_());
        require(Boolean.getBoolean("stalcraft.test.selfGamemode"), "self-gamemode-switch",
                "set stalcraft.test.selfGamemode=true for this synthetic test");
        require(ordinary.func_70003_b(2, "gamemode"), "self-gamemode-command-entry",
                "the guarded gamemode command must reach its argument guard");

        mark("self-gamemode-argument-guard");
        require(LocalPermissionHooks.isAllowedGamemodeRequest(ordinary, new String[] {"1"}),
                "self-gamemode-1", ordinary.func_70005_c_());
        require(LocalPermissionHooks.isAllowedGamemodeRequest(ordinary, new String[] {"2", ordinary.func_70005_c_()}),
                "self-gamemode-2", ordinary.func_70005_c_());
        require(!LocalPermissionHooks.isAllowedGamemodeRequest(ordinary,
                new String[] {"1", "@a"}), "selector-target-denied", ordinary.func_70005_c_());
        require(!LocalPermissionHooks.isAllowedGamemodeRequest(ordinary,
                new String[] {"1", "ProbeBeta".equals(ordinary.func_70005_c_()) ? "ProbeAlpha" : "ProbeBeta"}),
                "other-player-target-denied", ordinary.func_70005_c_());
        require(!LocalPermissionHooks.isAllowedGamemodeRequest(ordinary, new String[] {"0"}),
                "survival-mode-denied", ordinary.func_70005_c_());
        require(LocalPermissionHooks.isAllowedGamemodeRequest(server, new String[] {"1", "ProbeAlpha"}),
                "server-console-gamemode-allowed", "dedicated console sender was denied");

        mark("temporary-op-check");
        String ordinaryName = ordinary.field_71092_bJ.toLowerCase(Locale.ROOT);
        boolean inserted = addTemporaryOperator(manager, ordinaryName);
        try {
            require(ordinary.func_70003_b(2, "time"), "temporary-op-authorized", ordinary.func_70005_c_());
        } finally {
            if (inserted) removeTemporaryOperator(manager, ordinaryName);
        }
        require(!manager._g(ordinaryName), "temporary-op-restored", ordinary.func_70005_c_());

        mark("creative-is-not-admin");
        int oldMode = ordinary.field_71134_c._a()._a();
        try {
            ordinary.func_71033_a(enhr._c);
            require(!manager._g("readiness-unknown"), "unknown-denied-with-creative",
                    "unknown identity inherited authority while a creative player was online");
        } finally {
            ordinary.func_71033_a(enhr._a(oldMode));
        }
        require(ordinary.field_71134_c._a()._a() == 2, "creative-test-mode-restored", ordinary.func_70005_c_());

        testAdvancedInventory(ordinary);
        mark("nei-item-browser");
        LocalItemBrowserProbe.run(ordinary);
        testModeInventory(ordinary);
    }

    private static void testAdvancedInventory(gsye player) {
        ifxb handler = ifxb._a((jlas) player);
        dhmd view = handler == null ? null : handler._d;
        mark("advanced-view-owner");
        require(view != null && view.getSafeViewOwner() == player && view.getWindowId() == 0
                        && view.isUsableByPlayer(player), "advanced-view-owner", player.func_70005_c_());

        ArrayList<SlotRef> empty = new ArrayList<SlotRef>();
        SlotRef outOfRange = null;
        voib goldProbe = ownedGold(player, 8);
        for (Map.Entry<Integer, hcxt> inventoryEntry : view.getInventories().entrySet()) {
            Integer inventoryId = inventoryEntry.getKey();
            hcxt inventory = inventoryEntry.getValue();
            if (inventoryId == null || inventory == null) continue;
            for (Map.Entry<Integer, String> sectionEntry : inventory.getNetworkMapping().entrySet()) {
                Integer sectionId = sectionEntry.getKey();
                zhku section = sectionId == null ? null : inventory.getNetworkSection(sectionId.intValue());
                if (section == null || sectionId == null) continue;
                if (outOfRange == null) {
                    outOfRange = new SlotRef(new htyp(inventoryId.intValue(), sectionId.intValue(), section.size()));
                }
                for (int slot = 0; slot < section.size(); slot++) {
                    htyp index = new htyp(inventoryId.intValue(), sectionId.intValue(), slot);
                    boolean mutable = view.isMutable(index);
                    boolean emptySlot = view.get(index) == null;
                    boolean sectionAccepts = section.canPutStack(slot, goldProbe);
                    boolean playerAccepts = view.canPlayerPutStack(section, goldProbe);
                    boolean stackValid = section.isStackValid(goldProbe);
                    boolean indexFits = section.canIndexFitStack(slot, goldProbe);
                    if (mutable && emptySlot && slot < player.field_71070_bA.field_75151_b.size()
                            && Boolean.getBoolean("reconstruction.readinessInventoryTrace")) {
                        System.out.println("[RECONSTRUCTION READINESS SLOT] inventory=" + inventoryId
                                + " section=" + sectionId + " name=" + sectionEntry.getValue()
                                + " slot=" + slot + " vanillaBound="
                                + (slot < player.field_71070_bA.field_75151_b.size())
                                + " mutable=" + mutable + " empty=" + emptySlot
                                + " canPutStack=" + sectionAccepts
                                + " canPlayerPutStack=" + playerAccepts
                                + " isStackValid=" + stackValid + " canIndexFitStack=" + indexFits
                                + " owner=" + goldProbe._l());
                    }
                    if (slot < player.field_71070_bA.field_75151_b.size()
                            && mutable && emptySlot && sectionAccepts && playerAccepts
                            && stackValid && indexFits) {
                        empty.add(new SlotRef(index));
                    }
                }
            }
        }
        mark("advanced-view-slots");
        require(empty.size() >= 3 && outOfRange != null, "advanced-view-slots",
                "need three empty mutable slots and one bounded section index");
        SlotRef first = empty.get(0), second = empty.get(1), readEmpty = empty.get(2);

        testEmptyReadDoesNotFallback(player, view, readEmpty);
        testRealSwapAndDenials(player, view, first, second, outOfRange);
    }

    private static void testEmptyReadDoesNotFallback(gsye player, dhmd view, SlotRef candidate) {
        mark("empty-advanced-read-no-fallback");
        require(candidate.index._c() < player.field_71070_bA.field_75151_b.size(),
                "empty-advanced-read-no-fallback", "candidate has no corresponding vanilla container slot");
        rbxb legacySlot = player.field_71070_bA.func_75139_a(candidate.index._c());
        voib originalLegacy = legacySlot.func_75211_c();
        voib marker = new voib(266, 1, 0);
        try {
            legacySlot.func_75215_d(marker);
            require(view.get(candidate.index) == null, "empty-advanced-read-no-fallback",
                    "legacy marker unexpectedly populated the advanced view");
            require(LocalInventoryHooks.getStackFromSlot(player, view, candidate.index) == null,
                    "empty-advanced-read-no-fallback", "empty advanced slot returned a legacy stack");
        } finally {
            legacySlot.func_75215_d(originalLegacy);
        }
    }

    private static void testRealSwapAndDenials(gsye player, dhmd view,
                                               SlotRef first, SlotRef second,
                                               SlotRef outOfRange) {
        htyp a = first.index;
        htyp b = second.index;
        voib originalA = view.get(a);
        voib originalB = view.get(b);
        int window = view.getWindowId();
        voib goldEight = ownedGold(player, 8);
        voib goldTwo = ownedGold(player, 2);
        try {
            mark("seed-temporary-gold-stacks");
            require(originalA == null && originalB == null && !a.equals(b),
                    "seed-temporary-gold-stacks", "selected slots changed before fixture setup");
            boolean setA = view.set(a, goldEight);
            boolean setB = view.set(b, goldTwo);
            if (Boolean.getBoolean("reconstruction.readinessInventoryTrace")) {
                System.out.println("[RECONSTRUCTION READINESS SEED] a=" + a._a() + ":" + a._b() + ":" + a._c()
                        + " setA=" + setA + " aNow=" + describe(view.get(a))
                        + " goldAOwner=" + goldEight._l()
                        + " b=" + b._a() + ":" + b._b() + ":" + b._c()
                        + " setB=" + setB + " bNow=" + describe(view.get(b))
                        + " goldBOwner=" + goldTwo._l());
            }
            require(setA && setB,
                    "seed-temporary-gold-stacks", "could not seed owner-tagged gold stacks");
            require(isGold(view.get(a), 8) && isGold(view.get(b), 2),
                    "seed-temporary-gold-stacks", "seeded inventory state differs");

            mark("real-inventory-split");
            bbod valid = new bbod(window, a, b, 2);
            require(LocalInventoryHooks.allow(valid, player), "valid-swap-guard", "valid swap was rejected");
            require(player.field_71135_a != null, "real-inventory-split", "server connection missing");
            LocalPacketBridge.handle(valid, player.field_71135_a);
            require(isGold(view.get(a), 6) && isGold(view.get(b), 4),
                    "real-inventory-split", "real packet handler did not transfer two gold ingots (8 to 6, 2 to 4)");

            voib stateA = view.get(a), stateB = view.get(b);
            mark("invalid-window-denial");
            bbod badWindow = new bbod(window + 77, a, b, 1);
            require(!LocalInventoryHooks.allow(badWindow, player), "invalid-window-denial", "bad window accepted");
            LocalPacketBridge.handle(badWindow, player.field_71135_a);
            require(isGold(view.get(a), 6) && isGold(view.get(b), 4),
                    "invalid-window-no-change", "bad window changed stacks");

            mark("alias-denial");
            bbod alias = new bbod(window, a, a, 1);
            require(!LocalInventoryHooks.allow(alias, player), "alias-denial", "same-slot alias accepted");
            LocalPacketBridge.handle(alias, player.field_71135_a);
            require(same(view.get(a), stateA) && same(view.get(b), stateB),
                    "alias-no-change", "same-slot request changed stacks");

            mark("out-of-range-index-denial");
            bbod badIndex = new bbod(window, a, outOfRange.index, 1);
            require(!LocalInventoryHooks.allow(badIndex, player), "out-of-range-index-denial", "out-of-range index accepted");
            LocalPacketBridge.handle(badIndex, player.field_71135_a);
            require(same(view.get(a), stateA) && same(view.get(b), stateB),
                    "out-of-range-index-no-change", "out-of-range request changed stacks");

            mark("negative-transfer-denial");
            bbod negative = new bbod(window, a, b, -1);
            require(!LocalInventoryHooks.allow(negative, player), "negative-transfer-denial", "negative split amount accepted");
            LocalPacketBridge.handle(negative, player.field_71135_a);
            require(same(view.get(a), stateA) && same(view.get(b), stateB),
                    "negative-transfer-no-change", "negative split changed stacks");
        } finally {
            view.set(a, originalA);
            view.set(b, originalB);
        }
    }

    private static void testModeInventory(gsye player) {
        mark("creative-to-adventure-inventory");
        int slot = -1;
        for (int i = 0; i < player.field_71071_by._a.length; i++)
            if (player.field_71071_by._a[i] == null) { slot = i; break; }
        require(slot >= 0, field, "need an empty legacy slot");
        voib probe = new voib(266, 7, 0);
        ognf._c(probe)._a("reconstructionModeProbe", "v35");
        ifxb handler = ifxb._a(player);
        try {
            player.func_71033_a(enhr._c);
            player.field_71071_by.func_70299_a(slot, probe);
            player.func_71033_a(enhr._d);
            require(player.field_71071_by.func_70301_a(slot) == null, field, "accepted items stayed in legacy slot");
            require(countModeProbe(handler) == 7, field, "advanced inventory did not receive exactly seven items");
            player.func_71033_a(enhr._d);
            require(countModeProbe(handler) == 7, field, "repeated mode change duplicated items");
            System.out.println("[RECONSTRUCTION INVENTORY MODE] PASS creativeToAdventure=7 legacyEmpty=true noDuplicate=true");
        } finally {
            player.field_71071_by.func_70299_a(slot, null);
            for (hcxt inventory : handler._d.getInventories().values())
                for (zhku section : inventory.getSections().values())
                    for (Map.Entry<Integer, voib> entry : new java.util.HashMap<Integer, voib>(section.getContentsIndex()).entrySet())
                        if (isModeProbe(entry.getValue())) section.setStackAtUnchecked(entry.getKey().intValue(), null);
            player.func_71033_a(enhr._d);
            ServerPacketHandler.syncInventory(player);
        }
    }

    private static boolean isModeProbe(voib stack) {
        return stack != null && "v35".equals(ognf._d(stack)._j("reconstructionModeProbe"));
    }

    private static int countModeProbe(ifxb handler) {
        int total = 0;
        for (hcxt inventory : handler._d.getInventories().values())
            for (zhku section : inventory.getSections().values())
                for (voib stack : section.getContentsIndex().values())
                    if (isModeProbe(stack)) total += stack._b;
        return total;
    }

    private static voib ownedGold(gsye player, int count) {
        voib result = new voib(266, count, 0);
        mact._a(result, player.field_71092_bJ);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static boolean addTemporaryOperator(laun manager, String playerName) {
        return manager._h.add(playerName);
    }

    private static void removeTemporaryOperator(laun manager, String playerName) {
        manager._h.remove(playerName);
    }

    private static boolean isGold(voib stack, int count) {
        return stack != null && stack._d == 266 && stack._b == count;
    }

    private static String describe(voib stack) {
        return stack == null ? "null" : "id=" + stack._d + ",count=" + stack._b + ",owner=" + stack._l();
    }

    private static boolean same(voib actual, voib expected) {
        if (actual == expected) return true;
        return actual != null && expected != null && actual._d == expected._d
                && actual._b == expected._b && actual._j() == expected._j();
    }

    private static void require(boolean passed, String name, String detail) {
        field = name;
        if (!passed) throw new IllegalStateException(detail);
    }

    private static void mark(String name) {
        field = name;
    }

    private static String clean(String value) {
        if (value == null || value.length() == 0) return "unspecified";
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private static final class SlotRef {
        final htyp index;

        SlotRef(htyp index) {
            this.index = index;
        }
    }
}

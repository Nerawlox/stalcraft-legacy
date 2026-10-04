import java.util.Map;

/** Test-only migration of retained vanilla inventory items into the Stalker view. */
public final class LocalTestInventoryModeHooks {
    private static final String ENABLE_PROPERTY = "stalcraft.test.selfGamemode";

    private LocalTestInventoryModeHooks() {
    }

    /**
     * Creative mode uses Minecraft's legacy inventory, while the test's adventure mode
     * exposes the Stalker inventory. Move accepted legacy items across without dropping
     * overflow or changing ownership tags.
     */
    public static void onGameModeChanged(gsye player, enhr newMode) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY) || player == null || newMode == null
                || newMode._a() != 2 || player.field_71071_by == null) return;

        ifxb handler = ifxb._a((jlas) player);
        dhmd view = handler == null ? null : handler._d;
        if (handler == null || view == null || view.getSafeViewOwner() != player
                || !view.isOwnedView() || !view.isUsableByPlayer(player)
                || handler._b == null || handler._c == null) return;

        qptu legacy = player.field_71071_by;
        boolean changed = false;
        for (int slot = 0; slot < legacy._a.length; slot++) {
            changed |= transferOne(player, handler, view, legacy, slot, legacy._a[slot]);
        }
        for (int armor = 0; armor < legacy._b.length; armor++) {
            int slot = legacy._a.length + armor;
            changed |= transferOne(player, handler, view, legacy, slot, legacy._b[armor]);
        }

        if (changed) {
            legacy._f();
            view.detectAndSendChanges();
        }
    }

    private static boolean transferOne(gsye player, ifxb handler, dhmd view,
                                       qptu legacy, int legacySlot, voib source) {
        if (source == null || source._b <= 0 || !sameOrUnowned(source, player.field_71092_bJ)) return false;

        int initialCount = source._b;
        voib remainder = source._l();
        int equipped = placeInEquipment(player, handler, view, remainder);
        if (equipped > 0) remainder._b -= equipped;

        int beforeGrid = remainder._b;
        if (beforeGrid > 0 && handler._c.canPlayerPutStack(player, remainder)) {
            handler._c.appendStackPartially(remainder, false, 0);
        }
        int moved = initialCount - remainder._b;
        if (moved <= 0) return false;

        voib removed = legacy.func_70298_a(legacySlot, moved);
        if (removed == null || removed._b != moved) {
            // The server tick is single-threaded; this guard prevents duplication if that
            // assumption is violated by another hook.
            System.err.println("[TEST INVENTORY MODE] legacy removal mismatch player="
                    + player.func_70005_c_() + " slot=" + legacySlot + " moved=" + moved);
            return false;
        }
        System.out.println("[TEST INVENTORY MODE] moved player=" + player.func_70005_c_()
                + " legacySlot=" + legacySlot + " count=" + moved
                + " overflow=" + remainder._b);
        return true;
    }

    /** Uses the active owned view's native slot filters for special equipment sections. */
    private static int placeInEquipment(gsye player, ifxb handler, dhmd view, voib remainder) {
        if (remainder._b <= 0) return 0;
        voib one = remainder._l();
        one._b = 1;
        int inventoryId = -1;
        for (Map.Entry<Integer, hcxt> entry : view.getInventories().entrySet()) {
            if (entry.getValue() == handler._b) {
                inventoryId = entry.getKey().intValue();
                break;
            }
        }
        if (inventoryId < 0) return 0;

        hcxt equipment = handler._b;
        for (Map.Entry<Integer, String> sectionEntry : equipment.getNetworkMapping().entrySet()) {
            int sectionId = sectionEntry.getKey().intValue();
            zhku section = equipment.getNetworkSection(sectionId);
            if (section == null) continue;
            for (int slot = 0; slot < section.size(); slot++) {
                htyp index = new htyp(inventoryId, sectionId, slot);
                if (!view.isMutable(index) || !view.isSlotOwned(index) || view.get(index) != null
                        || !section.isStackValid(one) || !section.canPutStack(slot, one)
                        || !section.canIndexFitStack(slot, one)
                        || !view.canPlayerPutStack(section, one)) continue;
                try {
                    if (view.set(index, one._l())) return 1;
                } catch (RuntimeException ignored) {
                    // Continue to the next native-filtered equipment slot.
                }
            }
        }
        return 0;
    }

    private static boolean sameOrUnowned(voib stack, String username) {
        String owner = mact._e(stack);
        return owner == null || owner.length() == 0 || owner.equalsIgnoreCase(username);
    }
}

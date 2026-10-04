import java.lang.reflect.Field;
import java.util.Set;

/** Validate the actor's actual server view before entering the retained item handlers. */
public final class LocalInventoryHooks {
    private LocalInventoryHooks() {}

    public static boolean allow(nvsj request, jlas player) {
        if (request == null || player == null || player.field_70170_p == null
                || player.field_70170_p.field_72995_K || !player.func_70089_S()) return false;
        try {
            ifxb handler = ifxb._a(player);
            int id = request._b();
            dhmd view = handler == null ? null : (id == 0 ? handler._d : handler._c(id));
            if (id < 0 || view == null || view.getSafeViewOwner() != player
                    || !view.isUsableByPlayer(player)) return reject(request, "window");
            if (request instanceof wplc || request instanceof zhsr) return true;
            if (request instanceof txdm) {
                txdm swap = (txdm) request;
                if (!slot(view, swap._d()) || !slot(view, swap._e()) || swap._d().equals(swap._e())) return false;
                if (request instanceof bbod) {
                    voib stack = view.get(swap._d());
                    int amount = ((bbod) request)._a();
                    return stack != null && amount > 0 && amount <= stack._b;
                }
                return true;
            }
            if (request instanceof supg) {
                htyp weapon = (htyp) field(request, "_a");
                htyp attachment = (htyp) field(request, "_b");
                return slot(view, weapon) && field(request, "_d") != null
                    && (attachment == null || (slot(view, attachment) && !weapon.equals(attachment)));
            }
            if (request instanceof sdot) {
                htyp weapon = (htyp) field(request, "_a"), camo = (htyp) field(request, "_b");
                return slot(view, weapon) && slot(view, camo) && !weapon.equals(camo);
            }
            if (request instanceof yvqy) {
                if (!slot(view, (htyp) field(request, "_a"))) return false;
                int destination = ((Integer) field(request, "_b")).intValue();
                if (!view.getInventories().containsKey(destination)) return false;
                Object changed = field(request, "_c");
                if (!(changed instanceof Set<?>) || ((Set<?>) changed).size() > 1024) return false;
                for (Object index : (Set<?>) changed)
                    if (!(index instanceof htyp) || !slot(view, (htyp) index)) return false;
                return true;
            }
            if (request instanceof rald || request instanceof xbna || request instanceof tgcc
                    || request instanceof zhsx) return slot(view, (htyp) field(request, "_a"));
            return reject(request, "unsupported-action");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return reject(request, "invalid-index");
        }
    }

    static boolean slot(dhmd view, htyp index) {
        if (index == null || index._c() < 0) return false;
        hcxt inventory = view.getInventories().get(index._a());
        if (inventory == null) return false;
        zhku section = inventory.getNetworkSection(index._b());
        return section != null && index._c() < section.size() && view.isMutable(index);
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static boolean reject(nvsj request, String reason) {
        if (Boolean.getBoolean("reconstruction.serverProbe"))
            System.out.println("[RECONSTRUCTION INVENTORY] rejected=" + request.getClass().getName()
                + " reason=" + reason);
        return false;
    }

    /** The vanilla placement path already has the authoritative held stack. */
    public static void beforeBlockPlace(gsye player, waom request) {
        // Do not manufacture a held item from the untrusted placement packet.
    }

    public static voib getStackFromSlot(jlas player, dhmd view, htyp index) {
        if (view == null || view.getSafeViewOwner() != player || !slot(view, index)) return null;
        return view.get(index);
    }

    public static void setStackToSlot(jlas player, dhmd view, htyp index, voib stack) {
        if (view != null && view.getSafeViewOwner() == player && slot(view, index)) view.set(index, stack);
    }

    public static boolean validCreative(gsye player, int slot, voib stack) {
        return player != null && player.field_71134_c != null && player.field_71134_c._b()
            && slot >= 0 && player.field_71069_bz != null
            && slot < player.field_71069_bz.field_75151_b.size()
            && (stack == null || (stack._a() != null && stack._b > 0 && stack._b <= 64));
    }

    /** Preserve native partial-stack semantics instead of treating bbod as a full swap. */
    public static void handleSplit(bbod request, jlas player) {
        if (!allow(request, player)) return;
        ifxb handler = ifxb._a(player);
        dhmd view = request._b() == 0 ? handler._d : handler._c(request._b());
        view.transferStackPart(request._d(), request._e(), request._a());
        view.detectAndSendChanges();
        if (request._c() >= 0)
            ServerPacketHandler.sendPacketToPlayer(player, new xael(new yepz(request._b(), request._c())));
        ServerPacketHandler.syncInventory(player);
    }
}

import java.io.File;
import java.util.ArrayList;

/** Opt-in authoritative tick diagnostics for this lab's synthetic world only. */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class LocalServerProbe {
    private static int ticks;
    private static int sharedTicks;
    private static boolean firstChange;
    private static boolean secondChange;
    private static boolean fixturePrepared;

    public static void tick(jlrg value) {
        if (new File("reconstruction-stop.flag").exists()) value._z();
        if (!Boolean.getBoolean("reconstruction.serverProbe")) return;
        ticks++;
        if (value.__ag() == null || value._j.length == 0 || value._j[0] == null) return;
        ArrayList players = new ArrayList(value.__ag()._e);
        if (ticks % 40 == 0) {
            StringBuilder state = new StringBuilder("[RECONSTRUCTION SERVER] ticks=" + ticks + " players=" + players.size());
            for (Object item : players) {
                gsye player = (gsye) item;
                state.append(" ").append(player.func_70005_c_()).append("#").append(player.field_70157_k)
                    .append("@").append(player.field_70165_t).append(",").append(player.field_70163_u).append(",").append(player.field_70161_v)
                    .append(" hp=").append(player.func_110143_aJ());
            }
            state.append(" testBlock=").append(value._a(0).func_72798_a(445, 4, -625));
            for (Object item : players) {
                gsye player = (gsye) item;
                voib held = player.func_70694_bm();
                if (held != null && held._a() instanceof grkk) {
                    state.append(" weapon[").append(player.func_70005_c_()).append("]=").append(held._d)
                        .append(" bullets=").append(grkk._H(held)).append(" ammo=").append(grkk._I(held))
                        .append(" reserve=").append(ognf._b(player, grkk._I(held)));
                }
            }
            System.out.println(state);
        }
        boolean allAlive = players.size() >= 2;
        for (Object item : players) if (((gsye) item).func_110143_aJ() <= 0) allAlive = false;
        if (allAlive) sharedTicks++;
        if (!"ReconstructionTest".equals(value._j())) return;
        if (Boolean.getBoolean("reconstruction.readinessProbe")) LocalReadinessProbe.tick(value);
        if (sharedTicks >= 40 && Boolean.getBoolean("reconstruction.gameplayProbe") && !fixturePrepared) {
            fixturePrepared = true;
            prepareWeaponFixture(value, players);
        }
        if (fixturePrepared) LocalCombatServerProbe.tick(value);
        if (sharedTicks >= 100 && !firstChange) {
            firstChange = true;
            boolean changed = value._a(0).func_72832_d(445, 4, -625, 41, 0, 3);
            System.out.println("[RECONSTRUCTION SERVER] block transition to gold=" + changed);
        }
        if (sharedTicks >= 300 && !secondChange) {
            secondChange = true;
            boolean changed = value._a(0).func_72832_d(445, 4, -625, 57, 0, 3);
            System.out.println("[RECONSTRUCTION SERVER] block transition to diamond=" + changed);
        }
    }

    private static void prepareWeaponFixture(jlrg server, ArrayList players) {
        for (Object entry : players) {
            gsye player = (gsye) entry;
            if (!"ProbeAlpha".equals(player.func_70005_c_())) continue;
            for (lrhp item : lrhp.field_77698_e) {
                if (!(item instanceof grkk)) continue;
                voib weapon = new voib(item);
                ServerPacketHandler.ensureDefaultAttachments(weapon);
                int[] compatible = ((grkk) item)._Y(weapon);
                if (compatible == null) continue;
                for (int ammo : compatible) {
                    if (ammo <= 0 || ammo >= lrhp.field_77698_e.length || lrhp.field_77698_e[ammo] == null) continue;
                    if (Boolean.getBoolean("reconstruction.combatProbe")) {
                        ifxb inventory = ifxb._a(player);
                        mact._a(weapon, player.field_71092_bJ);
                        if (!inventory._d._a()._h().setStackAt(0, weapon))
                            throw new IllegalStateException("Combat fixture primary equipment rejected firearm");
                        inventory._b(false); inventory._b(0);
                        voib reserve = new voib(ammo, 64, 0);
                        mact._a(reserve, player.field_71092_bJ);
                        if (!inventory._d._a(reserve, null))
                            throw new IllegalStateException("Combat fixture rejected ammunition");
                    } else {
                        ((buzu) server)._a("gamemode 1 ProbeAlpha", server);
                        ServerPacketHandler.handleCreativeSetSlot(player, 36, weapon);
                        ServerPacketHandler.giveItemToPlayer(player, new voib(ammo, 64, 0));
                    }
                    ServerPacketHandler.syncInventory(player);
                    System.out.println("[RECONSTRUCTION GAMEPLAY] fixture weapon=" + weapon._d + " ammo=" + ammo
                        + " reserve=" + ognf._b(player, ammo) + " target=ProbeAlpha");
                    return;
                }
            }
            throw new IllegalStateException("No registered weapon/ammo pair for diagnostic fixture");
        }
    }
}

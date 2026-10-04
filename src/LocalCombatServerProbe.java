/** Positions actual test clients for a bounded hit test; never runs on the supplied map. */
public final class LocalCombatServerProbe {
    private static boolean ready;
    private static long readyAt;
    private static int phase;
    public static void tick(jlrg server) {
        if (!Boolean.getBoolean("reconstruction.combatProbe")
                || !"ReconstructionTest".equals(server._j())) return;
        gsye alpha = server.__ag()._h("ProbeAlpha"), beta = server.__ag()._h("ProbeBeta");
        if (alpha == null || beta == null || !alpha.func_70089_S() || !beta.func_70089_S()) return;
        if (ready) {
            long elapsed = System.currentTimeMillis() - readyAt;
            if (phase == 0 && elapsed >= 20000) {
                beta.func_71033_a(enhr._c); phase++;
                System.out.println("[RECONSTRUCTION COMBAT FIXTURE] phase=creative health=" + beta.func_110143_aJ());
            } else if (phase == 1 && elapsed >= 35000) {
                beta.func_71033_a(enhr._d);
                for (int y = 4; y <= 6; y++) server._a(0).func_72832_d(447, y, -595, 1, 0, 3);
                phase++;
                System.out.println("[RECONSTRUCTION COMBAT FIXTURE] phase=wall health=" + beta.func_110143_aJ());
            } else if (phase == 2 && elapsed >= 50000) {
                for (int y = 4; y <= 6; y++) server._a(0).func_72832_d(447, y, -595, 0, 0, 3);
                grkk._G(alpha.func_70694_bm());
                ServerPacketHandler.syncInventory(alpha); phase++;
                System.out.println("[RECONSTRUCTION COMBAT FIXTURE] phase=empty-ammunition health=" + beta.func_110143_aJ());
            } else if (phase == 3 && elapsed >= 65000) {
                ifxb._a(alpha)._d._a()._h().setStackAt(0, null);
                ServerPacketHandler.syncInventory(alpha); phase++;
                System.out.println("[RECONSTRUCTION COMBAT FIXTURE] phase=empty-hand health=" + beta.func_110143_aJ());
            }
            return;
        }
        voib held = alpha.func_70694_bm();
        if (held == null || !(held._a() instanceof grkk)) return;
        alpha.func_71033_a(enhr._d); beta.func_71033_a(enhr._d);
        alpha.func_70606_j(20.0f); beta.func_70606_j(20.0f);
        alpha.field_71135_a.func_72569_a(447.5, 4.0, -600.5, 0.0f, 0.0f);
        beta.field_71135_a.func_72569_a(447.5, 4.0, -590.5, 180.0f, 0.0f);
        ready = true;
        readyAt = System.currentTimeMillis();
        System.out.println("[RECONSTRUCTION COMBAT FIXTURE] READY alphaMode=adventure betaMode=adventure weapon=" + held._d);
    }
}

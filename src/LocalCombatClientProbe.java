import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import net.minecraft.client.qlfw;

/** Opt-in packet diagnostics for two connected clients in the synthetic lab. */
public final class LocalCombatClientProbe {
    private static long readyAt;
    private static int stage;

    public static void tick(qlfw client) {
        if (!Boolean.getBoolean("reconstruction.combatProbe") || client._t == null || client._r == null
                || !"ProbeAlpha".equals(client._T()._a())) return;
        voib held = client._t.func_70694_bm();
        if (stage < 7 && (held == null || !(held._a() instanceof grkk))) return;
        if (stage < 6 && grkk._H(held) <= 0) return;
        jlas target = null;
        for (Object entity : client._r.field_73010_i)
            if (entity instanceof jlas && "ProbeBeta".equals(((jlas) entity).func_70005_c_())) target = (jlas) entity;
        if (target == null || !target.func_70089_S()) return;
        double x = client._t.field_70165_t, y = client._t.field_70163_u + client._t.func_70047_e(), z = client._t.field_70161_v;
        double tx = target.field_70165_t, ty = target.field_70163_u + target.func_70047_e() * 0.7, tz = target.field_70161_v;
        double dx = tx - x, dy = ty - y, dz = tz - z;
        client._t.field_70177_z = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        client._t.field_70125_A = (float) (-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI);
        long now = System.currentTimeMillis();
        if (readyAt == 0) readyAt = now;
        long[] times = {3000, 6000, 9000, 12000, 27000, 42000, 57000, 72000};
        if (stage >= times.length || now - readyAt < times[stage]) return;
        try {
            String label;
            if (stage == 0) { x = Double.NaN; label = "nonfinite"; }
            else if (stage == 1) { x += 100.0; label = "off-origin"; }
            else if (stage == 2) { tz += 1000000.0; label = "out-of-range"; }
            else if (stage == 3) label = "valid-hitscan";
            else if (stage == 4) label = "creative-target";
            else if (stage == 5) label = "wall-blocked";
            else if (stage == 6) label = "empty-ammunition";
            else label = "empty-hand";
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeBoolean(false); output.writeFloat(1.0f); output.writeInt(stage + 1); output.writeShort(1);
            output.writeDouble(x); output.writeDouble(y); output.writeDouble(z);
            output.writeDouble(tx); output.writeDouble(ty); output.writeDouble(tz);
            output.writeByte(0); output.writeByte(0); output.writeShort(0); output.writeInt(0);
            gloomyfolken.mods.weapon.qlfw shot = new gloomyfolken.mods.weapon.qlfw();
            shot._a(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
            new rrmj(shot, gloomyfolken.mods.weapon.zwbc.hrmt._a).sendToServer();
            if (stage == 3) new rrmj(shot, gloomyfolken.mods.weapon.zwbc.hrmt._a).sendToServer();
            System.out.println("[RECONSTRUCTION COMBAT CLIENT] sent=" + label + " magazineBefore="
                + (held == null || !(held._a() instanceof grkk) ? -1 : grkk._H(held)));
            stage++;
        } catch (java.io.IOException failure) { throw new IllegalStateException("Combat probe", failure); }
    }
}

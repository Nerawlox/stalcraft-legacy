import java.util.Map;
import java.util.List;
import java.util.WeakHashMap;
import java.lang.reflect.Field;

/** Narrow server-side admission policy for the retained hitscan request. */
public final class LocalCombatHooks {
    private static final Map<jlas, Double> NEXT_ALLOWED_TICK = new WeakHashMap<jlas, Double>();
    private static final Map<jlas, Long> LAST_ACCEPTED_TICK = new WeakHashMap<jlas, Long>();
    private static final Map<jlas, lrhp> LAST_ACCEPTED_WEAPON = new WeakHashMap<jlas, lrhp>();
    private static final Map<jlas, Double> NEXT_MELEE_TICK = new WeakHashMap<jlas, Double>();
    private static final Field SHOT_INFO = field(rrmj.class, "_b");
    private static final Field FIRE_MODE = field(gloomyfolken.mods.weapon.owap.class, "_R");
    private static final Field GRENADE_MODE = field(gloomyfolken.mods.weapon.owap.class, "_S");
    private static final int MAX_PACKET_ENTRIES = 64;
    private static final double MAX_COORDINATE = 30000000.0;
    private static final double MAX_WEAPON_RANGE = 512.0;
    private static final double ORIGIN_TOLERANCE_SQUARED = 2.25;

    private LocalCombatHooks() { }

    /**
     * Call before the retained handler. A true result consumes the request's one
     * server-tick firing slot; the retained handler remains responsible for the
     * single native magazine decrement and its normal inventory synchronization.
     */
    public static synchronized boolean acceptHitscan(gloomyfolken.mods.weapon.qlfw shot,
                                                       jlas shooter, voib held) {
        if (shot == null || shooter == null || shooter.field_70170_p == null
                || shooter.field_70170_p.field_72995_K || shooter.field_70128_L
                || !shooter.func_70089_S() || held == null || !(held._a() instanceof grkk)) return reject("no live shooter or held firearm");

        grkk weapon = (grkk) held._a();
        rajh ammunition = grkk._E(held);
        int ammoId = grkk._I(held);
        if (ammunition == null || ammunition._p_() || ammoId <= 0 || !weapon._h(held, ammoId)
                || grkk._H(held) <= 0) return reject("missing, incompatible, projectile, or empty ammunition");
        gloomyfolken.mods.weapon.owap controller = gloomyfolken.mods.weapon.owap._b(shooter);
        if (controller == null) return reject("weapon controller is unavailable");
        try {
            if (((Boolean) GRENADE_MODE.get(controller)).booleanValue()) return reject("underbarrel projectile mode is unsupported");
            Object selectedMode = FIRE_MODE.get(controller);
            if (!(selectedMode instanceof gloomyfolken.mods.weapon.owap.hrmt)
                    || !weapon._A(held).contains(selectedMode)) return reject("server fire mode is not valid for this weapon");
        } catch (IllegalAccessException failure) {
            return reject("weapon controller state is unavailable");
        }

        net.minecraft.util.wmya origin = shot._e();
        net.minecraft.util.wmya endpoint = shot._f();
        if (!finiteVector(origin) || !finiteVector(endpoint)) return reject("nonfinite or out-of-bounds ray");
        if (shot._a().size() > MAX_PACKET_ENTRIES || shot._b().size() > MAX_PACKET_ENTRIES) return reject("oversized target maps");
        if (shot._d() < 0 || shot._d() > MAX_PACKET_ENTRIES || !finite(shot._h())
                || shot._h() < 0.0f || shot._h() > 60.0f) return reject("invalid shot metadata");

        double eyeX = shooter.field_70165_t;
        double eyeY = shooter.field_70163_u + shooter.func_70047_e();
        double eyeZ = shooter.field_70161_v;
        // Native requests may originate at feet or eyes. Validate against both
        // server-authoritative anchors, then rebuild the ray from eyes.
        double feetX = origin._c - shooter.field_70165_t;
        double feetY = origin._d - shooter.field_70163_u;
        double feetZ = origin._e - shooter.field_70161_v;
        double eyeYDelta = origin._d - eyeY;
        double feetDistanceSquared = feetX * feetX + feetY * feetY + feetZ * feetZ;
        double eyeDistanceSquared = feetX * feetX + eyeYDelta * eyeYDelta + feetZ * feetZ;
        if (Math.min(feetDistanceSquared, eyeDistanceSquared) > ORIGIN_TOLERANCE_SQUARED) {
            return reject("client origin differs from authoritative player body");
        }

        double dx = endpoint._c - origin._c;
        double dy = endpoint._d - origin._d;
        double dz = endpoint._e - origin._e;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (!(distanceSquared > 0.0001) || !finite(distanceSquared)) return reject("empty ray");
        int weaponRange = weapon._z(held);
        double effectiveRange = Math.min((double) weaponRange, MAX_WEAPON_RANGE);
        if (weaponRange <= 0 || distanceSquared > effectiveRange * effectiveRange) return reject("ray exceeds weapon range");

        // Reject rays aimed well behind the server's current view. The broad
        // cone allows normal packet/tick yaw drift while blocking arbitrary rays.
        net.minecraft.util.wmya look = shooter.func_70040_Z();
        if (!finiteVector(look)) return reject("invalid server look vector");
        double dot = dx * look._c + dy * look._d + dz * look._e;
        double length = Math.sqrt(distanceSquared);
        if (!(dot / length >= 0.75) || !(dot > 0.0)) return reject("ray outside server aim cone");

        // The client may choose the shot distance, but the ray itself comes
        // from the server's eye position and current look direction.
        double rayLength = effectiveRange;
        origin._c = eyeX;
        origin._d = eyeY;
        origin._e = eyeZ;
        endpoint._c = eyeX + look._c * rayLength;
        endpoint._d = eyeY + look._d * rayLength;
        endpoint._e = eyeZ + look._e * rayLength;

        long tick = shooter.field_70170_p.func_82737_E();
        float cooldown = weapon._q(held);
        if (!finite(cooldown) || cooldown <= 0.0f || cooldown > 2400.0f) return reject("invalid native fire cadence");
        Double nextAllowed = NEXT_ALLOWED_TICK.get(shooter);
        if (nextAllowed != null && (double) tick < nextAllowed.doubleValue()) return reject("native weapon cadence not ready");
        NEXT_ALLOWED_TICK.put(shooter, Double.valueOf((double) tick + (double) cooldown));
        LAST_ACCEPTED_TICK.put(shooter, Long.valueOf(tick));
        LAST_ACCEPTED_WEAPON.put(shooter, held._a());
        if (Boolean.getBoolean("reconstruction.gameplayProbe")) {
            System.out.println("[RECONSTRUCTION COMBAT] accepted shooter=" + shooter.func_70005_c_()
                + " weapon=" + held._d + " ammo=" + ammoId + " bullets=" + grkk._H(held)
                + " range=" + effectiveRange + " cooldownTicks=" + cooldown);
        }
        return true;
    }

    private static boolean reject(String reason) {
        if (Boolean.getBoolean("reconstruction.gameplayProbe")) {
            System.out.println("[RECONSTRUCTION COMBAT] rejected=" + reason);
        }
        return false;
    }

    /** Replacement handler for the retained hitscan request. */
    public static void handleHit(rrmj request, jlas shooter) {
        if (request == null || shooter == null || shooter.field_70170_p == null) return;
        try {
            gloomyfolken.mods.weapon.qlfw shot = (gloomyfolken.mods.weapon.qlfw) SHOT_INFO.get(request);
            voib held = shooter.func_70694_bm();
            if (!acceptHitscan(shot, shooter, held)) return;

            grkk weapon = (grkk) held._a();
            int ammoBefore = grkk._H(held);
            grkk._M(held);
            ServerPacketHandler.sendPacketToPlayer(shooter, new xael(new plgc(grkk._H(held))));
            ServerPacketHandler.syncInventory(shooter);
            if (Boolean.getBoolean("reconstruction.gameplayProbe")) {
                System.out.println("[RECONSTRUCTION COMBAT] ammo shooter=" + shooter.func_70005_c_()
                    + " before=" + ammoBefore + " after=" + grkk._H(held));
            }

            gloomyfolken.mods.weapon.trace.EntityTracer.hrmt hit =
                gloomyfolken.mods.weapon.trace.EntityTracer._a(shooter.field_70170_p,
                    shot._e(), shot._f(), shooter);
            if (hit == null || hit._k == null || hit._k == shooter
                    || !hit._k.func_70089_S() || hit._k.field_70128_L) {
                if (Boolean.getBoolean("reconstruction.gameplayProbe")) {
                    System.out.println("[RECONSTRUCTION COMBAT] traceMiss shooter=" + shooter.func_70005_c_()
                        + " blockedOrNoTarget=true");
                }
                return;
            }

            xuac target = hit._k;
            net.minecraft.util.wmya impact = hit._j;
            gloomyfolken.mods.weapon.trace.EntityTracer.dfak zone = hit._b == null
                ? gloomyfolken.mods.weapon.trace.EntityTracer.dfak._d : hit._b;
            if (impact != null && target.field_70131_O > 0.0f) {
                double ratio = (impact._d - target.field_70163_u) / (double) target.field_70131_O;
                if (ratio > 0.72) zone = gloomyfolken.mods.weapon.trace.EntityTracer.dfak._e;
                else if (ratio < 0.28) zone = gloomyfolken.mods.weapon.trace.EntityTracer.dfak._c;
            }
            double dx = shot._e()._c - target.field_70165_t;
            double dy = shot._e()._d - target.field_70163_u;
            double dz = shot._e()._e - target.field_70161_v;
            float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float healthBefore = target instanceof buao ? ((buao) target).func_110143_aJ() : -1.0f;
            float damage = weapon._a(shooter, distance, zone, target);
            if (!finite(damage) || damage <= 0.0f) {
                if (Boolean.getBoolean("reconstruction.gameplayProbe")) {
                    System.out.println("[RECONSTRUCTION COMBAT] rejected=weapon produced non-positive or nonfinite damage target="
                        + target.getClass().getSimpleName() + " damage=" + damage);
                }
                return;
            }

            target.field_70172_ad = 0;
            boolean damaged = applyAcceptedHit(target, net.minecraft.util.kjtv.func_76365_a(shooter),
                damage, shooter, held);
            if (damaged) {
                try { weapon._a((buao) shooter, target); }
                catch (Throwable ignored) { }
            }
            int observerPackets = impact == null ? 0 : broadcastHitObserver(shooter, impact, target, shot._h());
            if (Boolean.getBoolean("reconstruction.gameplayProbe")) {
                float healthAfter = target instanceof buao ? ((buao) target).func_110143_aJ() : -1.0f;
                System.out.println("[ServerPacketHandler] Weapon hit target " + target.getClass().getSimpleName()
                    + " (ID " + target.field_70157_k + ") zone=" + zone + " dmg=" + damage
                    + " damaged=" + damaged + " health=" + healthBefore + "->" + healthAfter
                    + " observerPackets=" + observerPackets);
            }
        } catch (Throwable failure) {
            System.err.println("[RECONSTRUCTION COMBAT] Hit request failed safely: " + failure.getClass().getSimpleName());
            if (Boolean.getBoolean("reconstruction.gameplayProbe")) failure.printStackTrace();
        }
    }

    /** Projectile mode has no retained server entity/impact path; never spend ammo for it. */
    public static void handleShoot(rakn request, jlas shooter) {
        if (request != null && shooter != null && Boolean.getBoolean("reconstruction.gameplayProbe")) {
            System.out.println("[RECONSTRUCTION COMBAT] rejected=projectile request has no server projectile implementation shooter="
                + shooter.func_70005_c_());
        }
    }

    /** Apply only native-supported modes for the held weapon to the server player controller. */
    public static void handleFireMode(tgqy request, jlas shooter) {
        if (request == null || shooter == null || shooter.field_70170_p == null
                || shooter.field_70170_p.field_72995_K || request._a == null) return;
        voib held = shooter.func_70694_bm();
        if (held == null || !(held._a() instanceof grkk)) {
            reject("fire mode request without held firearm");
            return;
        }
        grkk weapon = (grkk) held._a();
        gloomyfolken.mods.weapon.owap controller = gloomyfolken.mods.weapon.owap._b(shooter);
        if (controller == null || !weapon._A(held).contains(request._a)) {
            reject("fire mode is not supported by held firearm");
            return;
        }
        if (request._b && weapon._a(held, jjta.hrmt._b, dibb.class) == null) {
            reject("underbarrel mode requested without a registered launcher attachment");
            return;
        }
        try {
            FIRE_MODE.set(controller, request._a);
            GRENADE_MODE.setBoolean(controller, request._b);
            if (request._b) reject("underbarrel projectile mode recorded but unavailable on server");
            else if (Boolean.getBoolean("reconstruction.gameplayProbe")) {
                System.out.println("[RECONSTRUCTION COMBAT] fireMode shooter=" + shooter.func_70005_c_()
                    + " weapon=" + held._d + " mode=" + request._a);
            }
        } catch (IllegalAccessException failure) {
            reject("could not update server fire mode");
        }
    }

    /** Server-only melee path using the actual held melee item's native stats. */
    public static void handleMelee(ozfd request, jlas shooter) {
        if (request == null || shooter == null || shooter.field_70170_p == null
                || shooter.field_70170_p.field_72995_K || shooter.field_70128_L
                || !shooter.func_70089_S()) return;
        voib held = shooter.func_70694_bm();
        if (held == null || !(held._a() instanceof pleb)) {
            reject("melee request without a held melee weapon");
            return;
        }
        pleb weapon = (pleb) held._a();
        if (request._a) {
            reject("strong melee request lacks server-verifiable charge state");
            return;
        }
        int swing = request._a ? 1 : 0;
        Float[] damages = weapon._l();
        Integer[] delays = weapon._m();
        Integer[] cooldowns = weapon._n();
        if (damages == null || delays == null || cooldowns == null
                || swing >= damages.length || swing >= delays.length || swing >= cooldowns.length
                || damages[swing] == null || delays[swing] == null || cooldowns[swing] == null) {
            reject("held melee weapon has no native stats for requested swing");
            return;
        }
        float damage = damages[swing].floatValue();
        int animationDelay = delays[swing].intValue();
        int cooldownTicks = cooldowns[swing].intValue();
        float nativeReach = weapon._o();
        if (!finite(damage) || damage <= 0.0f || animationDelay < 0
                || cooldownTicks <= 0 || cooldownTicks > 2400
                || !finite(nativeReach) || nativeReach <= 0.0f || nativeReach > 6.0f) {
            reject("held melee weapon has invalid damage, reach, or cooldown stats");
            return;
        }

        double now = (double) shooter.field_70173_aa;
        synchronized (LocalCombatHooks.class) {
            Double next = NEXT_MELEE_TICK.get(shooter);
            if (next != null && now <= next.doubleValue()) {
                reject("native melee cooldown not ready");
                return;
            }
            NEXT_MELEE_TICK.put(shooter, Double.valueOf(now + (double) cooldownTicks));
        }

        net.minecraft.util.wmya origin = net.minecraft.util.wmya._a(shooter.field_70165_t,
            shooter.field_70163_u + shooter.func_70047_e(), shooter.field_70161_v);
        net.minecraft.util.wmya look = shooter.func_70040_Z();
        if (!finiteVector(origin) || !finiteVector(look)) {
            reject("invalid authoritative melee ray");
            return;
        }
        double length = Math.min((double) nativeReach, 6.0);
        net.minecraft.util.wmya end = net.minecraft.util.wmya._a(origin._c + look._c * length,
            origin._d + look._d * length, origin._e + look._e * length);
        net.minecraft.util.dfak bounds = net.minecraft.util.dfak._a(Math.min(origin._c, end._c) - 1.5,
            Math.min(origin._d, end._d) - 1.5, Math.min(origin._e, end._e) - 1.5,
            Math.max(origin._c, end._c) + 1.5, Math.max(origin._d, end._d) + 1.5,
            Math.max(origin._e, end._e) + 1.5);
        net.minecraft.util.ezgw blockHit = shooter.field_70170_p.func_72831_a(origin, end, false, true);
        double blockDistance = blockHit == null || blockHit._j == null ? Double.POSITIVE_INFINITY
            : Math.sqrt(origin._d(blockHit._j));
        xuac selected = null;
        net.minecraft.util.ezgw entityHit = null;
        double nearest = Double.POSITIVE_INFINITY;
        List<?> candidates = shooter.field_70170_p.func_72839_b(shooter, bounds);
        if (candidates != null) {
            for (Object item : candidates) {
                if (!(item instanceof buao)) continue;
                xuac candidate = (xuac) item;
                if (candidate == shooter || candidate.field_70128_L || !candidate.func_70089_S()
                        || candidate.field_70121_D == null) continue;
                net.minecraft.util.ezgw intercept = candidate.field_70121_D._b(0.4, 0.4, 0.4)._a(origin, end);
                if (intercept == null || intercept._j == null) continue;
                double distance = Math.sqrt(origin._d(intercept._j));
                if (distance < nearest && distance <= blockDistance) {
                    selected = candidate;
                    entityHit = intercept;
                    nearest = distance;
                }
            }
        }

        if (selected == null) {
            if (blockHit != null && blockHit._j != null) {
                try {
                    int blockId = shooter.field_70170_p.func_72798_a(blockHit._f, blockHit._g, blockHit._h);
                    int metadata = shooter.field_70170_p.func_72805_g(blockHit._f, blockHit._g, blockHit._h);
                    zzfd visual = new zzfd(shooter, blockHit._j, blockId, (byte) metadata, 0.0f)._a(true);
                    ServerPacketHandler.sendPacketToPlayer(shooter, new xael(visual));
                    visual._a(shooter);
                } catch (Throwable failure) {
                    if (Boolean.getBoolean("reconstruction.gameplayProbe")) failure.printStackTrace();
                }
            }
            if (Boolean.getBoolean("reconstruction.gameplayProbe")) {
                System.out.println("[RECONSTRUCTION COMBAT] meleeMiss shooter=" + shooter.func_70005_c_()
                    + " swing=" + swing + " range=" + nativeReach);
            }
            return;
        }

        if (selected instanceof gloomyfolken.mods.stalker.mobs.entity.EntityMutant) {
            float mutantMultiplier = weapon._x();
            if (!finite(mutantMultiplier) || mutantMultiplier <= 0.0f) {
                reject("invalid native mutant melee multiplier");
                return;
            }
            damage *= mutantMultiplier;
        }
        if (!finite(damage) || damage <= 0.0f) {
            reject("non-positive native melee damage");
            return;
        }
        float healthBefore = ((buao) selected).func_110143_aJ();
        selected.field_70172_ad = 0;
        boolean damaged = selected.func_70097_a(net.minecraft.util.kjtv.func_76365_a(shooter), damage);
        if (damaged) {
            try {
                zzfd visual = new zzfd(shooter, entityHit._j, selected, damage)._a(true);
                ServerPacketHandler.sendPacketToPlayer(shooter, new xael(visual));
                visual._a(shooter);
            } catch (Throwable failure) {
                if (Boolean.getBoolean("reconstruction.gameplayProbe")) failure.printStackTrace();
            }
            selected.func_70024_g(look._c * 0.3, 0.1, look._e * 0.3);
            if (held._k() > 0) held._a(swing == 1 ? 2 : 1, (buao) shooter);
        }
        if (Boolean.getBoolean("reconstruction.gameplayProbe")) {
            System.out.println("[ServerPacketHandler] Melee attack hit " + selected.getClass().getSimpleName()
                + " for " + damage + " damage (isStrong=" + request._a + ") damaged=" + damaged
                + " health=" + healthBefore + "->" + ((buao) selected).func_110143_aJ()
                + " range=" + nativeReach + " cooldownTicks=" + cooldownTicks);
        }
    }

    /** Broadcast the retained registered hit/tracer event after a valid hit. */
    public static int broadcastHitObserver(jlas shooter, net.minecraft.util.wmya impact,
                                           xuac target, float eventValue) {
        if (shooter == null || impact == null || target == null || !finiteVector(impact)
                || !finite(eventValue) || shooter.field_70170_p == null
                || shooter.field_70170_p.field_72995_K || target.field_70170_p != shooter.field_70170_p) return 0;
        int recipients = 0;
        jlrg server = jlrg._H();
        if (server != null && server.__ag() != null) {
            for (Object object : server.__ag()._e) {
                if (!(object instanceof jlas) || object == shooter) continue;
                jlas recipient = (jlas) object;
                if (recipient.field_71093_bK != shooter.field_71093_bK) continue;
                double startX = (double) (float) shooter.field_70165_t - recipient.field_70165_t;
                double startY = (double) (float) shooter.field_70163_u + shooter.func_70047_e() - recipient.field_70163_u;
                double startZ = (double) (float) shooter.field_70161_v - recipient.field_70161_v;
                double hitX = (double) (float) impact._c - recipient.field_70165_t;
                double hitY = (double) (float) impact._d - recipient.field_70163_u;
                double hitZ = (double) (float) impact._e - recipient.field_70161_v;
                double limit = 4096.0;
                if (startX * startX < limit && startY * startY < limit && startZ * startZ < limit
                        || hitX * hitX < limit && hitY * hitY < limit && hitZ * hitZ < limit) recipients++;
            }
        }
        new zzfd(shooter, impact, target, eventValue)._a(shooter);
        return recipients;
    }

    /** Apply damage only for a request admitted earlier in this server tick. */
    public static boolean applyAcceptedHit(xuac target, net.minecraft.util.kjtv source, float amount,
                                           jlas shooter, voib held) {
        if (target == null || source == null || shooter == null || held == null
                || !(held._a() instanceof grkk) || !finite(amount) || amount <= 0.0f
                || target.field_70170_p == null || target.field_70170_p.field_72995_K
                || shooter.field_70170_p != target.field_70170_p) return false;

        synchronized (LocalCombatHooks.class) {
            Long acceptedTick = LAST_ACCEPTED_TICK.get(shooter);
            lrhp acceptedWeapon = LAST_ACCEPTED_WEAPON.get(shooter);
            if (acceptedTick == null || acceptedTick.longValue() != shooter.field_70170_p.func_82737_E()
                    || acceptedWeapon != held._a()) return false;
        }

        // This deliberately does not alter creative capability or invulnerability.
        return target.func_70097_a(source, amount);
    }

    private static boolean finiteVector(net.minecraft.util.wmya vector) {
        return vector != null && finite(vector._c) && finite(vector._d) && finite(vector._e)
                && Math.abs(vector._c) <= MAX_COORDINATE && Math.abs(vector._e) <= MAX_COORDINATE
                && Math.abs(vector._d) <= 4096.0;
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field result = type.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}

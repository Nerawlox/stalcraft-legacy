import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Preserves unsupported scoreboard names and metadata without inventing runtime behavior. */
public final class LocalScoreboardHooks {
    private static final Map<String, ngya> PRESERVED = new ConcurrentHashMap<String, ngya>();
    private static final Map<rtfd, rtag> ORIGINAL_DATA =
        Collections.synchronizedMap(new WeakHashMap<rtfd, rtag>());
    private static final String[] ROOT_KEYS = {"Objectives", "PlayerScores", "Teams", "DisplaySlots"};
    private static final String[] OBJECTIVE_KEYS = {"Name", "CriteriaName", "DisplayName"};
    private static final String[] SCORE_KEYS = {"Name", "Objective", "Score"};
    private static final String[] TEAM_KEYS = {"Name", "DisplayName", "Prefix", "Suffix",
        "AllowFriendlyFire", "SeeFriendlyInvisibles", "Players"};

    private LocalScoreboardHooks() {
    }

    public static Object lookup(Map<Object, Object> criteria, Object key) {
        Object registered = criteria.get(key);
        if (registered != null || !(key instanceof String)) {
            return registered;
        }

        String name = (String) key;
        PreservedCriterion created = new PreservedCriterion(name);
        ngya existing = PRESERVED.putIfAbsent(name, created);
        if (existing == null) {
            System.err.println("[LocalScoreboardHooks] Preserving unresolved scoreboard criterion: " + name);
            return created;
        }
        return existing;
    }

    /** Capture original unknown NBT fields before the native loader consumes records. */
    public static void captureOriginalData(rtfd scoreboard, rtag original) {
        synchronized (ORIGINAL_DATA) {
            ORIGINAL_DATA.put(scoreboard, (rtag) original._c());
        }
    }

    /**
     * Merge storage-only unknown fields into the current native serialization. Existing native
     * values win, and record fields are copied only into records that still exist. The old
     * runtime does not implement newer objective/team semantics such as RenderType or visibility.
     */
    public static void mergeOriginalData(rtfd scoreboard, rtag current) {
        if (scoreboard._a == null) {
            return;
        }
        rtag original;
        synchronized (ORIGINAL_DATA) {
            original = ORIGINAL_DATA.get(scoreboard);
        }
        if (original == null) {
            return;
        }
        for (Object value : original._d()) {
            jlim tag = (jlim) value;
            String key = tag._b();
            if (isKnown(key, ROOT_KEYS)) {
                continue;
            }
            if (!current._c.containsKey(key)) {
                current._a(key, tag._c());
            }
        }
        mergeRecords(original, current, "Objectives", "Name", OBJECTIVE_KEYS);
        mergeRecords(original, current, "PlayerScores", "Name", "Objective", SCORE_KEYS);
        mergeRecords(original, current, "Teams", "Name", TEAM_KEYS);
        mergeDisplaySlots(original, current);
    }

    private static void mergeRecords(rtag original, rtag current, String listName,
                                     String keyOne, String[] nativeKeys) {
        mergeRecords(original, current, listName, keyOne, null, nativeKeys);
    }

    private static void mergeRecords(rtag original, rtag current, String listName,
                                     String keyOne, String keyTwo, String[] nativeKeys) {
        if (!original._c(listName) || !current._c(listName)) {
            return;
        }
        aroe source = original._n(listName);
        aroe target = current._n(listName);
        for (int i = 0; i < source._d(); i++) {
            rtag oldRecord = (rtag) source._b(i);
            rtag liveRecord = findRecord(target, oldRecord, keyOne, keyTwo);
            if (liveRecord == null) {
                continue;
            }
            for (Object value : oldRecord._d()) {
                jlim tag = (jlim) value;
                String key = tag._b();
                if (!isKnown(key, nativeKeys) && !liveRecord._c.containsKey(key)) {
                    liveRecord._a(key, tag._c());
                }
            }
        }
    }

    private static rtag findRecord(aroe records, rtag original, String keyOne, String keyTwo) {
        String valueOne = original._j(keyOne);
        String valueTwo = keyTwo == null ? null : original._j(keyTwo);
        for (int i = 0; i < records._d(); i++) {
            rtag candidate = (rtag) records._b(i);
            if (!valueOne.equals(candidate._j(keyOne))) {
                continue;
            }
            if (keyTwo == null || valueTwo.equals(candidate._j(keyTwo))) {
                return candidate;
            }
        }
        return null;
    }

    private static void mergeDisplaySlots(rtag original, rtag current) {
        if (!original._c("DisplaySlots") || !current._c("DisplaySlots")) {
            return;
        }
        rtag oldSlots = original._m("DisplaySlots");
        rtag liveSlots = current._m("DisplaySlots");
        for (Object value : oldSlots._d()) {
            jlim tag = (jlim) value;
            String key = tag._b();
            if (!"slot_0".equals(key) && !"slot_1".equals(key) && !"slot_2".equals(key) &&
                    !liveSlots._c.containsKey(key)) {
                liveSlots._a(key, tag._c());
            }
        }
    }

    private static boolean isKnown(String key, String[] knownKeys) {
        for (String known : knownKeys) {
            if (known.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** Storage adapter only; it preserves the serialized key and provides no stat evaluator. */
    private static final class PreservedCriterion implements ngya {
        private final String name;

        private PreservedCriterion(String name) {
            this.name = name;
        }

        @Override
        public String _a() {
            return this.name;
        }

        @SuppressWarnings("rawtypes")
        @Override
        public int _a(List values) {
            return 0;
        }

        @Override
        public boolean _b() {
            return true;
        }
    }
}

r"""Statically verify the audited class changes in the community 2019 overlay.

This reads class files only. It never loads or executes game classes.
Reproducible invocation (PowerShell):
  python scripts/verify_2019_overlay.py --overlay .local/overlay --classpath .local/lab/classpath
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile

from classfile_index import parse_class


def read_entry(jar, name):
    with zipfile.ZipFile(jar) as archive:
        return archive.read(name)


def class_from_dir(directory, name):
    return (directory / (name + ".class")).read_bytes()


def u2(data, pos):
    return int.from_bytes(data[pos:pos + 2], "big")


def instruction_stream(info, method):
    """Yield (opcode, symbolic operand) pairs, validating instruction bounds."""
    code, cp, pos, result = method.code, info.constant_pool, 0, []

    def utf(index):
        row = cp[index]
        if not row or row[0] != 1:
            raise ValueError("bad UTF8 CP index")
        return row[1].decode("utf-8")

    def member(index):
        row = cp[index]
        if not row or row[0] not in (9, 10, 11):
            raise ValueError("bad member CP index")
        owner = cp[row[1]]
        nat = cp[row[2]]
        return (utf(owner[1]), utf(nat[1]), utf(nat[2]), row[0])

    fixed = {}
    for op in range(256):
        fixed[op] = 1
    for op in (16, 18, 21, 22, 23, 24, 25, 54, 55, 56, 57, 58, 169, 188):
        fixed[op] = 2
    for op in (17, 19, 20, 132, 178, 179, 180, 181, 182, 183, 184, 187, 189, 192, 193):
        fixed[op] = 3
    for op in (185, 186, 197):
        fixed[op] = 5 if op != 197 else 4
    for op in range(153, 169):
        fixed[op] = 3
    for op in (198, 199, 167, 168):
        fixed[op] = 3
    fixed[200] = fixed[201] = 5

    while pos < len(code):
        start, op = pos, code[pos]
        size = fixed[op]
        if op == 196:
            if pos + 2 > len(code):
                raise ValueError("short wide instruction")
            size = 6 if code[pos + 1] == 132 else 4
        elif op in (170, 171):
            aligned = (pos + 4) & ~3
            if aligned + (12 if op == 170 else 8) > len(code):
                raise ValueError("short switch instruction")
            if op == 170:
                low, high = struct.unpack(">ii", code[aligned + 4:aligned + 12])
                size = aligned + 12 + 4 * (high - low + 1) - pos
            else:
                count = int.from_bytes(code[aligned + 4:aligned + 8], "big", signed=True)
                size = aligned + 8 + 8 * count - pos
        if size < 1 or pos + size > len(code):
            raise ValueError("invalid instruction span at %d" % pos)
        raw = code[pos + 1:pos + size]
        operand = raw.hex()
        if op in (18, 19, 20):
            index = raw[0] if op == 18 else int.from_bytes(raw[:2], "big")
            c = cp[index]
            operand = (c[0], utf(c[1]) if c[0] in (7, 8, 16, 19, 20) else c[1].hex())
        elif 178 <= op <= 185:
            operand = member(int.from_bytes(raw[:2], "big"))
        elif op == 186:
            row = cp[int.from_bytes(raw[:2], "big")]
            nat = cp[row[2]]
            operand = ("invokedynamic", utf(nat[1]), utf(nat[2]))
        elif op in (187, 189, 192, 193):
            row = cp[int.from_bytes(raw[:2], "big")]
            operand = utf(row[1])
        elif op == 197:
            row = cp[int.from_bytes(raw[:2], "big")]
            operand = (utf(row[1]), raw[2])
        result.append((op, operand))
        pos = start + size
    return result


def members(info):
    return {(m.name, m.descriptor): m for m in info.methods}


def fields(info):
    return {(m.name, m.descriptor): m.access for m in info.fields}


def assert_structure(before, after, allow_method_key_changes=()):
    if (before.name, before.superclass, before.interfaces, before.access) != \
            (after.name, after.superclass, after.interfaces, after.access):
        raise AssertionError("class identity/header changed: " + before.name)
    if fields(before) != fields(after):
        raise AssertionError("field identity/access changed: " + before.name)
    old, new = members(before), members(after)
    allowed = set(allow_method_key_changes)
    if set(old) - allowed != set(new) - allowed:
        raise AssertionError("method inventory changed unexpectedly: " + before.name)
    for key in set(old) & set(new):
        if old[key].access != new[key].access and key not in allowed:
            raise AssertionError("method access changed: %s %r" % (before.name, key))
    return old, new


def find_method(info, name, desc):
    matches = [m for m in info.methods if (m.name, m.descriptor) == (name, desc)]
    if len(matches) != 1:
        raise AssertionError("expected one method %s.%s%s, got %d" % (info.name, name, desc, len(matches)))
    return matches[0]


def check_common(before_data, after_data, label, allowed=()):
    before, after = parse_class(before_data), parse_class(after_data)
    old, new = assert_structure(before, after, allowed)
    changed, same = [], 0
    for key in set(old) & set(new):
        if key in allowed:
            continue
        if old[key].code != new[key].code:
            raise AssertionError("unapproved Code change in %s: %r" % (label, key))
        same += 1
    return before, after, old, new, {"class": before.name, "unchanged_method_code": same}


def assert_once(tokens, target, label):
    count = tokens.count(target)
    if count != 1:
        raise AssertionError("%s expected once, found %d: %r" % (label, count, target))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--overlay", type=Path, required=True)
    ap.add_argument("--classpath", type=Path, required=True)
    args = ap.parse_args()
    overlay, cp = args.overlay.resolve(), args.classpath.resolve()
    basejar, patchjar = cp / "classes.jar", cp / "offline-patches.jar"
    reports = []

    # Profiler predicate: this is the sole method replacement previously guarded by builder.
    before, after, old, new, report = check_common(
        read_entry(basejar, "izmo.class"), class_from_dir(overlay, "izmo"), "izmo", {("_l", "()Z")})
    a, b = find_method(before, "_l", "()Z"), find_method(after, "_l", "()Z")
    if instruction_stream(after, b) != [(184, ("LocalProfilerHooks", "isClientThread", "()Z", 10)), (172, "")]:
        raise AssertionError("unexpected profiler predicate body")
    reports.append(report)

    # Client tick hook: exactly one offline call is redirected and all other opcodes stay exact.
    before, after, old, new, report = check_common(
        read_entry(patchjar, "net/minecraft/client/qlfw.class"),
        class_from_dir(overlay, "net/minecraft/client/qlfw"), "qlfw", {("_w", "()V")})
    tickkey = ("_w", "()V")
    old_tokens = instruction_stream(before, find_method(before, *tickkey))
    new_tokens = instruction_stream(after, find_method(after, *tickkey))
    old_calls = [x for x in old_tokens if x[0] in (182, 183, 184, 185)]
    new_calls = [x for x in new_tokens if x[0] in (182, 183, 184, 185)]
    if len(old_tokens) != len(new_tokens) or len(old_calls) != len(new_calls):
        raise AssertionError("qlfw tick changed instruction shape")
    differences = [(x, y) for x, y in zip(old_tokens, new_tokens) if x != y]
    if len(differences) != 1 or differences[0][0][1] != ("local/stalcraft/OfflineHook", "tick", "(Ljava/lang/Object;)V", 10) \
            or differences[0][1][1] != ("LocalClientProbe", "tick", "(Ljava/lang/Object;)V", 10):
        raise AssertionError("unexpected qlfw offline tick redirection: %r" % (differences,))
    reports.append(report)

    # Server tick hook: retain all original instructions and inject exactly one hook before RETURN.
    before, after, old, new, report = check_common(
        read_entry(basejar, "jlrg.class"), class_from_dir(overlay, "jlrg"), "jlrg", {("_C", "()V")})
    key = ("_C", "()V")
    old_tokens = instruction_stream(before, find_method(before, *key))
    new_tokens = instruction_stream(after, find_method(after, *key))
    hook = (184, ("LocalServerProbe", "tick", "(Ljlrg;)V", 10))
    if old_tokens.count((177, "")) != 1 or new_tokens.count(hook) != 1:
        raise AssertionError("jlrg must have one return and one server hook")
    expected = []
    for op in old_tokens:
        if op == (177, ""):
            expected.extend(((42, ""), hook))
        expected.append(op)
    if new_tokens != expected:
        raise AssertionError("jlrg changed instructions beyond the audited tick insertion")
    reports.append(report)

    # BetterGrass: wrapper gates client worlds, and the original implementation survives exactly.
    bgname = "poersch/minecraft/bettergrassandleaves/renderer/BlockRendererList"
    bgdesc = "(Ltxrt;Llrzy;IIILxuac;)Z"
    before, after, old, new, report = check_common(
        read_entry(basejar, bgname + ".class"), class_from_dir(overlay, bgname), "BetterGrass",
        {("onEntityWalkingHook", bgdesc), ("reconstruction$clientWalking", bgdesc)})
    original = find_method(before, "onEntityWalkingHook", bgdesc)
    wrapper = find_method(after, "onEntityWalkingHook", bgdesc)
    preserved = find_method(after, "reconstruction$clientWalking", bgdesc)
    if instruction_stream(after, preserved) != instruction_stream(before, original):
        raise AssertionError("BetterGrass original body not preserved under reconstruction$clientWalking")
    expected = [
        (43, ""),  # aload_1
        (180, ("lrzy", "field_72995_K", "Z", 9)),
        (154, "0005"),  # ifne client path
        (3, ""), (172, ""),
        (42, ""), (43, ""), (28, ""), (29, ""), (21, "04"), (25, "05"),
        (184, (bgname, "reconstruction$clientWalking", bgdesc, 10)),
        (172, ""),
    ]
    if instruction_stream(after, wrapper) != expected:
        raise AssertionError("unexpected BetterGrass server-world guard: %r" % (instruction_stream(after, wrapper),))
    reports.append(report)

    # NettyHooks: exact single static delegation replacement.
    nname = "gloomyfolken/mods/asm/NettyHooks"
    before, after, old, new, report = check_common(
        read_entry(patchjar, nname + ".class"), class_from_dir(overlay, nname), "NettyHooks",
        {("getServerIP", "()Ljava/lang/String;")})
    method = find_method(after, "getServerIP", "()Ljava/lang/String;")
    if instruction_stream(after, method) != [
            (184, ("LocalClientProbe", "serverAddress", "()Ljava/lang/String;", 10)), (176, "")]:
        raise AssertionError("unexpected NettyHooks getServerIP replacement")
    reports.append(report)

    # Scoreboard criteria and unknown NBT fields: retain the instruction-width-compatible
    # criterion lookup change, snapshot original data on load, and merge at the final save return.
    scoreboard = "rtfd"
    criterion_method = ("_b", "(Laroe;)V")
    map_lookup = (185, ("java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", 11))
    preserve_lookup = (184, ("LocalScoreboardHooks", "lookup",
                             "(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;", 10))
    scoreboard_before_raw = read_entry(basejar, scoreboard + ".class")
    scoreboard_before = parse_class(scoreboard_before_raw)
    capture_method = ("func_76184_a", "(Lrtag;)V")
    merge_method = ("func_76187_b", "(Lrtag;)V")
    scoreboard_methods = {(m.name, m.descriptor) for m in scoreboard_before.methods}
    if criterion_method not in scoreboard_methods:
        raise AssertionError("original scoreboard criteria reader missing")
    if capture_method not in scoreboard_methods or merge_method not in scoreboard_methods:
        raise AssertionError("original scoreboard team metadata methods missing")
    before, after, old, new, report = check_common(
        scoreboard_before_raw, class_from_dir(overlay, scoreboard), scoreboard,
        {criterion_method, capture_method, merge_method})
    old_criteria = instruction_stream(scoreboard_before, find_method(scoreboard_before, *criterion_method))
    new_criteria = instruction_stream(after, find_method(after, *criterion_method))
    assert_once(old_criteria, map_lookup, "original scoreboard criterion lookup")
    expected_criteria = list(old_criteria)
    index = expected_criteria.index(map_lookup)
    expected_criteria[index:index + 1] = [preserve_lookup, (0, ""), (0, "")]
    if new_criteria != expected_criteria:
        raise AssertionError("scoreboard criterion method changed beyond lookup redirection plus two NOPs")
    hook_info = parse_class(class_from_dir(overlay, "LocalScoreboardHooks"))
    lookup_method = find_method(hook_info, "lookup", "(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;")
    if not lookup_method.access & 0x0008:
        raise AssertionError("scoreboard lookup hook must be static")
    criterion_info = parse_class(class_from_dir(overlay, "LocalScoreboardHooks$PreservedCriterion"))
    if "ngya" not in criterion_info.interfaces:
        raise AssertionError("preserved scoreboard criterion must implement ngya")
    capture_hook = (184, ("LocalScoreboardHooks", "captureOriginalData", "(Lrtfd;Lrtag;)V", 10))
    before_capture = instruction_stream(before, find_method(before, *capture_method))
    after_capture = instruction_stream(after, find_method(after, *capture_method))
    capture_prefix = [(42, ""), (43, ""), capture_hook]
    if after_capture[:3] != capture_prefix or after_capture[3:] != before_capture:
        raise AssertionError("scoreboard NBT snapshot must be the exact load-method entry prefix")
    merge_hook = (184, ("LocalScoreboardHooks", "mergeOriginalData", "(Lrtfd;Lrtag;)V", 10))
    before_merge = instruction_stream(before, find_method(before, *merge_method))
    after_merge = instruction_stream(after, find_method(after, *merge_method))
    return_positions = [i for i, token in enumerate(before_merge) if token == (177, "")]
    if len(return_positions) != 2:
        raise AssertionError("expected early and final scoreboard save returns")
    expected_merge = list(before_merge)
    final_return = return_positions[-1]
    expected_merge[final_return:final_return] = [(42, ""), (43, ""), merge_hook]
    if after_merge != expected_merge:
        raise AssertionError("scoreboard NBT merge must precede only the final save return")
    for hook_name, hook_desc in (("captureOriginalData", "(Lrtfd;Lrtag;)V"),
                                 ("mergeOriginalData", "(Lrtfd;Lrtag;)V")):
        if not find_method(hook_info, hook_name, hook_desc).access & 0x0008:
            raise AssertionError("scoreboard NBT hook must be static: " + hook_name)
    reports.append({"class": scoreboard, "method": criterion_method[0],
                    "changed_call": [map_lookup, preserve_lookup],
                    "added_nops": 2, "all_non_target_methods_unchanged": True,
                    "unchanged_method_code": report["unchanged_method_code"],
                    "adapter_implements_ngya": True,
                    "original_nbt_snapshot_at_load_entry": True,
                    "unknown_nbt_merge_before_final_save_return": True,
                    "save_branch_operands_unchanged": True,
                    "unknown_nbt_storage_only": True})

    # Server packet bridge: old handler preserved, wrapper delegates, two client-only stat calls rerouted,
    # and exactly one invalid rakn return-broadcast redirected to a no-op.
    handler = "ServerPacketHandler"
    descriptor = "(Lgloomyfolken/bundle/common/core/dfaj;Lswfs;)V"
    handler_before_raw = read_entry(patchjar, handler + ".class")
    handler_before = parse_class(handler_before_raw)
    shoot_method = ("handleWeaponShoot", "(Lrakn;Ljlas;)V")
    shot_broadcast = (184, ("cpw/mods/fml/common/network/PacketDispatcher", "sendPacketToAllInDimension", "(Lizjo;I)V", 10))
    discard_shot = (184, ("LocalServerPlayerHooks", "discardInvalidShotBroadcast", "(Lizjo;I)V", 10))
    if shoot_method not in {(m.name, m.descriptor) for m in handler_before.methods}:
        raise AssertionError("original handleWeaponShoot signature missing")
    stat_method_keys = set()
    stat_call = (182, ("gloomyfolken/mods/stalker/misc/qlfw", "_f", "()V", 10))
    for candidate in handler_before.methods:
        if candidate.code is not None and stat_call in instruction_stream(handler_before, candidate):
            stat_method_keys.add((candidate.name, candidate.descriptor))
    if len(stat_method_keys) != 2:
        raise AssertionError("expected exactly two methods with original client stat calls")
    before, after, old, new, report = check_common(
        handler_before_raw, class_from_dir(overlay, handler), handler,
        {("handle", descriptor), ("reconstruction$handle", descriptor), shoot_method} | stat_method_keys)
    if ("reconstruction$handle", descriptor) not in new:
        raise AssertionError("original ServerPacketHandler.handle missing under reconstruction$handle")
    old_handler = find_method(before, "handle", descriptor)
    preserved = find_method(after, "reconstruction$handle", descriptor)
    if instruction_stream(before, old_handler) != instruction_stream(after, preserved):
        raise AssertionError("original packet handler body not preserved")
    if instruction_stream(after, find_method(after, "handle", descriptor)) != [
            (42, ""), (43, ""), (184, ("LocalPacketBridge", "handle", descriptor, 10)), (177, "")]:
        raise AssertionError("unexpected ServerPacketHandler bridge body")
    old_calls = []
    for method in before.methods:
        if method.code is not None:
            old_calls.extend((method.name, token) for token in instruction_stream(before, method)
                             if token == (182, ("gloomyfolken/mods/stalker/misc/qlfw", "_f", "()V", 10)))
    if len(old_calls) != 2:
        raise AssertionError("expected exactly two original client stat calls, got %d" % len(old_calls))
    new_calls = []
    for method in after.methods:
        if method.code is not None:
            new_calls.extend((method.name, token) for token in instruction_stream(after, method)
                             if token[0] in (182, 183, 184, 185) and token[1][0] == "LocalServerPlayerHooks")
    expected_hooks = {("LocalServerPlayerHooks", "refresh", "(Lgloomyfolken/mods/stalker/misc/qlfw;)V", 10),
                      ("LocalServerPlayerHooks", "armor", "(Lgloomyfolken/mods/stalker/misc/qlfw;)V", 10),
                      ("LocalServerPlayerHooks", "discardInvalidShotBroadcast", "(Lizjo;I)V", 10)}
    if len(new_calls) != 3 or {token[1] for _, token in new_calls} != expected_hooks:
        raise AssertionError("expected refresh, armor, and shot-broadcast hooks, got %r" % (new_calls,))
    old_shoot = instruction_stream(handler_before, find_method(handler_before, *shoot_method))
    new_shoot = instruction_stream(after, find_method(after, *shoot_method))
    assert_once(old_shoot, shot_broadcast, "original handleWeaponShoot dimension broadcast")
    if old_shoot.count(discard_shot) != 0:
        raise AssertionError("original handleWeaponShoot already contains the discard hook")
    expected_shoot = list(old_shoot)
    expected_shoot[expected_shoot.index(shot_broadcast)] = discard_shot
    if new_shoot != expected_shoot:
        raise AssertionError("handleWeaponShoot changed beyond the one broadcast-call redirection")
    hook_info = parse_class(class_from_dir(overlay, "LocalServerPlayerHooks"))
    discard_method = find_method(hook_info, "discardInvalidShotBroadcast", "(Lizjo;I)V")
    if instruction_stream(hook_info, discard_method) != [(177, "")]:
        raise AssertionError("discardInvalidShotBroadcast must remain a side-effect-free no-op")
    reports.append({"class": handler, "method": shoot_method[0],
                    "changed_call": [shot_broadcast, discard_shot],
                    "all_other_instructions_unchanged": True,
                    "discard_hook_is_noop": True})
    hook_for = {"refreshPlayerStats": (184, ("LocalServerPlayerHooks", "refresh",
                                                   "(Lgloomyfolken/mods/stalker/misc/qlfw;)V", 10)),
                "applyArmorToPlayer": (184, ("LocalServerPlayerHooks", "armor",
                                                 "(Lgloomyfolken/mods/stalker/misc/qlfw;)V", 10))}
    for method_key in stat_method_keys:
        if method_key[0] not in hook_for:
            raise AssertionError("unexpected method contains client stat call: %r" % (method_key,))
        before_tokens = instruction_stream(before, find_method(before, *method_key))
        after_tokens = instruction_stream(after, find_method(after, *method_key))
        if before_tokens.count(stat_call) != 1:
            raise AssertionError("expected one client stat call in %r" % (method_key,))
        expected_tokens = list(before_tokens)
        expected_tokens[expected_tokens.index(stat_call)] = hook_for[method_key[0]]
        if after_tokens != expected_tokens:
            raise AssertionError("unexpected code changes in server stat method %r" % (method_key,))
    reports.append(report)

    # Remote chat hook: preserve opcode/descriptor and change only the call owner.
    chat_name = "mods/chat/client/screen/GuiChatActive"
    chat_before_raw = read_entry(patchjar, chat_name + ".class")
    chat_before = parse_class(chat_before_raw)
    offline_chat_call = (184, ("local/stalcraft/OfflineHook", "sendLocalChat", "(Ljava/lang/Object;)V", 10))
    chat_methods = []
    for candidate in chat_before.methods:
        if candidate.code is not None and offline_chat_call in instruction_stream(chat_before, candidate):
            chat_methods.append((candidate.name, candidate.descriptor))
    if len(chat_methods) != 1:
        raise AssertionError("expected one method containing OfflineHook.sendLocalChat, got %r" % (chat_methods,))
    before, after, old, new, report = check_common(
        chat_before_raw, class_from_dir(overlay, chat_name), "GuiChatActive", set(chat_methods))
    method_key = chat_methods[0]
    before_tokens = instruction_stream(before, find_method(before, *method_key))
    after_tokens = instruction_stream(after, find_method(after, *method_key))
    if before_tokens.count(offline_chat_call) != 1:
        raise AssertionError("expected one original OfflineHook chat call")
    redirected = (184, ("LocalChatHooks", "sendLocalChat", "(Ljava/lang/Object;)V", 10))
    expected_tokens = list(before_tokens)
    expected_tokens[expected_tokens.index(offline_chat_call)] = redirected
    if after_tokens != expected_tokens:
        raise AssertionError("chat hook changed more than the audited call owner")
    reports.append(report)

    # Only two lifecycle call owners change; saved data and original spawn logic remain intact.
    go_name = "mods/gameobjects/world/GOWorldData"
    go_raw = read_entry(patchjar, go_name + ".class")
    go_info = parse_class(go_raw)
    go_calls = {
        (184, ("ServerPacketHandler", "beforeSpawnGameObject", "(Lmods/gameobjects/world/GOWorldData;Lmods/gameobjects/world/GOInstance;)V", 10)),
        (184, ("ServerPacketHandler", "onWorldDataSet", "(Lmods/gameobjects/world/GOWorldData;Llrzy;)V", 10)),
    }
    go_methods = {(method.name, method.descriptor) for method in go_info.methods
                  if method.code is not None and any(call in instruction_stream(go_info, method) for call in go_calls)}
    if len(go_methods) != 2:
        raise AssertionError("expected two game object lifecycle methods")
    before, after, old, new, report = check_common(go_raw, class_from_dir(overlay, go_name), "GOWorldData", go_methods)
    found = []
    for key in go_methods:
        tokens = instruction_stream(before, find_method(before, *key))
        expected = []
        for opcode, operand in tokens:
            if (opcode, operand) in go_calls:
                found.append((opcode, operand))
                operand = ("LocalGameObjectHooks",) + operand[1:]
            expected.append((opcode, operand))
        if instruction_stream(after, find_method(after, *key)) != expected:
            raise AssertionError("unexpected game object lifecycle code changes")
    if len(found) != 2 or set(found) != go_calls:
        raise AssertionError("game object hooks not uniquely redirected")
    reports.append(report)

    output = {"result": "PASS", "overlay": str(overlay), "classpath": str(cp),
              "checks": reports,
              "opaque_attributes": "Not compared byte-for-byte; classfile_index parses declarations and Code arrays, not raw non-Code attribute payloads. ASM reserialization may change their encoding. No claim of opaque-attribute identity.",
              "sha256": {"classes.jar": hashlib.sha256(basejar.read_bytes()).hexdigest(),
                        "offline-patches.jar": hashlib.sha256(patchjar.read_bytes()).hexdigest()}}
    print(json.dumps(output, indent=2))


if __name__ == "__main__":
    main()

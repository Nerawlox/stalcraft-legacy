"""Inventory the supplied offline overlay without loading any game classes.

Compare classes.jar then libs.jar in the distributor's classpath order. Instruction
fingerprints resolve constant-pool references, bootstrap arguments and exception
handlers; equal Code bytes alone would not establish equal instructions. Debug,
annotation, StackMapTable and other opaque attributes are outside that comparison.
The supplied base may itself be modified: this is not a pristine-release diff.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import struct
import zipfile

from classfile_index import parse_class

PINS = {
    "classes.jar": "d91d22576c994126ef90818fee4cd490b71cd62f3341464e630404293ab1f4cd",
    "libs.jar": "1738611cd0bbbaf9fdf19ff9abc4dae9040ae6db5f3c68463fa27b822eebafe0",
    "offline-patches.jar": "14388ea0e1a17ef054aab032588aa72ff4947d57f5532c85c94b49eb35d390f9",
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def fingerprint(value):
    return digest(json.dumps(value, sort_keys=True, separators=(",", ":")).encode())


class Reader:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def take(self, n):
        if n < 0 or self.pos + n > len(self.data):
            raise ValueError("class/attribute bounds exceeded")
        result = self.data[self.pos:self.pos + n]
        self.pos += n
        return result

    def u2(self):
        return int.from_bytes(self.take(2), "big")

    def u4(self):
        return int.from_bytes(self.take(4), "big")

    def done(self):
        if self.pos != len(self.data):
            raise ValueError("unconsumed class/attribute bytes")


class Detail:
    def __init__(self, data):
        self.info = parse_class(data)
        self.cp = self.info.constant_pool
        pool_end = 10
        for row in self.cp[1:]:
            if row:
                pool_end += 1 + (2 + len(row[1]) if row[0] == 1 else {
                    3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4,
                    11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2
                }[row[0]])
        r = Reader(data[pool_end:])
        r.take(6)
        r.take(r.u2() * 2)
        self.field_attrs, self.method_attrs = {}, {}
        for result in (self.field_attrs, self.method_attrs):
            for _ in range(r.u2()):
                flags, name, desc = r.u2(), self.utf(r.u2()), self.utf(r.u2())
                key = (name, desc)
                if key in result:
                    raise ValueError("duplicate member")
                result[key] = (flags, self.attrs(r))
        self.class_attrs = self.attrs(r)
        r.done()
        self.bootstraps = []
        if "BootstrapMethods" in self.class_attrs:
            b = Reader(self.class_attrs["BootstrapMethods"])
            for _ in range(b.u2()):
                handle = b.u2()
                self.bootstraps.append((handle, [b.u2() for _ in range(b.u2())]))
            b.done()

    def utf(self, i):
        if not self.cp[i] or self.cp[i][0] != 1:
            raise ValueError("not a UTF8 constant")
        return self.cp[i][1].decode("utf-8")

    def attrs(self, r):
        result = {}
        for _ in range(r.u2()):
            name = self.utf(r.u2())
            value = r.take(r.u4())
            if name in result:
                raise ValueError("duplicate attribute: " + name)
            result[name] = value
        return result

    def constant(self, i, seen=()):
        if i in seen or not 0 < i < len(self.cp) or not self.cp[i]:
            raise ValueError("cyclic/invalid constant reference")
        row = self.cp[i]
        tag = row[0]
        resolve = lambda index: self.constant(index, seen + (i,))
        if tag in (1, 3, 4, 5, 6):
            return [tag, row[1].hex()]
        if tag in (7, 8, 16, 19, 20):
            return [tag, resolve(row[1])]
        if tag in (9, 10, 11, 12):
            return [tag, resolve(row[1]), resolve(row[2])]
        if tag == 15:
            return [tag, row[1], resolve(row[2])]
        if tag in (17, 18):
            handle, arguments = self.bootstraps[row[1]]
            return [tag, resolve(row[2]), resolve(handle), [resolve(a) for a in arguments]]
        raise ValueError("unsupported constant")

    def fields(self):
        result = {}
        for key, (flags, attrs) in self.field_attrs.items():
            constant = attrs.get("ConstantValue")
            result[key] = [flags, self.constant(int.from_bytes(constant, "big")) if constant else None]
        return result

    def method(self, key):
        flags, attrs = self.method_attrs[key]
        payload = attrs.get("Code")
        if payload is None:
            return {"access": flags, "code": None, "kind": "native" if flags & 0x100 else "abstract_or_no_code", "calls": []}
        r = Reader(payload)
        max_stack, max_locals = r.u2(), r.u2()
        code = r.take(r.u4())
        handlers = [tuple(r.u2() for _ in range(4)) for _ in range(r.u2())]
        self.attrs(r)
        r.done()
        tokens, offsets, calls = self.instructions(code)
        offsets[len(code)] = len(tokens)
        def target(offset, allow_end=False):
            if offset not in offsets or (offset == len(code) and not allow_end):
                raise ValueError("branch/handler outside instruction boundaries")
            return offsets[offset]
        for token in tokens:
            if token[1][0] == "branch":
                token[1] = ["target", target(token[1][1])]
            elif token[1][0] == "switch":
                token[1] = ["switch", target(token[1][1]), [[k, target(v)] for k, v in token[1][2]]]
        exceptions = [[target(a), target(b, allow_end=True), target(c), self.constant(d) if d else None] for a, b, c, d in handlers]
        plain = [op for op, _ in tokens]
        kind = "return_only" if plain == [177] else "constant_return" if len(plain) == 2 and plain[0] in range(1, 21) and plain[1] in range(172, 177) else "body"
        return {"access": flags, "code": fingerprint([tokens, exceptions]), "kind": kind,
                "instruction_count": len(tokens), "max_stack": max_stack, "max_locals": max_locals,
                "exception_handler_count": len(handlers), "calls": sorted(set(calls))}

    def instructions(self, code):
        tokens, offsets, calls, p = [], {}, [], 0
        sizes = {op: 1 for op in range(202)}
        for op in (16, 18, 21, 22, 23, 24, 25, 54, 55, 56, 57, 58, 169, 188):
            sizes[op] = 2
        for op in (17, 19, 20, 132, 178, 179, 180, 181, 182, 183, 184, 187, 189, 192, 193, 198, 199):
            sizes[op] = 3
        for op in range(153, 169):
            sizes[op] = 3
        sizes.update({185: 5, 186: 5, 197: 4, 200: 5, 201: 5})
        while p < len(code):
            start, op = p, code[p]
            if op not in sizes:
                raise ValueError("invalid/reserved opcode")
            size = sizes[op]
            operand = None
            if op == 196:
                if p + 2 > len(code) or code[p + 1] not in (21, 22, 23, 24, 25, 54, 55, 56, 57, 58, 132, 169):
                    raise ValueError("invalid wide")
                size = 6 if code[p + 1] == 132 else 4
            if op in (170, 171):
                aligned = (p + 4) & ~3
                sr = Reader(code[aligned:])
                signed = lambda: int.from_bytes(sr.take(4), "big", signed=True)
                default = start + signed()
                if op == 170:
                    low, high = signed(), signed()
                    if high < low or high - low + 1 > len(code) // 4:
                        raise ValueError("invalid tableswitch")
                    pairs = [[k, start + signed()] for k in range(low, high + 1)]
                else:
                    count = signed()
                    if count < 0 or count > len(code) // 8:
                        raise ValueError("invalid lookupswitch")
                    pairs = [[signed(), start + signed()] for _ in range(count)]
                    if any(a[0] >= b[0] for a, b in zip(pairs, pairs[1:])):
                        raise ValueError("unsorted lookupswitch")
                size = aligned + sr.pos - start
                operand = ["switch", default, pairs]
            if p + size > len(code):
                raise ValueError("truncated instruction")
            raw = code[p + 1:p + size]
            if operand is None:
                operand = ["raw", raw.hex()]
            if op in (18, 19, 20, 178, 179, 180, 181, 182, 183, 184, 185, 186, 187, 189, 192, 193, 197):
                i = raw[0] if op == 18 else int.from_bytes(raw[:2], "big")
                operand = ["cp", self.constant(i), raw[2:].hex() if op in (185, 186, 197) else ""]
                if op in (182, 183, 184, 185):
                    row = self.cp[i]
                    owner, nat = self.cp[row[1]], self.cp[row[2]]
                    calls.append(self.utf(owner[1]) + "." + self.utf(nat[1]) + self.utf(nat[2]))
                if op == 19:
                    op = 18  # Same ldc constant, independent of CP index width.
            elif op in tuple(range(153, 169)) + (198, 199, 200, 201):
                operand = ["branch", start + int.from_bytes(raw, "big", signed=True)]
            offsets[start] = len(tokens)
            tokens.append([op, operand])
            p += size
        return tokens, offsets, calls


def jar_entries(path):
    result = {}
    with zipfile.ZipFile(path) as z:
        for entry in z.infolist():
            if not entry.is_dir() and entry.filename.endswith(".class"):
                if entry.filename in result:
                    raise ValueError("duplicate archive entry: " + entry.filename)
                data = z.read(entry)
                if data:
                    parse_class(data)
                result[entry.filename] = data
    return result


def compare(base, patch):
    before, after = Detail(base), Detail(patch)
    old, new = before.method_attrs, after.method_attrs
    changes, same = [], 0
    for key in sorted(set(old) | set(new)):
        a = before.method(key) if key in old else None
        b = after.method(key) if key in new else None
        if a is not None and b is not None and (a["access"], a["code"]) == (b["access"], b["code"]):
            same += 1
            continue
        changes.append({"name": key[0], "descriptor": key[1], "change": "added" if a is None else "removed" if b is None else "changed",
                        "before": a, "after": b})
    bf, pf = before.fields(), after.fields()
    field_changes = [{"name": k[0], "descriptor": k[1], "before": bf.get(k), "after": pf.get(k)}
                     for k in sorted(set(bf) | set(pf)) if bf.get(k) != pf.get(k)]
    header = lambda d: [d.info.access, d.info.superclass, d.info.interfaces]
    return {"unchanged_instruction_and_access_methods": same, "method_changes": changes,
            "field_changes": field_changes, "header_changed": header(before) != header(after)}


def audit(directory):
    inputs, jars = {}, {}
    for name, expected in PINS.items():
        path = directory / name
        actual = digest(path.read_bytes())
        if actual != expected:
            raise ValueError("input hash mismatch: " + name)
        jars[name] = jar_entries(path)
        inputs[name] = {"sha256": actual, "bytes": path.stat().st_size,
                        "nonempty_classes": sum(bool(v) for v in jars[name].values()),
                        "empty_classes": sum(not v for v in jars[name].values()),
                        "entry_identity_mismatches": [{"entry": k, "this_class": parse_class(v).name}
                                                      for k, v in jars[name].items()
                                                      if v and parse_class(v).name + ".class" != k]}
    records, counts = [], Counter()
    for entry, payload in sorted(jars["offline-patches.jar"].items()):
        candidates = [{"archive": name, "bytes": len(jars[name][entry]), "sha256": digest(jars[name][entry])}
                      for name in ("classes.jar", "libs.jar") if entry in jars[name]]
        record = {"class": entry[:-6], "patch_bytes": len(payload), "patch_sha256": digest(payload), "base_candidates": candidates}
        canonical_candidates = []
        if payload:
            canonical = parse_class(payload).name + ".class"
            record["patch_this_class"] = canonical[:-6]
            if canonical != entry:
                canonical_candidates = [{"archive": name, "entry": canonical, "bytes": len(jars[name][canonical]),
                                         "sha256": digest(jars[name][canonical]), "payload_equal": jars[name][canonical] == payload}
                                        for name in ("classes.jar", "libs.jar") if canonical in jars[name]]
                record["canonical_base_candidates"] = canonical_candidates
        elif entry.startswith("mods/carpentersblocks/"):
            # Empty bytes do not declare an identity. This lookup is only an
            # explicitly labelled path-alias check, not recovery of a class.
            canonical = entry[len("mods/"):]
            record["empty_alias_candidates"] = [{"archive": name, "entry": canonical,
                                                  "payload_equal": jars[name][canonical] == payload}
                                                 for name in ("classes.jar", "libs.jar") if canonical in jars[name]]
        if not payload:
            status = "empty_placeholder"
        elif canonical_candidates and canonical_candidates[0]["payload_equal"] and not candidates:
            status = "byte_identical_alias"
        elif not candidates:
            status = "new_in_patch"
            detail = Detail(payload)
            record["new_methods"] = [{"name": k[0], "descriptor": k[1], **detail.method(k)} for k in sorted(detail.method_attrs)]
        else:
            selected = candidates[0]["archive"]
            record["selected_base_archive"] = selected
            data = jars[selected][entry]
            if not data:
                status = "replaces_empty_base"
            elif data == payload:
                status = "byte_identical"
            else:
                status = "different_payload"
                record.update(compare(data, payload))
        record["status"] = status
        records.append(record)
        counts[status] += 1
    return {"schema": 1, "inputs": inputs, "counts": dict(counts), "classes": records,
            "limits": ["Only the supplied package is compared; pristine historical release is unavailable.",
                       "Instruction fingerprints include resolved CP/bootstrap references and exception tables, not full runtime equivalence.",
                       "Annotations, debug tables, stack maps, signatures and other opaque attributes are not compared semantically.",
                       "Empty placeholders are inventoried, not assumed safe or silently counted as recovered classes.",
                       "No game/JVM code is executed. Known game tests are separate evidence."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classes-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    input_root = args.classes_dir.resolve().parent
    if args.output.resolve().is_relative_to(input_root):
        raise ValueError("report must be outside the original package tree")
    if args.output.exists():
        raise ValueError("output already exists")
    report = audit(args.classes_dir)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(json.dumps({"counts": report["counts"], "classes": len(report["classes"]), "output": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()

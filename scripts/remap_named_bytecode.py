"""Remap semantic class/member references while preserving Code arrays and literals.

Supports class-file attributes used by Java 8. No input bytecode is executed.
Original UTF8 entries stay intact; semantic references point to interned names.
"""
from collections import Counter, defaultdict
from pathlib import Path
import argparse
import csv
import hashlib
import json
import struct
import zipfile
from classfile_index import parse_class, map_descriptor, map_signature


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def readable_names(directory):
    names = defaultdict(set)
    metadata = directory / 'mcp-reference.json'
    if not metadata.exists():
        metadata = directory / 'provenance.json'
    provenance = json.loads(metadata.read_text(encoding='utf-8'))
    for filename in ('methods.csv', 'fields.csv'):
        path = directory / filename
        if sha(path) != provenance['csv_files'][filename]['sha256']:
            raise ValueError('MCP reference hash mismatch: ' + filename)
        with path.open(encoding='utf-8', newline='') as stream:
            for row in csv.DictReader(stream):
                names[row['searge']].add(row['name'])
    ambiguous = {name: sorted(values) for name, values in names.items() if len(values) != 1}
    return {name: next(iter(values)) for name, values in names.items() if len(values) == 1}, ambiguous


def remap_class(data, class_names, member_names):
    original = parse_class(data)
    cp = list(original.constant_pool)
    # Find the original pool end without guessing based on source names.
    p = 10
    for row in cp[1:]:
        if row is None:
            continue
        tag = row[0]
        size = 2 + len(row[1]) if tag == 1 else {3: 4, 4: 4, 5: 8, 6: 8,
                   7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4, 15: 3,
                   16: 2, 17: 4, 18: 4, 19: 2, 20: 2}[tag]
        p += 1 + size
    body = bytearray(data[p:])
    utf_index = {row[1]: i for i, row in enumerate(cp) if row and row[0] == 1}
    changes = Counter()

    def utf(index):
        return cp[index][1].decode('utf-8')

    def intern(value):
        raw = value.encode('utf-8')
        if len(raw) > 65535:
            raise ValueError('Oversized UTF8 constant')
        if raw not in utf_index:
            utf_index[raw] = len(cp)
            cp.append((1, raw))
        return utf_index[raw]

    def redirect(index, kind):
        value = utf(index)
        if kind == 'class':
            updated = map_descriptor(value, class_names) if value.startswith('[') else class_names.get(value, value)
        elif kind == 'member':
            updated = member_names.get(value, value)
        elif kind == 'signature':
            updated = map_signature(value, class_names)
        else:
            updated = map_descriptor(value, class_names)
        if updated != value:
            changes[kind] += 1
            return intern(updated)
        return index

    for i, row in enumerate(list(cp)):
        if not row:
            continue
        if row[0] == 7:
            cp[i] = (7, redirect(row[1], 'class'))
        elif row[0] == 12:
            cp[i] = (12, redirect(row[1], 'member'), redirect(row[2], 'descriptor'))
        elif row[0] == 16:
            cp[i] = (16, redirect(row[1], 'descriptor'))

    p = 0

    def take(n):
        nonlocal p
        if n < 0 or p + n > len(body):
            raise ValueError('Attribute bounds exceeded')
        value = body[p:p + n]
        p += n
        return value

    def u1():
        return take(1)[0]

    def u2():
        return int.from_bytes(take(2), 'big')

    def u4():
        return int.from_bytes(take(4), 'big')

    def rewrite(kind):
        at = p
        index = u2()
        body[at:at + 2] = struct.pack('>H', redirect(index, kind))

    def value():
        tag = chr(u1())
        if tag in 'BCDFIJSZs':
            take(2)  # Numeric constants and annotation strings remain intact.
        elif tag == 'e':
            rewrite('descriptor')
            take(2)
        elif tag == 'c':
            rewrite('descriptor')
        elif tag == '@':
            annotation()
        elif tag == '[':
            for _ in range(u2()):
                value()
        else:
            raise ValueError('Unknown annotation value tag: ' + tag)

    def annotation():
        rewrite('descriptor')
        for _ in range(u2()):
            take(2)  # Element identifiers belong to the annotation owner.
            value()

    def attributes():
        nonlocal p
        for _ in range(u2()):
            name, size = utf(u2()), u4()
            end = p + size
            if end > len(body):
                raise ValueError('Attribute length exceeds class')
            if name == 'Signature':
                rewrite('signature')
            elif name == 'Code':
                take(4)
                take(u4())
                take(u2() * 8)
                attributes()
            elif name in ('LocalVariableTable', 'LocalVariableTypeTable'):
                for _ in range(u2()):
                    take(6)
                    rewrite('signature' if name == 'LocalVariableTypeTable' else 'descriptor')
                    take(2)
            elif name in ('RuntimeVisibleAnnotations', 'RuntimeInvisibleAnnotations'):
                for _ in range(u2()):
                    annotation()
            elif name in ('RuntimeVisibleParameterAnnotations', 'RuntimeInvisibleParameterAnnotations'):
                for _ in range(u1()):
                    for _ in range(u2()):
                        annotation()
            elif name in ('RuntimeVisibleTypeAnnotations', 'RuntimeInvisibleTypeAnnotations'):
                for _ in range(u2()):
                    tag = u1()
                    if tag in (0x00, 0x01, 0x16):
                        take(1)
                    elif tag in (0x10, 0x11, 0x12, 0x17, 0x42, 0x43, 0x44, 0x45, 0x46):
                        take(2)
                    elif tag in (0x13, 0x14, 0x15):
                        pass
                    elif tag in (0x40, 0x41):
                        take(u2() * 6)
                    elif 0x47 <= tag <= 0x4b:
                        take(3)
                    else:
                        raise ValueError('Unknown annotation target')
                    take(u1() * 2)
                    annotation()
            elif name == 'AnnotationDefault':
                value()
            elif name == 'InnerClasses':
                for _ in range(u2()):
                    inner = u2()
                    take(2)
                    at = p
                    old_name = u2()
                    take(2)
                    if inner and old_name:
                        raw = utf(original.constant_pool[inner][1])
                        target = class_names.get(raw, raw)
                        if raw != target:
                            body[at:at + 2] = struct.pack('>H', intern(target.rsplit('$', 1)[-1].rsplit('/', 1)[-1]))
                            changes['inner_name'] += 1
            else:
                p = end
            if p != end:
                raise ValueError('Attribute parse mismatch: ' + name)

    take(6)
    take(u2() * 2)
    for _ in range(2):
        for _ in range(u2()):
            take(2)
            rewrite('member')
            rewrite('descriptor')
            attributes()
    attributes()
    if p != len(body) or len(cp) >= 65536:
        raise ValueError('Invalid class end or constant-pool size')
    output = bytearray(data[:8]) + struct.pack('>H', len(cp))
    for row in cp[1:]:
        if row is None:
            continue
        tag = row[0]
        output += bytes([tag])
        if tag == 1:
            output += struct.pack('>H', len(row[1])) + row[1]
        elif tag in (3, 4, 5, 6):
            output += row[1]
        elif tag in (7, 8, 16, 19, 20):
            output += struct.pack('>H', row[1])
        elif tag == 15:
            output += struct.pack('>BH', *row[1:])
        else:
            output += struct.pack('>HH', *row[1:])
    output += body
    result = parse_class(output)
    if result.name != class_names.get(original.name, original.name):
        raise ValueError('Remapped class identity mismatch')
    if original.string_constants != result.string_constants:
        raise ValueError('String literals changed')
    if [member.code for member in original.methods] != [member.code for member in result.methods]:
        raise ValueError('Instruction arrays changed')
    for members in (result.fields, result.methods):
        keys = [(member.name, member.descriptor) for member in members]
        if len(keys) != len(set(keys)):
            raise ValueError('Member signature collision in ' + result.name)
    return bytes(output), result.name, changes, original


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True, type=Path, action='append')
    parser.add_argument('--classes', required=True, type=Path)
    parser.add_argument('--mcp', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    args = parser.parse_args()
    mapping = json.loads(args.classes.read_text(encoding='utf-8'))
    if mapping['consistency_issues']:
        raise ValueError('Unvalidated class map')
    if not any(sha(path) == mapping['inputs']['classes']['sha256'] for path in args.input):
        raise ValueError('No supplied input matches the class-map provenance')
    class_names = {row['original']: row['named'] for row in mapping['rows']}
    members, ambiguous = readable_names(args.mcp)
    args.output.mkdir(parents=True, exist_ok=True)
    reports = []
    for source in args.input:
        target = args.output / (source.stem.replace('-standard', '-named') + '.jar')
        if target.resolve() == source.resolve():
            raise ValueError('Input must not be overwritten')
        count, methods, changes, names, member_declarations = 0, 0, Counter(), set(), Counter()
        with zipfile.ZipFile(source) as archive, zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as out:
            for entry in archive.infolist():
                if not entry.filename.endswith('.class'):
                    continue
                raw = archive.read(entry)
                converted, name, stats, info = remap_class(raw, class_names, members)
                if name in names:
                    raise ValueError('Class path collision: ' + name)
                names.add(name)
                out.writestr(name + '.class', converted)
                count += 1
                methods += sum(member.code is not None for member in info.methods)
                changes.update(stats)
                member_declarations['fields'] += sum(member.name in members for member in info.fields)
                member_declarations['methods'] += sum(member.name in members for member in info.methods)
        reports.append({'input': str(source), 'input_sha256': sha(source), 'output': str(target),
                        'output_sha256': sha(target), 'classes': count, 'code_methods_checked': methods,
                        'changes': dict(changes), 'renamed_declarations': dict(member_declarations),
                        'validation': 'All classes reparsed; unchanged Code arrays and literal strings; no class/member collisions'})
    report = {'class_map_sha256': sha(args.classes), 'mcp_ambiguous_identifiers': ambiguous,
              'scope': 'Research bytecode only. Reflection/ASM string targets intentionally preserved and require later audit before runtime use.',
              'archives': reports}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'ambiguous_identifiers_retained': len(ambiguous), 'archives': reports}), flush=True)


if __name__ == '__main__':
    main()

"""Minimal bounds-checked Java class declaration reader; never loads a class."""
from dataclasses import dataclass
import re
import zipfile


@dataclass
class Member:
    access: int
    name: str
    descriptor: str
    code: bytes = None


@dataclass
class ClassInfo:
    name: str
    superclass: str
    interfaces: list
    access: int
    fields: list
    methods: list
    string_constants: set
    constant_pool: list


def parse_class(data):
    data = bytes(data)
    p = 0

    def take(n):
        nonlocal p
        if n < 0 or p + n > len(data):
            raise ValueError('Class bounds exceeded')
        result = data[p:p + n]
        p += n
        return result

    def u2():
        return int.from_bytes(take(2), 'big')

    def u4():
        return int.from_bytes(take(4), 'big')

    if take(4) != b'\xca\xfe\xba\xbe':
        raise ValueError('Invalid class magic')
    take(4)
    count = u2()
    cp = [None] * count
    i = 1
    while i < count:
        tag = take(1)[0]
        if tag == 1:
            cp[i] = (tag, take(u2()))
        elif tag in (3, 4):
            cp[i] = (tag, take(4))
        elif tag in (5, 6):
            cp[i] = (tag, take(8))
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            cp[i] = (tag, u2())
        elif tag in (9, 10, 11, 12, 17, 18):
            cp[i] = (tag, u2(), u2())
        elif tag == 15:
            cp[i] = (tag, take(1)[0], u2())
        else:
            raise ValueError('Unknown CP tag: ' + str(tag))
        i += 1

    def utf(index):
        if not cp[index] or cp[index][0] != 1:
            raise ValueError('Expected UTF8 constant')
        # Names and descriptors are ASCII. Do not decode arbitrary MUTF8 literals.
        return cp[index][1].decode('utf-8')

    def cls(index):
        return utf(cp[index][1]) if index else ''

    access, this, parent = u2(), u2(), u2()
    interfaces = [cls(u2()) for _ in range(u2())]

    def attributes():
        code = None
        for _ in range(u2()):
            name = utf(u2())
            payload = take(u4())
            if name == 'Code':
                if len(payload) < 8:
                    raise ValueError('Short Code attribute')
                size = int.from_bytes(payload[4:8], 'big')
                code = payload[8:8 + size]
                if len(code) != size:
                    raise ValueError('Code bounds exceeded')
        return code

    groups = []
    for _ in range(2):
        members = []
        for _ in range(u2()):
            flags, name, descriptor = u2(), u2(), u2()
            members.append(Member(flags, utf(name), utf(descriptor), attributes()))
        groups.append(members)
    attributes()
    if p != len(data):
        raise ValueError('Trailing bytes after class')
    strings = {cp[row[1]][1] for row in cp if row and row[0] == 8}
    return ClassInfo(cls(this), cls(parent), interfaces, access, *groups, strings, cp)


def read_jar(path):
    result = {}
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            if entry.filename.endswith('.class'):
                info = parse_class(archive.read(entry))
                if entry.filename != info.name + '.class' or info.name in result:
                    raise ValueError('Duplicate or inconsistent class entry: ' + entry.filename)
                result[info.name] = info
    return result


def map_descriptor(descriptor, mapping):
    return re.sub(r'L([^;<]+)', lambda match: 'L' + mapping.get(match[1], match[1]), descriptor)


def map_signature(signature, mapping):
    """Parse JVMS 4.7.9.1, including formal bounds and dotted nested types."""
    p = 0

    def expect(value):
        nonlocal p
        if p >= len(signature) or signature[p] != value:
            raise ValueError('Invalid generic signature: ' + signature)
        p += 1

    def arguments():
        nonlocal p
        if p >= len(signature) or signature[p] != '<':
            return ''
        p += 1
        result = '<'
        while signature[p] != '>':
            if signature[p] == '*':
                result += '*'
                p += 1
            else:
                if signature[p] in '+-':
                    result += signature[p]
                    p += 1
                result += type_signature()
        p += 1
        return result + '>'

    def type_signature():
        nonlocal p
        if p >= len(signature):
            raise ValueError('Truncated generic signature')
        tag = signature[p]
        p += 1
        if tag in 'BCDFIJSZV':
            return tag
        if tag == '[':
            return '[' + type_signature()
        if tag == 'T':
            end = signature.index(';', p)
            value = 'T' + signature[p:end + 1]
            p = end + 1
            return value
        if tag != 'L':
            raise ValueError('Invalid generic type tag: ' + tag)
        components, parameters = [], []
        while True:
            start = p
            while p < len(signature) and signature[p] not in '<.;':
                p += 1
            if p == start:
                raise ValueError('Empty class signature component')
            components.append(signature[start:p])
            parameters.append(arguments())
            if signature[p] == ';':
                p += 1
                break
            expect('.')
        raw_base = components[0]
        raw = '$'.join(components)
        full = mapping.get(raw)
        if full is not None and len(components) > 1:
            parts = full.rsplit('$', len(components) - 1)
            if len(parts) != len(components):
                raise ValueError('Mapped nested signature changes nesting depth')
            components = parts
        else:
            components[0] = mapping.get(components[0], components[0])
            prefix = raw_base
            for index in range(1, len(components)):
                prefix += '$' + components[index]
                if prefix in mapping:
                    components[index] = mapping[prefix].rsplit('$', 1)[-1]
        return 'L' + '.'.join(name + params for name, params in zip(components, parameters)) + ';'

    result = ''
    if signature.startswith('<'):
        p = 1
        result += '<'
        while signature[p] != '>':
            end = signature.index(':', p)
            result += signature[p:end + 1]
            p = end + 1
            if signature[p] != ':':
                result += type_signature()
            while signature[p] == ':':
                p += 1
                result += ':' + type_signature()
        p += 1
        result += '>'
    if p < len(signature) and signature[p] == '(':
        p += 1
        result += '('
        while signature[p] != ')':
            result += type_signature()
        p += 1
        result += ')' + type_signature()
    while p < len(signature):
        if signature[p] == '^':
            result += '^'
            p += 1
        result += type_signature()
    return result


def descriptor_parts(descriptor):
    """Preserve primitive/array shape and separate reference type identities."""
    parts = re.findall(r'\[*L[^;]+;|\[*[BCDFIJSZV]|[()]', descriptor)
    if ''.join(parts) != descriptor:
        raise ValueError('Unsupported descriptor: ' + descriptor)
    shape, references = [], []
    for part in parts:
        if 'L' in part:
            prefix, name = part.split('L', 1)
            shape.append(prefix + 'L;')
            references.append(name[:-1])
        else:
            shape.append(part)
    return tuple(shape), tuple(references)

"""Small independent class fixtures for the patch audit's comparison boundaries."""
import struct
import unittest

from audit_2019_offline_patch import Detail, compare


def u2(n):
    return struct.pack(">H", n)


def u4(n):
    return struct.pack(">I", n)


def fixture(code=b"\x12\x0b\xb0", literal=b"first", handler=None, native=False):
    # Constant indices: 1 Test, 2 this_class, 3 Object, 4 super, 5 m,
    # 6 descriptor, 7 Code, 8 Throwable name, 9 catch class, 10 literal,
    # 11 string. Build bytes independently of the reader under test.
    pool = [(1, b"Test"), (7, 1), (1, b"java/lang/Object"), (7, 3),
            (1, b"m"), (1, b"()Ljava/lang/String;"), (1, b"Code"),
            (1, b"java/lang/Throwable"), (7, 8), (1, literal), (8, 10)]
    encoded = b"".join(bytes([tag]) + (u2(len(value)) + value if tag == 1 else u2(value)) for tag, value in pool)
    body = u2(0x21) + u2(2) + u2(4) + u2(0) + u2(0) + u2(1)
    body += u2(0x109 if native else 9) + u2(5) + u2(6)
    if native:
        body += u2(0)
    else:
        handlers = [] if handler is None else [handler]
        payload = u2(1) + u2(0) + u4(len(code)) + code + u2(len(handlers))
        payload += b"".join(b"".join(u2(v) for v in row) for row in handlers) + u2(0)
        body += u2(1) + u2(7) + u4(len(payload)) + payload
    return b"\xca\xfe\xba\xbe" + u2(0) + u2(52) + u2(len(pool) + 1) + encoded + body + u2(0)


class ComparisonTests(unittest.TestCase):
    def test_identical_class(self):
        report = compare(fixture(), fixture())
        self.assertEqual(report["method_changes"], [])
        self.assertEqual(report["unchanged_instruction_and_access_methods"], 1)

    def test_same_code_changed_cp_literal(self):
        a, b = Detail(fixture()), Detail(fixture(literal=b"second"))
        self.assertEqual(a.info.methods[0].code, b.info.methods[0].code)
        self.assertNotEqual(a.method(("m", "()Ljava/lang/String;"))["code"], b.method(("m", "()Ljava/lang/String;"))["code"])

    def test_ldc_width_and_branch_offsets(self):
        a = fixture(code=b"\x12\x0b\x57\xa7\x00\x03\x01\xb0")
        b = fixture(code=b"\x13\x00\x0b\x57\xa7\x00\x03\x01\xb0")
        self.assertEqual(compare(a, b)["method_changes"], [])

    def test_exception_handler_is_compared(self):
        a = fixture(handler=(0, 2, 2, 9))
        b = fixture(handler=(0, 2, 2, 0))
        self.assertEqual(Detail(a).info.methods[0].code, Detail(b).info.methods[0].code)
        self.assertEqual(len(compare(a, b)["method_changes"]), 1)

    def test_native_replaced_by_stub(self):
        report = compare(fixture(native=True), fixture(code=b"\x01\xb0"))
        self.assertEqual(report["method_changes"][0]["before"]["kind"], "native")
        self.assertEqual(report["method_changes"][0]["after"]["kind"], "constant_return")

    def test_invalid_branch_boundary(self):
        with self.assertRaises(ValueError):
            Detail(fixture(code=b"\xa7\x00\x01\x01\xb0")).method(("m", "()Ljava/lang/String;"))

    def test_branch_cannot_target_end(self):
        with self.assertRaises(ValueError):
            Detail(fixture(code=b"\xa7\x00\x03")).method(("m", "()Ljava/lang/String;"))

    def test_truncated_code(self):
        with self.assertRaises(ValueError):
            Detail(fixture(code=b"\x13\x00")).method(("m", "()Ljava/lang/String;"))


if __name__ == "__main__":
    unittest.main()

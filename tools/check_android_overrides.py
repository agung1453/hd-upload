#!/usr/bin/env python3
"""
Verify that every `override fun` in this project really overrides something.

A Kotlin `override` whose method does not match the supertype is only reported
by the compiler, and rather opaquely:

    e: NimbusWebViewClient.kt:112:5 'doUpdateVisitedHistory' overrides nothing.

Two different mistakes produced exactly that message, one CI round-trip each:

  * `onReceivedTitle` was declared on a WebViewClient subclass, but it is a
    WebChromeClient callback - WebViewClient has no such method at all.
  * `doUpdateVisitedHistory` was declared with four parameters, but the SDK
    declares three (`(Landroid/webkit/WebView;Ljava/lang/String;Z)V`).

So this checks both the name *and* the parameter count, straight from the
platform stubs:

    ANDROID_JAR=$ANDROID_HOME/platforms/android-35/android.jar \
        python3 tools/check_android_overrides.py

Each override is attributed to the class it really belongs in (nested and
anonymous `object : Super()` bodies included, matched by braces), resolved to
its supertype, and looked up in that stub's `methods` table.

Skips silently when no android.jar is available. Exit code 1 on a mismatch.
"""

from __future__ import annotations

import os
import re
import struct
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "app", "src", "main", "java")

DECL = re.compile(
    r"\b(?:class|object|interface)\s+(?:(\w+)\s*)?"
    r"(?:\([^()]*(?:\([^()]*\)[^()]*)*\))?\s*:\s*([\w.]+)",
    re.S,
)
OVERRIDE = re.compile(r"(?m)^[ \t]*override\s+fun\s+(\w+)\s*\(")
IMPORT = re.compile(r"(?m)^import\s+([\w.]+)")


# --------------------------------------------------------------------------- #
# .class parsing
# --------------------------------------------------------------------------- #

def constant_pool(blob: bytes) -> dict[int, str]:
    """Utf8 entries of the constant pool, keyed by index."""
    count = struct.unpack_from(">H", blob, 8)[0]
    out: dict[int, str] = {}
    pos, idx = 10, 1
    while idx < count:
        tag = blob[pos]
        pos += 1
        if tag == 1:
            ln = struct.unpack_from(">H", blob, pos)[0]
            pos += 2
            out[idx] = blob[pos : pos + ln].decode("utf-8", "replace")
            pos += ln
        elif tag in (3, 4):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            idx += 1
        elif tag == 7:
            pos += 2
        elif tag in (8, 16, 19, 20):
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag == 15:
            pos += 3
        idx += 1
    return out


def descriptor_arity(desc: str) -> int:
    """Number of parameters in a JVM method descriptor."""
    i, n = 1, 0
    while i < len(desc) and desc[i] != ")":
        while i < len(desc) and desc[i] == "[":
            i += 1
        if i < len(desc) and desc[i] == "L":
            while i < len(desc) and desc[i] != ";":
                i += 1
            i += 1
        else:
            i += 1
        n += 1
    return n


def declared_methods(blob: bytes) -> dict[str, set[int]]:
    """name -> set of parameter counts from the class's own methods table.

    A class's own methods live in the `methods` table that follows the
    constant pool; they are not referenced through Methodref entries, so
    scanning the pool alone finds nothing. Overloads are preserved, which
    matters because `onReceivedError` exists in both a 3- and 4-argument form.
    """
    u2 = lambda at: struct.unpack_from(">H", blob, at)[0]      # noqa: E731
    u4 = lambda at: struct.unpack_from(">I", blob, at)[0]      # noqa: E731

    cp = constant_pool(blob)
    pos, idx = 10, 1
    count = u2(8)
    while idx < count:
        tag = blob[pos]
        pos += 1
        if tag == 1:
            pos += 2 + u2(pos)
        elif tag in (3, 4):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            idx += 1
        elif tag == 7:
            pos += 2
        elif tag in (8, 16, 19, 20):
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag == 15:
            pos += 3
        idx += 1

    # access_flags, this_class, super_class, then interfaces_count (2 bytes
    # itself) followed by that many interfaces.
    pos += 6
    pos += 2 + 2 * u2(pos)

    def skip_members(at: int) -> int:
        """`at` points AT a member_count; returns AT the next member_count."""
        n = u2(at)
        at += 2
        for _ in range(n):
            attr_count = u2(at + 6)
            at += 8
            for _a in range(attr_count):
                at += 2 + 4 + u4(at + 2)
        return at

    result: dict[str, set[int]] = {}
    try:
        pos = skip_members(pos)      # past fields -> AT methods_count
        methods = u2(pos)
        pos += 2
        for _ in range(methods):
            name = cp.get(u2(pos + 2))
            desc = cp.get(u2(pos + 4))
            if name and desc and desc.startswith("(") and not name.startswith("<"):
                result.setdefault(name, set()).add(descriptor_arity(desc))
            attr_count = u2(pos + 6)
            pos += 8
            for _a in range(attr_count):
                pos += 2 + 4 + u4(pos + 2)
            if pos > len(blob):
                return {}              # unparsable -> cannot verify, not wrong
    except (struct.error, IndexError):
        return {}                      # unparsable -> cannot verify, not wrong
    return result


# --------------------------------------------------------------------------- #
# Kotlin source parsing
# --------------------------------------------------------------------------- #

def strip_kotlin(source: str) -> str:
    """Drop comments and literals so brace matching is meaningful."""
    out, i, n = [], 0, len(source)
    while i < n:
        ch = source[i]
        nxt = source[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "/":
            while i < n and source[i] != "\n":
                i += 1
        elif ch == "/" and nxt == "*":
            i += 2
            while i < n - 1 and not (source[i] == "*" and source[i + 1] == "/"):
                i += 1
            i += 2
        elif source.startswith('"""', i):
            i += 3
            while i < n - 1 and not source.startswith('"""', i):
                i += 1
            i += 3
        elif ch == '"':
            i += 1
            while i < n and source[i] != '"':
                if source[i] == "\\":
                    i += 1
                i += 1
            i += 1
        elif ch == "'":
            i += 1
            while i < n and source[i] != "'":
                if source[i] == "\\":
                    i += 1
                i += 1
            i += 1
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def brace_pairs(code: str) -> dict[int, int]:
    stack, pairs = [], {}
    for i, ch in enumerate(code):
        if ch == "{":
            stack.append(i)
        elif ch == "}" and stack:
            pairs[stack.pop()] = i
    return pairs


def body_open(code: str, start: int) -> int | None:
    """Index of the '{' that opens this declaration's body, or None."""
    depth = 0
    for i in range(start, len(code)):
        ch = code[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch == "{" and depth <= 0:
            return i
        elif ch == ";" and depth <= 0:
            return None
    return None


def kotlin_arity(code: str, after_paren: int) -> int:
    """Parameter count of a function whose '(' sits just before after_paren."""
    i, depth, commas, has_token = after_paren, 0, 0, False
    while i < len(code):
        ch = code[i]
        if ch in "(<":
            depth += 1
        elif ch in ">)":
            if depth == 0:
                break
            depth -= 1
        elif ch == "," and depth == 0:
            commas += 1
        elif not ch.isspace() and depth == 0:
            has_token = True
        i += 1
    return commas + 1 if has_token else 0


def find_jar() -> str | None:
    jar = os.environ.get("ANDROID_JAR")
    if jar and os.path.isfile(jar):
        return jar
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")):
        if not root:
            continue
        platforms = os.path.join(root, "platforms")
        if not os.path.isdir(platforms):
            continue
        for name in sorted(os.listdir(platforms), reverse=True):
            candidate = os.path.join(platforms, name, "android.jar")
            if os.path.isfile(candidate):
                return candidate
    return None


def main() -> int:
    jar = find_jar()
    if not jar:
        print("no android.jar found - skipping (set ANDROID_JAR or ANDROID_HOME)")
        return 0

    zf = zipfile.ZipFile(jar)
    short_names: dict[str, list[str]] = {}
    for name in zf.namelist():
        if name.endswith(".class"):
            short_names.setdefault(name[:-6].rsplit("/", 1)[-1], []).append(name)

    blob_cache: dict[str, bytes | None] = {}
    arity_cache: dict[str, dict[str, set[int]]] = {}

    def blob_for(fqcn: str) -> bytes | None:
        path = fqcn.replace(".", "/") + ".class"
        if path not in blob_cache:
            try:
                blob_cache[path] = zf.read(path)
            except KeyError:
                blob_cache[path] = None
        return blob_cache[path]

    problems: list[str] = []
    checked = 0

    for dirpath, _dirs, files in os.walk(JAVA):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, ROOT)
            raw = open(path, encoding="utf-8").read()
            code = strip_kotlin(raw)
            if not OVERRIDE.search(code):
                continue

            pairs = brace_pairs(code)
            scopes: list[tuple[int, int, str]] = []
            for m in DECL.finditer(code):
                open_brace = body_open(code, m.start())
                if open_brace is None:
                    continue
                close = pairs.get(open_brace)
                if close is not None:
                    scopes.append((open_brace, close, m.group(2)))

            imports = set(IMPORT.findall(raw))
            for om in OVERRIDE.finditer(code):
                inside = [s for s in scopes if s[0] <= om.start() <= s[1]]
                if not inside:
                    continue
                supertype = max(inside, key=lambda s: s[0])[2]

                short = supertype.rsplit(".", 1)[-1]
                fqcn = next(
                    (i for i in imports if i.rsplit(".", 1)[-1] == short), None
                )
                if fqcn is None:
                    cands = short_names.get(short, [])
                    if len(cands) == 1:
                        fqcn = cands[0][:-6].replace("/", ".")
                if fqcn is None or fqcn.startswith("kotlin"):
                    continue

                blob = blob_for(fqcn)
                if blob is None:
                    continue

                name = om.group(1)
                if fqcn not in arity_cache:
                    arity_cache[fqcn] = declared_methods(blob)
                declared = arity_cache[fqcn]

                if name not in declared:
                    problems.append(
                        f"{rel}: '{name}' does not exist on {fqcn} "
                        f"(overridden inside a class extending it)"
                    )
                    continue

                checked += 1
                want = kotlin_arity(code, om.end())
                have = declared[name]
                if want not in have:
                    problems.append(
                        f"{rel}: '{name}' on {fqcn} declares "
                        f"{sorted(have)} parameter(s) but the override has "
                        f"{want} -> 'overrides nothing'"
                    )

    for p in problems:
        print(f"ERROR {p}")
    print()
    if problems:
        print(f"FAILED: {len(problems)} override(s) that do not match the platform")
        return 1
    print(f"OK - {checked} platform override(s) verified against {os.path.basename(jar)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
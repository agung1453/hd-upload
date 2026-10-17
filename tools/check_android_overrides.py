#!/usr/bin/env python3
"""
Verify that every `override fun` in this project really overrides something.

A Kotlin `override` whose method does not exist in the supertype is only
reported by the compiler, as a fairly opaque message:

    e: NimbusWebViewClient.kt:112:5 'onReceivedTitle' overrides nothing.

That cost a full CI round-trip, because `onReceivedTitle` is a
**WebChromeClient** callback and does not exist on **WebViewClient** at all.

This script answers the question offline by reading the platform stubs
directly. Point it at an `android.jar`:

    ANDROID_JAR=$ANDROID_HOME/platforms/android-35/android.jar \
        python3 tools/check_android_overrides.py

Each `override fun` is attributed to the class it really lives in - honouring
nested and anonymous `object : Super()` bodies by matching braces - and then
checked against the superclass stub.

Skips silently when no android.jar is available. Exit code 1 on a mismatch.
"""

from __future__ import annotations

import os
import re
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


def strip_kotlin(source: str) -> str:
    """Drop comments and literals so brace matching is meaningful."""
    out, i, n = [], 0, len(source)
    while i < n:
        ch, nxt = source[i], (source[i + 1] if i + 1 < n else "")
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
    """Map each '{' to its matching '}'."""
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
    index: dict[str, list[str]] = {}
    for name in zf.namelist():
        if name.endswith(".class"):
            index.setdefault(name[:-6].rsplit("/", 1)[-1], []).append(name)

    blobs: dict[str, bytes | None] = {}

    def members(fqcn: str) -> bytes | None:
        path = fqcn.replace(".", "/") + ".class"
        if path not in blobs:
            try:
                blobs[path] = zf.read(path)
            except KeyError:
                blobs[path] = None
        return blobs[path]

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
            scopes: list[tuple[int, int, str]] = []   # (open, close, supertype)
            for m in DECL.finditer(code):
                open_brace = body_open(code, m.start())
                if open_brace is None:
                    continue
                close = pairs.get(open_brace)
                if close is None:
                    continue
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
                    cands = index.get(short, [])
                    if len(cands) == 1:
                        fqcn = cands[0][:-6].replace("/", ".")
                if fqcn is None or fqcn.startswith("kotlin"):
                    continue

                blob = members(fqcn)
                if blob is None:
                    continue
                checked += 1
                if om.group(1).encode() not in blob:
                    problems.append(
                        f"{rel}: '{om.group(1)}' does not exist on {fqcn} "
                        f"(overridden inside a class extending it)"
                    )

    for p in problems:
        print(f"ERROR {p}")
    print()
    if problems:
        print(f"FAILED: {len(problems)} override(s) missing from the platform")
        return 1
    print(f"OK - {checked} platform override(s) verified against {os.path.basename(jar)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
#!/usr/bin/env python3
"""
Static consistency check for the Nimbus Android project.

Runs without the Android SDK / Gradle so it can be used before CI:
  1. every XML file is well formed
  2. every @drawable/@color/@string/... reference resolves to a real resource
  3. every R.<type>.<name> reference in Kotlin resolves
  4. every *Binding class used in Kotlin has a matching layout
  5. every binding.<id> access exists in that layout
  6. every Activity/Application class in the manifest exists as a Kotlin class
  7. every com.nimbus import resolves to a declared type

Exit code 1 when something is wrong.
"""

from __future__ import annotations

import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app", "src", "main")
RES = os.path.join(APP, "res")
JAVA = os.path.join(APP, "java")
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

RES_TYPES = {
    "drawable": ("drawable", "mipmap-anydpi-v26", "mipmap-hdpi", "mipmap-mdpi",
                 "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"),
    "color": (), "string": (), "dimen": (), "style": (), "array": (),
    "plurals": (), "bool": (), "integer": (), "string-array": (),
    "integer-array": (), "array-string": (),
}
VALUE_TAGS = {
    "string": "string", "color": "color", "dimen": "dimen", "style": "style",
    "string-array": "array", "integer-array": "array", "plurals": "plurals",
    "bool": "bool", "integer": "integer", "array": "array",
}

errors: list[str] = []
warnings: list[str] = []


def err(msg: str) -> None:
    errors.append(msg)


def warn(msg: str) -> None:
    warnings.append(msg)


def rel(path: str) -> str:
    return os.path.relpath(path, ROOT)


def walk(base: str, suffixes: tuple[str, ...]) -> list[str]:
    out = []
    for dirpath, _dirnames, filenames in os.walk(base):
        for name in filenames:
            if name.endswith(suffixes):
                out.append(os.path.join(dirpath, name))
    return sorted(out)


# --------------------------------------------------------------------------- #
# 1. XML well-formedness + resource table
# --------------------------------------------------------------------------- #

defined: dict[str, set[str]] = defaultdict(set)
id_defs: set[str] = set()
all_xml: list[str] = []

for path in walk(RES, (".xml",)):
    all_xml.append(path)
    try:
        tree = ET.parse(path)
    except ET.ParseError as exc:
        err(f"XML PARSE ERROR  {rel(path)}: {exc}")
        continue

    parent_dir = os.path.basename(os.path.dirname(path))

    # File-name based resource types
    if parent_dir in RES_TYPES["drawable"]:
        defined["drawable"].add(os.path.splitext(os.path.basename(path))[0])
        if parent_dir.startswith("mipmap"):
            defined["mipmap"].add(os.path.splitext(os.path.basename(path))[0])
    elif parent_dir in ("layout", "menu", "xml", "anim", "color"):
        defined[parent_dir].add(os.path.splitext(os.path.basename(path))[0])

    for node in tree.iter():
        tag = node.tag.split("}")[-1]
        name = node.get("name")
        rtype = None
        if tag in VALUE_TAGS:
            rtype = VALUE_TAGS[tag]
        elif tag == "item" and node.get("type") in VALUE_TAGS:
            rtype = VALUE_TAGS[node.get("type")]
        if rtype and name:
            defined[rtype].add(name)

        if parent_dir in ("layout", "menu") and node.get(ANDROID_NS + "id"):
            raw = node.get(ANDROID_NS + "id")
            if raw.startswith("@+id/"):
                defined["id"].add(raw[5:])
                id_defs.add(raw[5:])

# Manifest classes
manifest_path = os.path.join(APP, "AndroidManifest.xml")
manifest_classes: list[str] = []
try:
    manifest = ET.parse(manifest_path).getroot()
    for node in manifest.iter():
        if node.tag == "activity" or node.tag == "application":
            cls = node.get(ANDROID_NS + "name")
            if cls:
                manifest_classes.append(cls)
except ET.ParseError as exc:
    err(f"XML PARSE ERROR  {rel(manifest_path)}: {exc}")

# --------------------------------------------------------------------------- #
# 2. Resource references from XML
# --------------------------------------------------------------------------- #

REF_RE = re.compile(r'"@(?!\+)(?:([a-z]+):)?([A-Za-z0-9_.\-]+)"')
plus_id_re = re.compile(r'@\+id/([A-Za-z0-9_]+)')


def check_xml_refs(path: str, text: str) -> None:
    for _typ, name in REF_RE.findall(text):
        # style/array/... all resolve by simple name
        if not _typ:
            if name == "null":
                continue  # explicit empty reference, e.g. contentDescription="@null"
            if name not in defined["id"]:
                err(f"MISSING @id/{name} referenced in {rel(path)}")
            continue
        typ = _typ
        if typ == "string-array":
            typ = "array"
        if typ in ("drawable", "mipmap", "layout", "menu", "xml", "color",
                   "string", "dimen", "style", "array", "plurals", "bool",
                   "integer", "anim"):
            if name not in defined[typ]:
                err(f"MISSING @{typ}/{name} referenced in {rel(path)}")
    for name in plus_id_re.findall(text):
        if name not in id_defs:
            err(f"UNDECLARED @{name} declared in {rel(path)}")


for path in all_xml:
    with open(path, encoding="utf-8") as handle:
        check_xml_refs(path, handle.read())

# --------------------------------------------------------------------------- #
# Kotlin sources
# --------------------------------------------------------------------------- #

kotlin_files = walk(JAVA, (".kt",))
if not kotlin_files:
    err("No Kotlin sources found under app/src/main/java")

declared_types: dict[str, set[str]] = defaultdict(set)
DECL_RE = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:public |internal |private |abstract |open |sealed |final |annotation |data |value )*"
    r"(?:class|object|interface|enum class)\s+([A-Za-z_]\w*)",
    re.M,
)
# Top level functions, including extensions: `fun View.setVisible(...)`.
FUN_RE = re.compile(
    r"^(?:public |internal |private )?(?:inline |suspend |tailrec |operator |infix )*fun\s+"
    r"(?:<[^>]+>\s*)?(?:[A-Za-z_][\w.<>?]*\.)?([A-Za-z_]\w*)\s*\(",
    re.M,
)
# Top level extension properties: `val View.isVisible: Boolean get() = ...`
PROP_RE = re.compile(
    r"^(?:public |internal |private )?(?:const )?(?:val|var)\s+"
    r"(?:[A-Za-z_][\w.<>?]*\.)?([A-Za-z_]\w*)\s*[:=]",
    re.M,
)
ENUM_ENTRY_RE = re.compile(r"^\s{4}([A-Z][A-Z0-9_]{2,})\s*[(,;]?\s*$", re.M)

for path in kotlin_files:
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
    pkg_name = pkg.group(1) if pkg else ""
    for rx in (DECL_RE, FUN_RE, PROP_RE, ENUM_ENTRY_RE):
        for match in rx.finditer(text):
            declared_types[pkg_name].add(match.group(1))

all_declared = set()
for names in declared_types.values():
    all_declared |= names

# R, BuildConfig and the ViewBinding classes are generated by AGP at build time.
GENERATED = {"R", "BuildConfig", "Manifest"}

# `android.R.color.x` / `com.foo.R.x` are framework or library constants: the
# lookbehind makes sure only *our* generated R class is inspected.
R_RE = re.compile(
    r"(?<![\w.])R\.(drawable|mipmap|layout|menu|xml|color|string|dimen|style|array|plurals|bool|integer|anim)\.([A-Za-z0-9_]+)"
)

for path in kotlin_files:
    with open(path, encoding="utf-8") as handle:
        text = handle.read()

    for typ, name in R_RE.findall(text):
        if name in defined[typ]:
            continue
        # Style / plurals names contain dots which become underscores in R.
        if typ == "style":
            dotted = name.replace("_", ".")
            if any(dotted == s or s.endswith("." + dotted) for s in defined["style"]):
                continue
        err(f"MISSING R.{typ}.{name} referenced in {rel(path)}")

    # Internal imports must resolve to something we declare (generated
    # symbols excluded).
    for imp in re.findall(r"^import\s+(com\.nimbus\.browser[\w.]*)", text, re.M):
        last = imp.split(".")[-1]
        if last in GENERATED or last.endswith("Binding"):
            continue
        if last not in all_declared:
            err(f"MISSING type for import {imp} in {rel(path)}")

# --------------------------------------------------------------------------- #
# 4/5. ViewBinding usage
# --------------------------------------------------------------------------- #

layout_ids: dict[str, set[str]] = {}
for path in walk(os.path.join(RES, "layout"), (".xml",)):
    name = os.path.splitext(os.path.basename(path))[0]
    ids: set[str] = set()
    try:
        for node in ET.parse(path).iter():
            raw = node.get(ANDROID_NS + "id")
            if raw and raw.startswith("@+id/"):
                ids.add(raw[5:])
    except ET.ParseError:
        pass
    layout_ids[name] = ids

# layout -> binding class name, incl. the "view_" layouts
def binding_to_layout(binding: str) -> str | None:
    snake = re.sub(r"(?<!^)(?=[A-Z])", "_", binding).lower()
    snake = snake.replace("_binding", "")
    return snake if snake in layout_ids else None


for path in kotlin_files:
    with open(path, encoding="utf-8") as handle:
        text = handle.read()

    # var name -> every binding type it is ever assigned from
    var_bindings: dict[str, set[str]] = defaultdict(set)
    for var_name, binding in re.findall(
        r"(?:lateinit\s+var|val|var)\s+(\w+)\s*:\s*(\w+Binding)\b", text
    ):
        var_bindings[var_name].add(binding)
    for var_name, binding in re.findall(
        r"(?:val|var)\s+(\w+)\s*=\s*(\w+Binding)\s*\.\s*(?:inflate|bind)\b", text
    ):
        var_bindings[var_name].add(binding)

    for var_name, bindings in var_bindings.items():
        # Union the ids of every layout this variable can be, so reused local
        # names across functions do not produce false positives.
        ids: set[str] = {"root"}
        found_layout = False
        for binding in bindings:
            layout = binding_to_layout(binding)
            if layout is None:
                err(f"BINDING {binding} in {rel(path)} has no matching layout file")
                found_layout = True
                break
            found_layout = True
            ids |= layout_ids[layout]
        if not found_layout:
            continue
        for accessed in set(re.findall(rf"(?<![\w.]){var_name}\.([A-Za-z_]\w*)", text)):
            if accessed in ids:
                continue
            if accessed in ("isInitialized", "inflate", "bind"):
                continue
            err(
                f"MISSING id accessed as {var_name}.{accessed} in {rel(path)} "
                f"(candidates: {sorted(bindings)})"
            )

# --------------------------------------------------------------------------- #
# 6. Manifest classes exist
# --------------------------------------------------------------------------- #

for cls in manifest_classes:
    if cls.startswith("."):
        cls = "com.nimbus.browser" + cls
    simple = cls.split(".")[-1]
    if simple not in all_declared:
        err(f"MANIFEST class {cls} is not declared in any Kotlin source")

# --------------------------------------------------------------------------- #
# 8. Kotlin delimiter balance (cheap syntax smoke test)
# --------------------------------------------------------------------------- #

def strip_kotlin(source: str) -> str:
    """Remove comments, string/char literals and escapes."""
    out = []
    i, n = 0, len(source)
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


for path in kotlin_files:
    with open(path, encoding="utf-8") as handle:
        code = strip_kotlin(handle.read())
    for opener, closer, label in (("{", "}", "braces"), ("(", ")", "parens"), ("[", "]", "brackets")):
        if code.count(opener) != code.count(closer):
            err(
                f"UNBALANCED {label} in {rel(path)}: "
                f"{code.count(opener)} '{opener}' vs {code.count(closer)} '{closer}'"
            )

# --------------------------------------------------------------------------- #
# 9. Preference keys declared in XML must match the Prefs.KEY_* constants
# --------------------------------------------------------------------------- #

prefs_src_path = os.path.join(JAVA, "com", "nimbus", "browser", "core", "Prefs.kt")
if os.path.exists(prefs_src_path):
    with open(prefs_src_path, encoding="utf-8") as handle:
        prefs_src = handle.read()
    known = set(re.findall(r'const val (?:KEY_\w+)\s*=\s*"([^"]+)"', prefs_src))

    settings_xml = os.path.join(RES, "values", "strings_settings.xml")
    if os.path.exists(settings_xml):
        with open(settings_xml, encoding="utf-8") as handle:
            declared = dict(
                re.findall(
                    r'<string name="(PREF_KEY_\w+)"[^>]*>([^<]+)</string>', handle.read()
                )
            )
        for _key_name, value in declared.items():
            if value not in known:
                warn(
                    f"PREF key '{value}' has no Prefs.KEY_* constant "
                    f"(action-only preference, read directly by its screen)"
                )

# --------------------------------------------------------------------------- #
# 10. entries / entryValues arrays must be the same length
# --------------------------------------------------------------------------- #

for path in walk(os.path.join(RES, "values"), (".xml",)):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    arrays = {}
    for node in root:
        if node.tag in ("string-array", "integer-array", "array"):
            arrays[node.get("name")] = len(list(node))
    for base in list(arrays):
        values_key = f"{base}_values"
        if values_key in arrays and arrays[values_key] != arrays[base]:
            err(
                f"ARRAY LENGTH MISMATCH in {rel(path)}: "
                f"{base}={arrays[base]} but {values_key}={arrays[values_key]}"
            )

# --------------------------------------------------------------------------- #
# 10b. Two resources with the SAME qualifier (and type) is an aapt2 error.
#      `values/` + `values-night/` is the intended override mechanism, so only
#      identical qualifier sets collide.
# --------------------------------------------------------------------------- #

def qualifier_of(path: str) -> str:
    base = os.path.basename(os.path.dirname(path))
    return "" if base == "values" else base[len("values-"):]


value_defs: dict[tuple[str, str, str], list[str]] = defaultdict(list)
for path in walk(os.path.join(RES, "values"), (".xml",)) + walk(
    os.path.join(RES, "values-night"), (".xml",)
):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for node in root:
        name = node.get("name")
        tag = node.tag.split("}")[-1]
        rtype = VALUE_TAGS.get(tag)
        if not (name and rtype):
            continue
        value_defs[(rtype, name, qualifier_of(path))].append(rel(path))

for (rtype, name, qual), files in sorted(value_defs.items()):
    if len(files) > 1:
        err(
            f"DUPLICATE @{rtype}/{name} for qualifier '{qual or 'default'}' "
            f"defined in: {', '.join(files)}"
        )

# --------------------------------------------------------------------------- #
# Report
# --------------------------------------------------------------------------- #

print(f"XML files checked      : {len(all_xml)}")
print(f"Kotlin files checked   : {len(kotlin_files)}")
print(f"Layouts checked        : {len(layout_ids)}")
print(f"Resources defined      : "
      + ", ".join(f"{k}={len(v)}" for k, v in sorted(defined.items()) if v))
print()
for w in warnings:
    print(f"WARN  {w}")
for e in errors:
    print(f"ERROR {e}")
print()
if errors:
    print(f"FAILED with {len(errors)} error(s)")
    sys.exit(1)
print("OK - no static consistency errors found")
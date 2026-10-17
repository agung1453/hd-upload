#!/usr/bin/env python3
"""
Verify that resource references coming from *libraries* actually exist.

`aapt2` fails the link step with messages like

    error: attribute indicatorDirection not found

whenever a layout uses an attribute that no resource in the dependency graph
declares, or a style whose parent was renamed/removed upstream. Both mistakes
are easy to make and are only caught during a real build:

  * Material split `indicatorDirection` into `indicatorDirectionLinear`
    (LinearProgressIndicator) and `indicatorDirectionCircular`
    (CircularProgressIndicator) -- see app/src/main/res/layout/activity_browser.xml
  * `-ktx` modules (preference-ktx, activity-ktx, ...) are thin wrappers with
    no resources of their own; the attributes live in the base artifact

This script reproduces that link-time check without Gradle or the Android SDK:

  1. read the direct dependencies from app/build.gradle.kts
  2. download each AAR (cached under $TMPDIR) and union every <attr name=...>
     and every <style name=...>/parent
  3. scan the project for `app:` attributes, `@style/` references and
     <style parent="..."> and report anything that resolves to nothing

It does NOT check the `android:` namespace, which needs android.jar.

Usage:  python3 tools/check_xml_attributes.py
Needs network access on first run. Exit code 1 means a reference is unresolved.
"""

from __future__ import annotations

import hashlib
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD_FILE = os.path.join(ROOT, "app", "build.gradle.kts")
RES = os.path.join(ROOT, "app", "src", "main", "res")
CACHE = os.path.join(os.environ.get("TMPDIR", "/tmp"), "nimbus-aar-cache")

GOOGLE_MAVEN = "https://dl.google.com/dl/android/maven2/"
MAVEN_CENTRAL = "https://repo1.maven.org/maven2/"

# Pure-JAR dependencies carry no resources; skip them quietly.
JAR_ONLY = ("kotlinx-coroutines-android",)

fetched: list[str] = []


def log(msg: str) -> None:
    print(msg, flush=True)


def direct_dependencies() -> list[tuple[str, str, str]]:
    text = open(BUILD_FILE, encoding="utf-8").read()
    out = []
    for coord in dict.fromkeys(re.findall(r'implementation\("([^"]+)"\)', text)):
        parts = coord.split(":")
        if len(parts) == 3:
            out.append(tuple(parts))
    return out


def cache_path(group: str, artifact: str, version: str) -> str:
    key = hashlib.sha1(f"{group}:{artifact}:{version}".encode()).hexdigest()[:16]
    os.makedirs(CACHE, exist_ok=True)
    return os.path.join(CACHE, f"{artifact}-{version}-{key}.aar")


def fetch(group: str, artifact: str, version: str) -> str | None:
    path = cache_path(group, artifact, version)
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return path
    rel = f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.aar"
    for base in (GOOGLE_MAVEN, MAVEN_CENTRAL):
        try:
            with urllib.request.urlopen(base + rel, timeout=90) as resp:
                data = resp.read()
        except Exception:
            continue
        if data[:2] == b"PK":  # a real zip, not an HTML error page
            with open(path, "wb") as fh:
                fh.write(data)
            return path
    return None


def read_values(zf: zipfile.ZipFile):
    """Yield the root of every res/values*.xml inside an AAR."""
    for name in zf.namelist():
        if name.startswith("res/values") and name.endswith(".xml"):
            try:
                yield ET.fromstring(zf.read(name))
            except ET.ParseError:
                continue


def aar_attrs(path: str) -> set[str]:
    found: set[str] = set()
    try:
        with zipfile.ZipFile(path) as zf:
            for root in read_values(zf):
                for node in root.iter("attr"):
                    if node.get("name"):
                        # framework attrs arrive as "android:indeterminate"
                        found.add(node.get("name").split(":")[-1])
    except (zipfile.BadZipFile, OSError):
        pass
    return found


def aar_styles(path: str) -> set[str]:
    names: set[str] = set()
    try:
        with zipfile.ZipFile(path) as zf:
            for root in read_values(zf):
                for style in root.iter("style"):
                    if style.get("name"):
                        names.add(style.get("name"))
                    if style.get("parent"):
                        names.add(style.get("parent"))
    except (zipfile.BadZipFile, OSError):
        pass
    return names


def project_values():
    for dirpath, _dirs, files in os.walk(RES):
        if not os.path.basename(dirpath).startswith("values"):
            continue
        for fn in files:
            if fn.endswith(".xml"):
                path = os.path.join(dirpath, fn)
                try:
                    yield path, ET.parse(path).getroot()
                except ET.ParseError:
                    continue


def used_app_attrs() -> dict[str, set[str]]:
    used: dict[str, set[str]] = {}
    for dirpath, _dirs, files in os.walk(RES):
        for fn in files:
            if not fn.endswith(".xml"):
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, ROOT)
            for match in re.finditer(r"\bapp:([A-Za-z_]\w*)", open(path, encoding="utf-8").read()):
                used.setdefault(match.group(1), set()).add(rel)
    return used


def main() -> int:
    log("Reading direct dependencies from app/build.gradle.kts ...")
    deps = direct_dependencies()
    log(f"  {len(deps)} declared")

    known_attrs: set[str] = set()
    for _path, root in project_values():
        for node in root.iter("attr"):
            if node.get("name"):
                known_attrs.add(node.get("name"))
    log(f"Attributes declared by the project itself: {len(known_attrs)}")

    # `-ktx` modules are thin wrappers; their attributes live in the base module.
    wanted: list[tuple[str, str, str]] = []
    for group, artifact, version in deps:
        wanted.append((group, artifact, version))
        if artifact.endswith("-ktx"):
            wanted.append((group, artifact[: -len("-ktx")], version))

    known_styles: set[str] = set()
    unresolved: list[str] = []

    for group, artifact, version in wanted:
        path = fetch(group, artifact, version)
        if path is None:
            if artifact not in JAR_ONLY and not artifact.endswith("-android"):
                unresolved.append(f"{group}:{artifact}:{version}")
                log(f"  !! could not fetch {group}:{artifact}:{version} (ignored)")
            continue
        attrs, styles = aar_attrs(path), aar_styles(path)
        known_attrs |= attrs
        known_styles |= styles
        fetched.append(path)
        log(f"  {group}:{artifact}:{version} -> {len(attrs)} attrs, {len(styles)} styles")

    # ---- project side ----
    own_styles: set[str] = set()
    style_parents: list[tuple[str, str, str]] = []
    for path, root in project_values():
        for style in root.iter("style"):
            name = style.get("name")
            if not name:
                continue
            own_styles.add(name)
            if style.get("parent"):
                style_parents.append((name, style.get("parent"), os.path.relpath(path, ROOT)))

    problems: list[str] = []

    used = used_app_attrs()
    log("")
    log(f"Distinct app: attributes used in the project: {len(used)}")
    for attr in sorted(used):
        ok = attr in known_attrs
        log(f"  [{'OK ' if ok else 'MISSING'}] app:{attr}")
        if not ok:
            for f in sorted(used[attr]):
                problems.append(f"app:{attr} is declared by neither the project nor any dependency  ({f})")

    log("")
    log(f"Style parents to verify: {len(style_parents)}")
    for name, parent, path in style_parents:
        satisfied = (
            parent in known_styles
            or parent in own_styles
            # `Widget.Nimbus.Button.Text` implicitly extends `Widget.Nimbus.Button`
            or ("." in name and name.rsplit(".", 1)[0] in own_styles)
        )
        log(f"  [{'OK ' if satisfied else 'MISSING'}] <style name=\"{name}\"> parent=\"{parent}\"")
        if not satisfied:
            problems.append(f"style parent=\"{parent}\" of <style name=\"{name}\"> does not exist  ({path})")

    style_refs = 0
    for dirpath, _dirs, files in os.walk(RES):
        for fn in files:
            if not fn.endswith(".xml"):
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, ROOT)
            for match in re.finditer(r'"@style/([A-Za-z_][\w.]*)"', open(path, encoding="utf-8").read()):
                style_refs += 1
                name = match.group(1)
                if name not in known_styles and name not in own_styles:
                    problems.append(f"@style/{name} referenced in {rel} does not exist")

    if unresolved:
        log("")
        log("NOTE: these could not be downloaded, so their resources are absent")
        log("      from the known set; a MISSING verdict may be a false positive.")

    log("")
    if problems:
        for p in problems:
            log(f"ERROR {p}")
        log("")
        log(f"FAILED: {len(problems)} unresolved resource reference(s)")
        return 1

    log(f"OK - {len(used)} app: attributes and {style_refs} @style refs all resolve")
    return 0


if __name__ == "__main__":
    sys.exit(main())
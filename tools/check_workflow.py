#!/usr/bin/env python3
"""
Validate .github/workflows/*.yml against the GitHub Actions schema.

GitHub rejects a whole workflow if a key appears at a level where it is not
allowed, e.g. the step-level

    - name: Publish release
      permissions:        # <- invalid here
        contents: write

which fails with `(Line: 128, Col: 9): Unexpected value 'permissions'`.
`permissions` is only legal at the top level of the workflow or of a job.

This checker validates:
  * YAML parses
  * only known keys at workflow / job / step level
  * every step has exactly one of `uses` / `run`
  * every job has `runs-on` (or `uses` for a reusable workflow)
  * `needs` refers to an existing job
  * `on:` is present (PyYAML parses the bare key `on` as boolean True)

Usage:  python3 tools/check_workflow.py [path ...]
Exit code 1 if the workflow is invalid.
"""

from __future__ import annotations

import glob
import os
import sys

import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# https://docs.github.com/actions/using-workflows/workflow-syntax-for-github-actions
WORKFLOW_KEYS = {
    "name", "run-name", "on", "permissions", "env", "defaults",
    "concurrency", "jobs",
}
JOB_KEYS = {
    "name", "permissions", "needs", "if", "runs-on", "environment",
    "concurrency", "outputs", "env", "defaults", "steps", "timeout-minutes",
    "strategy", "continue-on-error", "container", "services", "uses",
    "with", "secrets",
}
STEP_KEYS = {
    "id", "if", "name", "uses", "run", "working-directory", "shell",
    "with", "env", "continue-on-error", "timeout-minutes",
}

PERMISSION_KEYS = {
    "actions", "attestations", "checks", "contents", "deployments",
    "discussions", "id-token", "issues", "models", "packages",
    "pages", "pull-requests", "repository-projects", "security-events",
    "statuses",
}


def on_key(doc: dict) -> str | None:
    """PyYAML turns the bare key `on` into the boolean True."""
    for candidate in (True, "on", "On", "ON"):
        if candidate in doc:
            return str(candidate)
    return None


def check(path: str) -> list[str]:
    problems: list[str] = []
    rel = os.path.relpath(path, ROOT)

    try:
        doc = yaml.safe_load(open(path, encoding="utf-8"))
    except yaml.YAMLError as exc:
        return [f"{rel}: YAML parse error: {exc}"]

    if not isinstance(doc, dict):
        return [f"{rel}: top level must be a mapping"]

    # ---- workflow level ----
    for key in doc:
        if str(key) not in WORKFLOW_KEYS and key is not True:
            problems.append(
                f"{rel}: '{key}' is not a valid top-level workflow key "
                f"(allowed: {', '.join(sorted(WORKFLOW_KEYS))})"
            )

    if on_key(doc) is None:
        problems.append(f"{rel}: missing the 'on:' trigger block")

    if "jobs" not in doc:
        problems.append(f"{rel}: missing 'jobs:'")
        return problems

    perms = doc.get("permissions")
    if isinstance(perms, dict):
        for scope in perms:
            if str(scope) not in PERMISSION_KEYS:
                problems.append(f"{rel}: unknown permission scope '{scope}'")

    jobs = doc["jobs"]
    job_names = list(jobs)

    # ---- jobs ----
    for name, job in jobs.items():
        if not isinstance(job, dict):
            problems.append(f"{rel}: job '{name}' must be a mapping")
            continue

        for key in job:
            if str(key) not in JOB_KEYS:
                allowed = "allowed at job level" if str(key) == "permissions" else "unknown key"
                problems.append(
                    f"{rel}: job '{name}': '{key}' is not valid here "
                    f"({allowed}). Job keys: {', '.join(sorted(JOB_KEYS))}"
                )

        if "runs-on" not in job and "uses" not in job:
            problems.append(f"{rel}: job '{name}' has neither 'runs-on' nor 'uses'")

        needs = job.get("needs")
        if needs is not None:
            listed = [needs] if isinstance(needs, str) else list(needs)
            for dep in listed:
                if dep not in job_names:
                    problems.append(f"{rel}: job '{name}' needs unknown job '{dep}'")

        jperm = job.get("permissions")
        if isinstance(jperm, dict):
            for scope in jperm:
                if str(scope) not in PERMISSION_KEYS:
                    problems.append(f"{rel}: job '{name}': unknown permission scope '{scope}'")

        # ---- steps ----
        steps = job.get("steps") or []
        if not isinstance(steps, list):
            problems.append(f"{rel}: job '{name}': 'steps' must be a list")
            continue

        for index, step in enumerate(steps):
            if not isinstance(step, dict):
                problems.append(f"{rel}: job '{name}' step {index}: must be a mapping")
                continue
            label = step.get("name") or step.get("uses") or step.get("id") or str(index)

            for key in step:
                if str(key) not in STEP_KEYS:
                    hint = ""
                    if str(key) == "permissions":
                        hint = " -- move it to the job level"
                    problems.append(
                        f"{rel}: job '{name}', step '{label}': "
                        f"'{key}' is not a valid step key{hint}"
                    )

            has_uses = "uses" in step
            has_run = "run" in step
            if has_uses and has_run:
                problems.append(
                    f"{rel}: job '{name}', step '{label}': "
                    "cannot have both 'uses' and 'run'"
                )
            elif not has_uses and not has_run:
                problems.append(
                    f"{rel}: job '{name}', step '{label}': "
                    "needs either 'uses' or 'run'"
                )

    return problems


def main(argv: list[str]) -> int:
    paths = argv[1:] or sorted(
        glob.glob(os.path.join(ROOT, ".github", "workflows", "*.yml"))
        + glob.glob(os.path.join(ROOT, ".github", "workflows", "*.yaml"))
    )
    if not paths:
        print("no workflow files found")
        return 0

    failed = False
    for path in paths:
        problems = check(path)
        rel = os.path.relpath(path, ROOT)
        if problems:
            failed = True
            print(f"INVALID {rel}")
            for p in problems:
                print(f"  - {p}")
        else:
            doc = yaml.safe_load(open(path, encoding="utf-8"))
            print(
                f"OK {rel}: jobs={list(doc.get('jobs', {}))} "
                f"triggers={list((doc.get(True) or doc.get('on') or {}).keys())}"
            )

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
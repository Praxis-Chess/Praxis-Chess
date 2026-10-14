#!/usr/bin/env python3
"""check-config.py — keep docker/app/application.yml in step with the example.

    python docker/check-config.py

The image's config is a hand-kept copy of the settings in
backend/src/main/resources/application.example.yml. A key that exists only in
the Docker copy is usually a typo or a setting that was renamed in the example
and never followed — Spring ignores unknown keys silently, so the container
would quietly run with a default. This catches that.

No dependencies: a small indentation parser is enough for these two files.
Exit 0 clean, 1 on a mismatch.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCKER = os.path.join(ROOT, "docker", "app", "application.yml")
EXAMPLE = os.path.join(ROOT, "backend", "src", "main", "resources", "application.example.yml")

# Keys the Docker config may set although the example does not show them:
# Spring Boot's own switches that the example leaves at their defaults.
DOCKER_ONLY = {
    "management.tracing.enabled",
    "praxis-chess.practice.timezone",
    "praxis-chess.practice",
}

KEY = re.compile(r"^(\s*)([A-Za-z0-9_\-\"\[\]./]+):(\s|$)")


def keys(path: str) -> set[str]:
    out, stack = set(), []
    with open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.rstrip("\n")
            stripped = line.lstrip()
            # Commented-out keys in the example still count: they document
            # settings that exist ("# commentary-model: ...").
            if stripped.startswith("#"):
                body = stripped.lstrip("#")
                if not KEY.match(body.lstrip()) or " " in body.lstrip().split(":")[0]:
                    continue
                indent = len(line) - len(stripped) + (len(body) - len(body.lstrip()) - 1)
                line = " " * max(indent, 0) + body.lstrip()
            m = KEY.match(line)
            if not m or stripped.startswith("-"):
                continue
            indent, name = len(m.group(1)), m.group(2)
            while stack and stack[-1][0] >= indent:
                stack.pop()
            stack.append((indent, name))
            out.add(".".join(n for _, n in stack))
    return out


def main() -> int:
    docker, example = keys(DOCKER), keys(EXAMPLE)
    missing = sorted(k for k in docker - example if k not in DOCKER_ONLY)
    if missing:
        print("docker/app/application.yml has keys the example does not:")
        for k in missing:
            print("  -", k)
        print("Rename them to match the example, or add them to DOCKER_ONLY with a reason.")
        return 1
    print(f"ok: {len(docker)} keys in the Docker config, all known to the example")
    return 0


if __name__ == "__main__":
    sys.exit(main())

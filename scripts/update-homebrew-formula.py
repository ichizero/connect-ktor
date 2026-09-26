#!/usr/bin/env python3
"""Prepare the tap's source Formula for a new connect-ktor release."""

import re
import sys
from pathlib import Path


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("usage: update-homebrew-formula.py FORMULA TAG SHA256")

    path = Path(sys.argv[1])
    tag = sys.argv[2]
    sha256 = sys.argv[3]
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", tag):
        raise SystemExit(f"invalid release tag: {tag}")
    if not re.fullmatch(r"[0-9a-f]{64}", sha256):
        raise SystemExit("invalid source archive SHA256")

    formula = path.read_text()
    urls = list(
        re.finditer(
            r'^  url "https://github\.com/ichizero/connect-ktor/archive/refs/tags/'
            r'(?P<tag>v[^/"]+)\.tar\.gz"$',
            formula,
            re.MULTILINE,
        )
    )
    checksums = list(
        re.finditer(r'^  sha256 "(?P<sha>[0-9a-f]{64})"$', formula, re.MULTILINE)
    )
    if len(urls) != 1 or len(checksums) != 1:
        raise SystemExit("expected one source URL and one source SHA256 in the Formula")

    current_tag = urls[0].group("tag")
    if current_tag == tag:
        if checksums[0].group("sha") != sha256:
            raise SystemExit(f"Formula already targets {tag} with a different SHA256")
        print(f"Formula already targets {tag}")
        return

    formula = (
        formula[: urls[0].start()]
        + f'  url "https://github.com/ichizero/connect-ktor/archive/refs/tags/{tag}.tar.gz"'
        + formula[urls[0].end() :]
    )
    checksums = list(re.finditer(r'^  sha256 "[0-9a-f]{64}"$', formula, re.MULTILINE))
    formula = (
        formula[: checksums[0].start()]
        + f'  sha256 "{sha256}"'
        + formula[checksums[0].end() :]
    )

    lines = formula.splitlines(keepends=True)
    starts = [i for i, line in enumerate(lines) if line.rstrip("\n") == "  bottle do"]
    if len(starts) > 1:
        raise SystemExit("expected at most one bottle block in the Formula")
    if starts:
        start = starts[0]
        end = next(
            (i for i in range(start + 1, len(lines)) if lines[i].rstrip("\n") == "  end"),
            None,
        )
        if end is None:
            raise SystemExit("unterminated bottle block in the Formula")
        del lines[start : end + 1]
        if start < len(lines) and lines[start].strip() == "":
            del lines[start]

    formula = "".join(lines).replace(
        "# v0.4.0's Go entry point rejects positional arguments, so expose the",
        "# The Go entry point rejects positional arguments, so expose the",
    )
    path.write_text(formula)
    print(f"Updated Formula from {current_tag} to {tag}")


if __name__ == "__main__":
    main()

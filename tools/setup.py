#!/usr/bin/env python3
"""One command from a fresh clone to a running build: fetch and verify the models, then start Gradle.

    python tools/setup.py                 # fetch the models, then ./gradlew build (about 5 minutes the first time)
    python tools/setup.py --task :desktop:showcase     # ... then show the station screens instead
    python tools/setup.py --no-build      # only fetch and verify the models

What it does, in order:
  1. checks models/ against the hashes in config/operating-points.json;
  2. downloads what has a public source (YuNet and SFace from the OpenCV Zoo), hash-checked;
  3. for the four models we converted ourselves, takes them from the bundle named in models/bundle-url.txt, --bundle-url or the
     PRAMAAN_MODELS_URL environment variable (see tools/fetch_models.py), hash-checked again;
  4. starts Gradle. If a model is still missing it says which, and still builds: the tests that need a model skip themselves, and
     the showcase and the reference device need none. The real liveness engine will not start until all six are present.

It never writes a file whose SHA-256 differs from the table. SFace is development use only (docs/licences.md).
Uses only the Python standard library and the Gradle wrapper in this repository.
"""
import argparse
import os
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fetch_models  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]


def gradle_command(task):
    # An absolute path: on Windows CreateProcess resolves a bare "gradlew.bat" against the parent's directory, not the cwd given
    # to subprocess, so a relative name is "file not found" when setup.py is run from anywhere but the repository root.
    wrapper = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    return [wrapper] + task.split() + ["--no-daemon"]


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--task", default="build", help="Gradle tasks to run after the models are in place (default: build)")
    ap.add_argument("--no-build", action="store_true", help="only fetch and verify the models")
    ap.add_argument("--bundle-url", help="a zip with the models we converted ourselves")
    args = ap.parse_args(argv)

    code = fetch_models.run(fetch_models.MODELS, fetch_models.expected_hashes(), False,
                            bundle=fetch_models.bundle_url(args.bundle_url, fetch_models.MODELS))
    if code != 0:
        print("\nNot every model is in place, so the real liveness engine will not start. Tests that need a model will skip,"
              " and the showcase and the reference device still work.\n")
    if args.no_build:
        return code
    print("running: " + " ".join(gradle_command(args.task)))
    build = subprocess.call(gradle_command(args.task), cwd=str(ROOT))
    return build if build != 0 else code


if __name__ == "__main__":
    sys.exit(main())

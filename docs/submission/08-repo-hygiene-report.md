# Repository hygiene report (2026-10-24)

What is tracked in git, what is not, and whether a stranger can build it. Run again before you push:
`git ls-files`, `git grep`, and a fresh clone (commands at the bottom).

## 1. What is tracked

489 files in 199 commits, one author (Bhavya Garg).

| Type | Count | Notes |
|---|---|---|
| `.java` | 408 | about 50,700 lines |
| `.md` | 28 | the Bible, the Build Map, the claim sheet, ADRs, the submission pack |
| `.png` | 10 | `docs/media/`: screens rendered offscreen from the desktop code. **No faces, no camera frames** |
| `.kts` `.json` `.py` `.properties` `.yml` `.yaml` `.xml` `.toml` `.pro` | 31 | build files, the operating-point table, the policy, tooling |
| `.pdf` `.html` | 4 | the two submission documents and their sources |
| `.onnx` | 1 | `core/src/test/resources/models/smoke_affine.onnx`: a synthetic y = 2x + 1 test fixture we generate ourselves. It is the only model-format file tracked, and `.gitignore` allows it on purpose |

Largest tracked file: under 300 KB (the two PDFs, about 225 KB and 250 KB).

## 2. Checks that came back clean

| Check | Result |
|---|---|
| Model binaries (`.onnx` `.tflite` `.pt` `.pth` `.task`) | none tracked except the synthetic fixture above |
| Keys and certificates with private keys (`.pem` `.key` `.jks` `.keystore` `.p12`) | none |
| Images or video of people (`.jpg` `.jpeg` `.bmp` `.mp4` `.avi` `.mov` `.mjpeg`) | none. `sbi/testdata/rcapture-face.json` and `*.mjpeg` hold MOSIP's test photo of a real person and are gitignored on purpose |
| Archives (`.zip`) | none |
| Secret patterns (private-key headers, AWS, GitHub, Slack, OpenAI, Google key formats) in tracked text | none |
| `password`, `secret`, `api_key`, `token` assigned to a string literal in code or config | none |
| Corpora, `third_party/`, `build/` | gitignored; nothing tracked |
| Claude Code folders (`.claude/`, `.code-review-graph/`) | gitignored; nothing tracked |
| Authors in history | one: Bhavya Garg |
| `Co-Authored-By` trailers or the word "Claude" in any commit message | **none now.** One early commit (the README rewrite) had carried a `Co-Authored-By: Claude` trailer against your standing instruction. Nothing had been pushed, so I removed that one line from that one commit message. Dates, authors and file contents are unchanged (the tree was compared before and after), but every commit after it got a new hash. The backup branch and the `refs/original` leftovers were deleted so the old history cannot be pushed by accident |

## 3. Left untracked on purpose

- `CLAUDE.md` is the project guide I read at the start of each session. It has no secrets, but it is working notes for the
  assistant, not part of the product. **It is your call** whether to publish it; today it stays out of git.

## 4. Can a stranger build it?

A fresh `git clone` into an empty directory, then `./gradlew build` with **no build cache and every task re-run**:

| | |
|---|---|
| Result | **BUILD SUCCESSFUL**, 22 of 22 tasks executed, 8 min 36 s |
| Tests | **665 run, 0 failures, 0 errors, 20 skipped** (core 367/1, sbi 37/7, desktop 57/2, harness 204/10) |
| Why 20 skipped, not 2 | The clone has no `models/*.onnx` (gitignored, licences in `docs/licences.md`) and no MockMDS fixtures. The tests that need them skip themselves, by design. On the author's machine, which has them, only 2 skip |
| Without the models | The build, the binding attack tests, the red-team report and the verifier all work. `:desktop:runScaffold` needs the models and a running MockMDS (`docs/hardware-testing-guide.md`) |
| `:harness:fusedRedTeam` in the clone | runs and prints `of 85 pitches: accepted without the flash cue 4, accepted with it 0, left to the challenge with it 3` |

The README states the 2-versus-20 difference, so a judge who clones is not surprised.

## 5. Not verified

- **CI has never run.** There is no remote. The workflow (`.github/workflows/build.yml`) is the fresh-clone build above plus
  `xvfb` on Linux for the JavaFX tests and `choco install ffmpeg` on Windows. Expect the first run to need a fix or two; read
  the log, do not delete tests.
- A clone on a second machine, a different OS, or a different JDK. Built here with JDK 24 on Windows 11.
- Anything on a phone or a real device.

## 6. Commands to re-run before you push

```bash
cd C:/Master_Brain/Projects/Pramaan
git status --short                                   # only CLAUDE.md should be untracked
git ls-files | grep -iE '\.(onnx|tflite|pt|pth|pem|key|jks|keystore|zip|mjpeg|mp4|jpg|jpeg)$'   # only smoke_affine.onnx
git grep -nIE "BEGIN (RSA |EC |OPENSSH |)PRIVATE KEY|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{30,}"       # nothing
git log --format=%B | grep -ci "co-authored-by\|claude"                                           # 0
D=$(mktemp -d); git clone -q . "$D/repo" && cd "$D/repo" && ./gradlew build --rerun-tasks --no-build-cache
```

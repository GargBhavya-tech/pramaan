# Submission checklist and plan to the deadline

Today is Saturday 24 Oct 2026. One secondary source gives the deadline as **Friday 6 Nov 2026** (13 days). Treat that as
unconfirmed until you have read it on Unstop, and **submit a complete version by Tuesday 3 Nov**: the rules say you may
submit several times and the last one before the deadline counts, so an early complete submission costs nothing and
protects you from a last-day outage.

## Step 0: the three facts to confirm today

- [ ] Deadline date, time and timezone, read on the Unstop page (not here).
- [ ] What the Unstop form asks for: file uploads, a repo link, a video link, text boxes, size and format limits.
- [ ] The rules on AI-generated or AI-assisted work, and on disclosure (see `00-START-HERE.md` section 4).

## Step 1: the repository (only you can do this)

There is no git remote. Create an **empty** repository on GitHub first (no README, no licence, no .gitignore, so the
histories do not collide), choose private or public to match the rules, then:

```bash
cd C:/Master_Brain/Projects/Pramaan
git branch -M main
git remote add origin https://github.com/GargBhavya-tech/pramaan.git   # use your real URL
git push -u origin main
```

- [ ] `CLAUDE.md` stays untracked (it is project-private); `git status` should show only that.
- [ ] If the rules want a private repo shared with a MOSIP account, share it with exactly the account they name.
- [ ] Open the repo in a private browser window and confirm the link works for someone who is not you.
- [ ] The README badge points at `GargBhavya-tech/pramaan`; if your URL differs, edit the badge line in `README.md`.
- [ ] The first GitHub Actions run will be the first CI run ever. It may fail for machine reasons (no display, no model
      files). Read the log; fix the workflow, do not delete tests. Do not let a red badge be the first thing a judge sees:
      push on Sunday, not on the last day.

## Step 2: confirm the repo builds from nothing

- [ ] `08-repo-hygiene-report.md` records a fresh-clone build on this machine (done 2026-10-24). Re-run it after any
      change: `git clone . /tmp/pramaan-check && cd /tmp/pramaan-check && ./gradlew build`.
- [ ] After you push, clone from GitHub on a second machine if you can, and run `./gradlew build`.

## Step 3: fill in the blanks only you know

- [ ] Team members: the PDFs say "Bhavya Garg". Edit `solution-document.html` and `pitch-7slides.html` (search "Team:") if
      there are more, then re-export (command at the bottom of this file).
- [ ] Repo URL in both PDFs (search "github.com/GargBhavya-tech/pramaan").
- [ ] The AI-assistance paragraph (below), filled in truthfully.

## Step 4: optional but the highest-value use of the spare days

One real result beats ten synthetic ones. `docs/research-plan-real-data.md` (experiment E1) is a six-second protocol:
a consenting person, a laptop camera and the screen as the flash light; the recorder and analyser are built.

- [ ] Run E1 with a consenting person (`--consent-given`), about 20 runs, 1 to 2 hours. Never record anyone unasked.
- [ ] Put the result in `docs/research-plan-real-data.md` and the claim sheet, with its interval and its limits, whatever
      it shows. A null result is still a result. Do not edit the claim sheet from memory.
- [ ] If you do not run E1, change nothing: the pack already says no real corpus exists.

## Step 5: the video (if the form or you want one)

- [ ] Record `07-demo-video-script.md` (3 minutes). Everyone on camera has agreed to be on it.
- [ ] Upload somewhere the judges can open without a login (unlisted link), and test the link in a private window.

## Step 6: submit

- [ ] Submit the 2-page document or the 7-slide pitch as the form asks (the rules say "or": submit whichever the form
      allows; if it allows both, submit both).
- [ ] Add the repo link and the video link if there are fields for them.
- [ ] Screenshot the confirmation page. Save it.
- [ ] If anything changes later, submit again; the last submission before the deadline is the one that counts.

## Dated plan

| Day | What | Who |
|---|---|---|
| Sat 24 Oct | Step 0, Step 1 (create repo and push), commit the pack | you |
| Sun 25 Oct | Check the first CI run; fix machine-specific failures; Step 2 | you |
| Mon 26 | Read `05-qa-prep.md`; rehearse questions 1-5 and 16 aloud to someone | you |
| Tue 27 - Thu 29 | Step 4 (E1) if a consenting person and a camera are available | you |
| Fri 30 Oct - Sun 1 Nov | Step 5: record the video; Step 3 | you |
| Mon 2 Nov | Dry run: open every link in a private window; re-read the form | you |
| **Tue 3 Nov** | **Submit a complete version** | you |
| Wed 4 - Thu 5 | Improve only if something is wrong; re-submit if you do | you |
| Fri 6 Nov | Buffer. Do not plan to submit today | |

## Paste-ready text for the form

**Tagline (under 120 characters):** Face liveness for MOSIP that leaves proof: the signed capture is bound to the live preview, and the verdict can be re-checked.

**Short description (about 300 characters):** PRAMAAN is a face liveness and presentation-attack detection engine for the MOSIP Registration Client (desktop and Android). It defines the frame-streaming and liveness interfaces, binds the unsigned preview to the signed capture, vetoes injected video, and stores signed evidence a server can re-check.

**Description:** The SBI preview stream is unsigned and the capture is signed, with nothing linking them, so liveness on the preview can pass one face while another is enrolled; Android clients never see the preview at all. PRAMAAN answers with two interfaces (`FaceFrameSource`, `LivenessEngine`); four capture-binding locks that work on unmodified SBI; cues that fail differently (passive model, moiré, flash reflection, scene consistency, stereo depth) fused with per-cue abstention; injection detection from the device identity and the OS camera registrations; and a signed, re-checkable evidence package in the face BIR. Physiological cues never count against a resident, and there is a Hindi, icon and speech mode. It also proposes an optional SBI Liveness Profile with a runnable reference device. Not measured on real attacks: no corpus exists, thresholds are provisional, nothing has run on a phone, and the desktop app is a scaffold. Every claim has a reproduction command in `docs/claim-sheet.md`.

**Technologies used:** Java (JDK 24 build, Java 11 bytecode), Gradle, ONNX Runtime 1.20, JavaFX 11, Jackson, JUnit 5, Android library (AAR), MOSIP SBI 1.x, Python tooling for model export; models YuNet, MiniFASNet, MediaPipe FaceMesh, SFace (licences in `docs/licences.md`).

**Licence:** MPL-2.0 (matches MOSIP).

## The AI-assistance paragraph (fill in the brackets with what is true, delete what is not)

> This project was developed with an AI coding assistant (Claude, by Anthropic) working under my direction. I [chose the
> problem framing and the threat model / set the priorities / reviewed the design decisions / ran the tests and the
> experiments / decided what to claim and what to disclaim]. The assistant [wrote most of the code and tests / drafted the
> documentation]. I [reviewed X and ran Y]. I did not [anything you did not do]. The commit history shows a single author
> because the repository is mine; it does not show how the code was produced, so I am stating it here.

If the 2026 rules forbid AI-assisted work, do not submit this as it stands: ask the organisers (decode@mosip.io or the
Unstop discussion board) what is allowed.

## Re-exporting the PDFs after an edit

```bash
cd C:/Master_Brain/Projects/Pramaan/docs/submission
"/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe" --headless --disable-gpu --no-pdf-header-footer \
  --print-to-pdf="$PWD/PRAMAAN-solution-2page.pdf" "file:///$PWD/solution-document.html"
"/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe" --headless --disable-gpu --no-pdf-header-footer \
  --print-to-pdf="$PWD/PRAMAAN-pitch-7slides.pdf" "file:///$PWD/pitch-7slides.html"
```

Then check the page counts (the document must be at most 2 pages; the pitch must be 7 pages).

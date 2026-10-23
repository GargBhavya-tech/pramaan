# PRAMAAN: submission pack, start here

Written 2026-10-24. Everything in this folder is for MOSIP Decode 2026, problem statement 04 (Face Liveness Detection and
Presentation Attack Detection). Read this page first; it says what is confirmed, what is not, and what only you can do.

## 1. What the submission asks for, and how sure we are

I looked for the official 2026 rules. The 2026 edition runs on **Unstop**; the Unstop page itself would not load for me
(it returned only the site title), so the table below comes from secondary sources and from the 2024-25 edition's
official pages. **Open the Unstop page yourself and check every row marked "verify".**

| Item | What I found | Source | Confidence |
|---|---|---|---|
| Edition dates | 17 Aug 2026 to **6 Nov 2026** (virtual) | [startupgrantsindia.com](https://www.startupgrantsindia.com/competitions/mosip-decode-2026) | **Verify.** One secondary source. Evaluation is "in November"; the exact cut-off time and timezone are unknown |
| Registration | closed 13-14 Sep 2026 | same; [Biometric Update](https://www.biometricupdate.com/202608/mosip-decode-2026-invites-students-to-solve-digital-id-infrastructure-challenges) | Verify that your team is registered |
| Team | 1 to 5 students | startupgrantsindia | Verify |
| What to submit | "An overview of the chosen problem statement" plus **a 1-2 page document or a 5-7 slide pitch** detailing the solution. Multiple submissions allowed; the **last one before the deadline** is final | startupgrantsindia (same wording as the 2024-25 official rules page) | Verify on Unstop |
| Source code | Previous edition: mandatory, on GitHub/GitLab/Bitbucket, with version history; a private repo shared with a named MOSIP account, links emailed to decode@mosip.io | [HackerEarth 2024-25 submission criteria](https://mosipdecode.hackerearth.com/challenges/hackathon/mosip-decode/custom-tab/submission-criteria) | **Unknown for 2026.** Check whether Unstop asks for a repo link and whether it must be private |
| Demo video | Optional in 2024-25 (2-5 min). A 2025 FAQ snippet called it required | search summaries | Unknown for 2026; a script is in `07` |
| Judging | Unstop page says relevance, creativity, usability, impact. 2024-25 used functionality, quality, integration with MOSIP, problem complexity | search summaries | Verify; `04-judging-map.md` covers both sets |
| IP | Source code must be provided to MOSIP on request; solutions must be original; follow the MOSIP Code of Conduct; open-source resources must respect OSI licences | [HackerEarth 2024-25 rules](https://mosipdecode.hackerearth.com/challenges/hackathon/mosip-decode/custom-tab/rules) | MPL-2.0 matches MOSIP; see section 4 |
| Prizes | USD 5,000 pool, possible MOSIP internship, showcase at MOSIP Connect 2027 | Biometric Update | Informational |
| Contact | decode@mosip.io (2024-25); Unstop discussion board | rules pages | Verify |

## 2. What is in this pack

| File | What it is | Status |
|---|---|---|
| `PRAMAAN-solution-2page.pdf` (source `solution-document.html`) | The **1-2 page document** the rules name | Done, 2 pages |
| `PRAMAAN-pitch-7slides.pdf` (source `pitch-7slides.html`) | The **5-7 slide pitch** the rules name (7 slides) | Done |
| `01-problem-overview.md` | The "overview of the chosen problem statement" | Done |
| `04-judging-map.md` | Each judging criterion, the evidence for it, and the honest weak spot | Done |
| `05-qa-prep.md` | 25 hard questions with answers that match the claim sheet | Done; rehearse aloud |
| `06-submission-checklist.md` | Dated plan to the deadline, every step, who does it | Done; tick as you go |
| `07-demo-video-script.md` | A 3-minute storyboard using only things that can truthfully be shown | Done; **you must record it** |
| `08-repo-hygiene-report.md` | Scan of what is tracked in git (no models, corpora, keys, faces) and a fresh-clone build | Done |

Also updated: `README.md` (real screenshots, submission section), `docs/demo-script.md` (new screen labels),
`docs/claim-sheet.md` (the UI claim and its limit), `CONTRIBUTING.md` (new).

## 3. What only you can do (I cannot, or must not, do these)

1. **Read the 2026 rules on Unstop** and confirm the table in section 1. Especially the deadline time and timezone.
2. **Check the AI-assistance rule (section 4).** This is the one that could disqualify the entry.
3. **Create the GitHub repository, push, and make sure the link works.** There is no git remote yet, so nothing is pushed.
   Exact commands are in `06-submission-checklist.md`. I will not push without your say-so.
4. **Fill in the team** (the PDFs name Bhavya Garg only; edit the HTML if there are more members) and the repo URL.
5. **Record the video** with a person who has consented (never record anyone unasked).
6. **Submit on Unstop** and keep a screenshot of the confirmation.
7. Optional but valuable: post the three open questions to MOSIP (Q12, Q13, Q16; wording in `05-qa-prep.md`).

## 4. The AI-assistance rule: read this before you submit

This codebase was written with an AI coding assistant working under your direction. One search summary of the 2025
rules said copied or borrowed ideas, "including those from internet sources or generated by AI", would not qualify.
I could not read the primary text and I do not know the 2026 wording. So:

- **Read the 2026 rules.** If AI-generated work is disallowed or must be disclosed, follow that, whatever it costs.
- If disclosure is allowed or required, say it plainly. A paste-ready paragraph is in `06-submission-checklist.md`
  with blanks for the parts only you can state truthfully (what you decided, reviewed and tested yourself).
- Do not describe the work as hand-written if it was not. A judge or MOSIP reviewer asking "how was this built?" is
  likelier than any technical question, and a straight answer is the safe one. `05-qa-prep.md` question 24 has one.
- The git history has you as the only author. That is how you asked for it, and it is accurate about who owns the
  repository, but it says nothing about how the code was produced, so it cannot stand in for disclosure.

## 5. The honest one-paragraph status (use this wording anywhere)

PRAMAAN is a working liveness engine, an SBI client, an evaluation harness, a desktop registration scaffold, an Android
library and a proposal for extending the SBI device standard, with **665 automated tests passing** (2 skipped because
they need MockMDS; last full build 2026-10-24, 198 commits, about 50,700 lines of Java). **It has not been measured on
real attacks** (no corpus exists, so every threshold is provisional and no APCER or BPCER is claimed), has not run on a
real device or a phone, is a scaffold and not the MOSIP Registration Client, and no real user has tried the screens. Its
own red team found two real weaknesses in it, which are logged, not hidden. The limits come first in every document in
this pack because a judge who finds one overstated claim discounts the rest.

#!/usr/bin/env python3
"""Builds docs/submission/PRAMAAN-pitch-7slides.pptx: the 7-slide pitch as an editable PowerPoint deck.

The same seven slides as pitch-7slides.html, as native text boxes and shapes (not pictures of slides), so the team can edit
every word. Speaker notes carry what to say; pictures carry alt text.

    python -m venv build/pptx-venv
    build/pptx-venv/Scripts/python -m pip install python-pptx
    build/pptx-venv/Scripts/python tools/pptx/make_pitch_pptx.py

Honest limits are on the slides: no real attack corpus, nothing run on a device or phone, a scaffold, screens rendered offscreen.
"""
import sys
from pathlib import Path

from lxml import etree
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_CONNECTOR, MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.oxml.ns import qn
from pptx.util import Emu, Inches, Pt

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "docs" / "submission" / "PRAMAAN-pitch-7slides.pptx"
MEDIA = REPO / "docs" / "media"

# palette (the same tokens as the station UI and the HTML pitch)
BLUE = "1B4F8A"
INK = "14202E"
INK2 = "3B4A5A"
INK3 = "52606D"
INFO_BG = "E7EFF8"
INFO = "173F70"
OK = "1B6E3C"
OK_BG = "E6F4EA"
WARN = "6B4500"
WARN_BG = "FFF4D6"
ERR = "8E1220"
ERR_BG = "FDE8EA"
WHITE = "FFFFFF"
LINE = "CFD6DD"
FONT = "Calibri"
DEVANAGARI = "Nirmala UI"


def rgb(hex6):
    return RGBColor.from_string(hex6)


prs = Presentation()
prs.slide_width = Inches(13.333)
prs.slide_height = Inches(7.5)
BLANK = prs.slide_layouts[6]


def style_run(run, size, color, bold=False, italic=False, font=FONT):
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.italic = italic
    run.font.name = font
    run.font.color.rgb = rgb(color)


def add_text(slide, x, y, w, h, paras, size=18, color=INK, bold=False, align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP,
             name=None, space_after=6, line_spacing=1.08, margin=0.0):
    """paras: a list of paragraphs; a paragraph is a string or a list of (text, {overrides}) runs."""
    tb = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    if name:
        tb.name = name
    tf = tb.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    tf.margin_left = tf.margin_right = Inches(margin)
    tf.margin_top = tf.margin_bottom = Inches(margin)
    for i, para in enumerate(paras):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        p.space_after = Pt(space_after)
        p.line_spacing = line_spacing
        runs = [(para, {})] if isinstance(para, str) else para
        for text, o in runs:
            r = p.add_run()
            r.text = text
            style_run(r, o.get("size", size), o.get("color", color), o.get("bold", bold), o.get("italic", False),
                      o.get("font", FONT))
    return tb


def add_box(slide, x, y, w, h, fill=INFO_BG, line=BLUE, shape=MSO_SHAPE.ROUNDED_RECTANGLE, name=None, radius=0.08,
            line_w=2.0):
    s = slide.shapes.add_shape(shape, Inches(x), Inches(y), Inches(w), Inches(h))
    if name:
        s.name = name
    s.fill.solid()
    s.fill.fore_color.rgb = rgb(fill)
    if line is None:
        s.line.fill.background()
    else:
        s.line.color.rgb = rgb(line)
        s.line.width = Pt(line_w)
    s.shadow.inherit = False
    if shape == MSO_SHAPE.ROUNDED_RECTANGLE:
        s.adjustments[0] = radius
    tf = s.text_frame
    tf.margin_left = tf.margin_right = Inches(0.15)
    tf.margin_top = tf.margin_bottom = Inches(0.1)
    return s


def box_text(shape, paras, size=16, color=INK, bold=False, align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.MIDDLE, space_after=4):
    tf = shape.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    for i, para in enumerate(paras):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        p.space_after = Pt(space_after)
        runs = [(para, {})] if isinstance(para, str) else para
        for text, o in runs:
            r = p.add_run()
            r.text = text
            style_run(r, o.get("size", size), o.get("color", color), o.get("bold", bold), o.get("italic", False),
                      o.get("font", FONT))


def arrow(slide, x1, y1, x2, y2, color=INK2, width=2.5, dash=False, name=None):
    c = slide.shapes.add_connector(MSO_CONNECTOR.STRAIGHT, Inches(x1), Inches(y1), Inches(x2), Inches(y2))
    if name:
        c.name = name
    c.line.color.rgb = rgb(color)
    c.line.width = Pt(width)
    ln = c.line._get_or_add_ln()
    if dash:
        d = etree.SubElement(ln, qn("a:prstDash"))
        d.set("val", "dash")
    tail = etree.SubElement(ln, qn("a:tailEnd"))
    tail.set("type", "triangle")
    return c


def footer(slide, n):
    add_text(slide, 0.6, 7.0, 8, 0.3, ["PRAMAAN  ·  MOSIP Decode 2026  ·  PS04"], size=12, color=INK3, name="Footer")
    add_text(slide, 11.7, 7.0, 1.0, 0.3, ["%d / 7" % n], size=12, color=INK3, align=PP_ALIGN.RIGHT, name="Slide number")


def title(slide, text, sub=None, color=BLUE):
    add_text(slide, 0.6, 0.45, 12.1, 0.9, [text], size=40, color=color, bold=True, name="Title", anchor=MSO_ANCHOR.TOP)
    if sub:
        add_text(slide, 0.6, 1.3, 12.1, 0.75, [sub], size=18, color=INK2, name="Subtitle")


def notes(slide, text):
    slide.notes_slide.notes_text_frame.text = text


def picture(slide, path, x, y, w, alt, name):
    pic = slide.shapes.add_picture(str(path), Inches(x), Inches(y), width=Inches(w))
    pic.name = name
    pic._element.nvPicPr.cNvPr.set("descr", alt)
    pic.line.color.rgb = rgb(LINE)
    pic.line.width = Pt(1)
    return pic


# ----------------------------------------------------------------------------------------------------------------- 1
s = prs.slides.add_slide(BLANK)
s.background.fill.solid()
s.background.fill.fore_color.rgb = rgb(BLUE)
add_text(s, 0.8, 1.35, 11.8, 1.3,
         [[("PRAMAAN ", {"size": 72, "bold": True, "color": WHITE}),
           ("प्रमाण", {"size": 72, "color": WHITE, "font": DEVANAGARI})]],
         size=72, color=WHITE, name="Title", space_after=0)
add_text(s, 0.8, 2.75, 11.8, 0.8, ["Proof, not a verdict."], size=40, color=WHITE, bold=True, name="Tagline")
add_text(s, 0.8, 3.6, 11.0, 1.2,
         ["Face liveness and presentation-attack detection for the MOSIP Registration Client, desktop and Android, "
          "that leaves evidence a server can re-check."], size=24, color="DCE8F5", name="Summary")
add_text(s, 0.8, 5.0, 11.8, 0.9,
         ["MOSIP Decode 2026  ·  Problem Statement 04",
          "Team: Bhavya Garg  ·  github.com/GargBhavya-tech/pramaan  ·  MPL-2.0"],
         size=18, color="DCE8F5", name="Meta", space_after=2)
chip = add_box(s, 0.8, 6.1, 6.6, 0.6, fill=WARN_BG, line=WARN, name="Honest status chip", radius=0.2)
box_text(chip, ["A working engine, not yet measured on real attacks"], size=18, color="4A2F00", bold=True,
         align=PP_ALIGN.CENTER)
notes(s, "PRAMAAN, which means proof. Face liveness and presentation attack detection for the MOSIP Registration Client, for "
         "MOSIP Decode 2026, problem statement four. One honest note first: this is a working engine that has not yet been "
         "measured on real attacks.")

# ----------------------------------------------------------------------------------------------------------------- 2
s = prs.slides.add_slide(BLANK)
title(s, "The gap nobody names", "The preview is unsigned. The capture is signed. Nothing links them.")
cam = add_box(s, 0.6, 2.35, 2.3, 1.45, name="Camera and device")
box_text(cam, ["Camera /", "SBI device"], size=18, color=INFO, bold=True, align=PP_ALIGN.CENTER)
arrow(s, 2.95, 2.75, 6.2, 2.75, color=ERR, dash=True, name="Unsigned preview arrow")
add_text(s, 3.2, 2.3, 3.0, 0.4, ["unsigned preview"], size=16, color=ERR, bold=True, align=PP_ALIGN.CENTER,
         name="Unsigned label")
b1 = add_box(s, 6.3, 2.3, 6.4, 0.9, fill=ERR_BG, line=ERR, name="Liveness passes on face A")
box_text(b1, ["Liveness check passes on face A"], size=20, color=ERR, bold=True, align=PP_ALIGN.CENTER)
arrow(s, 2.95, 3.45, 6.2, 3.45, color=OK, name="Signed capture arrow")
add_text(s, 3.2, 3.0, 3.0, 0.4, ["signed capture"], size=16, color=OK, bold=True, align=PP_ALIGN.CENTER,
         name="Signed label")
b2 = add_box(s, 6.3, 3.3, 6.4, 0.9, fill=ERR_BG, line=ERR, name="Packet stores face B")
box_text(b2, ["Registration packet stores face B"], size=20, color=ERR, bold=True, align=PP_ALIGN.CENTER)
add_text(s, 0.6, 4.55, 12.1, 2.3,
         [[("Capture swap: ", {"bold": True}), ("whoever controls the preview can pass liveness with one face and enrol another.", {})],
          [("Android: ", {"bold": True}), ("the client never sees preview frames at all; the device app shows the stream and "
                                          "returns only the signed capture.", {})],
          [("Injected video ", {"bold": True}), ("(OBS, deepfake pipelines) is outside ISO/IEC 30107. ", {}),
           ("Insiders ", {"bold": True}), ("can log in with a supervisor's photo.", {})],
          [("No audit trail: ", {"bold": True}), ("a client-side pass or fail cannot be re-checked by the server later or "
                                                  "re-scored with a better model.", {})]],
         size=18, color=INK, name="The four problems", space_after=10)
footer(s, 2)
notes(s, "The preview is unsigned. The capture that is stored is signed. Nothing links the two. So whoever controls the preview "
         "can pass liveness with one face while another face is enrolled. On Android, the client never even sees the preview.")

# ----------------------------------------------------------------------------------------------------------------- 3
s = prs.slides.add_slide(BLANK)
title(s, "Our answer: two interfaces, seven questions",
      "The problem statement asks to define the frame-streaming interface and the liveness/PAD interface. We did, and bound "
      "them together.")
i1 = add_box(s, 0.6, 2.2, 5.95, 1.35, name="FaceFrameSource")
box_text(i1, [[("FaceFrameSource", {"bold": True, "size": 20})],
              [("frames and the signed capture from the same object, so the capture can be bound to what was watched",
                {"size": 15})]], color=INFO)
i2 = add_box(s, 6.75, 2.2, 5.95, 1.35, name="LivenessEngine")
box_text(i2, [[("LivenessEngine", {"bold": True, "size": 20})],
              [("frames in; states, guidance and a decision out; never a score on the screen; fails closed", {"size": 15})]],
         color=INFO)
steps = [("1", "Real sensor?", "source integrity"), ("2", "One usable face", "quality gate"),
         ("3", "Looks alive?", "passive, asks nothing"), ("4", "Attack marks?", "print, screen, flat"),
         ("5", "Fusion", "accept / challenge / reject"), ("6", "Live answer?", "random challenge"),
         ("7", "Same face?", "binding + signed evidence")]
x0, gap, w = 0.6, 0.1, 1.64
for i, (n, a, b) in enumerate(steps):
    last = i == 6
    box = add_box(s, x0 + i * (w + gap), 3.85, w, 1.55, fill=OK_BG if last else INFO_BG, line=OK if last else BLUE,
                  name="Question " + n)
    c = OK if last else INFO
    box_text(box, [[(n, {"size": 26, "bold": True, "color": OK if last else BLUE})], [(a, {"size": 14, "bold": True})],
                   [(b, {"size": 13})]], color=c, align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.TOP, space_after=2)
add_text(s, 0.6, 5.7, 12.1, 1.2,
         [[("Mode C (desktop): ", {"bold": True}), ("the engine runs in the client, on the unsigned M-JPEG stream (3 fps "
                                                    "guaranteed, so every cue is gated on the measured frame rate).", {})],
          [("Mode D (Android): ", {"bold": True}), ("the engine ships as a library inside the SBI device app, where the "
                                                    "frames actually are.", {})]],
         size=16, color=INK, name="Deployment modes", space_after=8)
footer(s, 3)
notes(s, "PRAMAAN defines the two interfaces the problem statement asks for: a frame source that returns frames and the signed "
         "capture from the same object, and a liveness engine that takes frames in and gives states, guidance and a decision "
         "out. Seven questions are answered in order, from is this a real sensor to is the stored face provably the one that "
         "passed.")

# ----------------------------------------------------------------------------------------------------------------- 4
s = prs.slides.add_slide(BLANK)
title(s, "What is different", "Each idea is built and tested. None is claimed beyond what the claim sheet allows.")
cards = [
    ("Capture binding", "Four locks before any accept: nonce echoed in the device-signed capture, frame hash chain, identity "
                        "continuity, time. Works on unmodified SBI."),
    ("Evidence, not a verdict", "A signed JWS in the face BIR. One command re-verifies it; a newer model can re-score stored "
                                "crops. Merkle transparency log."),
    ("Cues that fail differently", "Passive, moiré, flash reflection (20.7-bit random colour code), scene, stereo. Each "
                                   "abstains when the station lacks the hardware."),
    ("Injection defence", "Device identity chain, OS camera registrations (OBS found via DirectShow), JPEG stream "
                          "fingerprint. Not a renamed-driver defence."),
    ("Inclusion by design", "Blink, pulse and pupil reflex only ever vote for life. Hindi, icons, speech, accessibility "
                            "profiles, infant mode, dual-control exceptions."),
    ("An SBI proposal", "An optional Liveness Profile (signed preview, signed light log, signed burst) with a runnable "
                        "reference device. Our own draft; not yet posted to MOSIP."),
]
cw, ch, gx, gy = 3.9, 2.15, 0.2, 0.25
for i, (head, body) in enumerate(cards):
    cx = 0.6 + (i % 3) * (cw + gx)
    cy = 2.25 + (i // 3) * (ch + gy)
    card = add_box(s, cx, cy, cw, ch, name="Card: " + head)
    box_text(card, [[(head, {"size": 20, "bold": True, "color": BLUE})], [(body, {"size": 15})]], color=INK,
             anchor=MSO_ANCHOR.TOP, space_after=6)
footer(s, 4)
notes(s, "Six ideas, each built and tested. Capture binding and the evidence package are the core. The cues are chosen so their "
         "holes do not overlap, and each one abstains rather than guessing when the station lacks what it needs. Inclusion is "
         "by design: physiological cues only ever vote for life. The SBI Liveness Profile is our own draft and has not been "
         "posted to MOSIP; we first want to ask how proposals are accepted.")

# ----------------------------------------------------------------------------------------------------------------- 5
s = prs.slides.add_slide(BLANK)
title(s, "The station, as built",
      "Rendered from the real code, offscreen. One plain sentence, an icon, no score. No live camera in these images.")
picture(s, MEDIA / "capture-screen.png", 0.6, 2.15, 6.0,
        "The resident capture screen mid-challenge: a step rail on the left, a camera area with a face oval and an arrow for a "
        "head turn, one plain instruction below it, and the operator's task panel on the right. The camera area shows a "
        "placeholder because no camera is connected.", "Screenshot: resident capture")
picture(s, MEDIA / "hindi-accessibility.png", 6.75, 2.15, 6.0,
        "The same screen in Hindi, with the larger-text and high-contrast switches in the header.",
        "Screenshot: Hindi")
add_text(s, 0.6, 5.95, 6.0, 0.9,
         [[("Resident capture. ", {"bold": True}), ("Step rail, one instruction, the Offline chip, the scaffold labelled as a "
                                                     "scaffold.", {})]], size=14, color=INK2, name="Caption left")
add_text(s, 6.75, 5.95, 6.0, 0.9,
         [[("Hindi, larger text, high contrast ", {"bold": True}), ("are one click. (Machine-drafted Hindi, not yet "
                                                                     "reviewed.)", {})]],
         size=14, color=INK2, name="Caption right")
add_text(s, 0.6, 6.5, 12.1, 0.45,
         [[("Fails closed. ", {"bold": True}), ("If the policy, the table or a model does not verify, nothing can be registered "
                                                 "and the screen says what to check.", {})]],
         size=14, color=INK, name="Fails closed note")
footer(s, 5)
notes(s, "These are the real screens, rendered offscreen from the actual code, so there is no live camera in them. The resident "
         "sees one sentence and an icon, never a score and never the reason a check failed. Hindi, larger text and high "
         "contrast are one click each. If the policy, a model or the certificate does not verify, nothing can be registered. "
         "You can click through all of it with: gradlew desktop showcase.")

# ----------------------------------------------------------------------------------------------------------------- 6
s = prs.slides.add_slide(BLANK)
title(s, "What is verified, and what is not",
      "No real attack corpus exists, so no APCER or BPCER is claimed. Zero failures in 60 attacks would still allow a true "
      "rate near 6%.")
ok = add_box(s, 0.6, 2.25, 5.95, 3.2, fill=OK_BG, line=OK, name="Verified")
box_text(ok, [[("Verified (one command each)", {"size": 22, "bold": True, "color": OK})],
              [("673 automated tests pass (20 skipped on a fresh clone: no models)", {})],
              [("Flash code: 1,679,616 sequences = 20.7 bits", {})],
              [("At most 3 colour changes a second, 900 sequences", {})],
              [("Insider signal: 6 in 40 at 2% gives z ≈ 5.87", {})],
              [("Transparency log matches the CT test vectors", {})],
              [("Each capture-swap attack is a failing test", {})]],
         size=16, color=INK, anchor=MSO_ANCHOR.TOP, space_after=6)
no = add_box(s, 6.75, 2.25, 5.95, 3.2, fill=WARN_BG, line=WARN, name="Not claimed")
box_text(no, [[("Not done, not claimed", {"size": 22, "bold": True, "color": WARN})],
              [("No error rate on real attacks; thresholds provisional", {})],
              [("Not run on a device, a phone or a certified SBI device", {})],
              [("Desktop app is a scaffold, not the Registration Client", {})],
              [("No user has tried the screens; Hindi unreviewed", {})],
              [("Flash, stereo, scene cues: synthetic data and fakes only", {})]],
         size=16, color=INK, anchor=MSO_ANCHOR.TOP, space_after=6)
add_text(s, 0.6, 5.7, 12.1, 1.2,
         [[("We attacked ourselves. ", {"bold": True}),
           ("On synthetic screen replays with the passive model assumed fooled and no flash light, the fusion accepted ", {}),
           ("4 of 85", {"bold": True}), (" display pitches; with the flash cue, ", {}), ("0", {"bold": True}),
           (". Logged as R-03 with R-01 (moiré blind pitches); the fix is proposed, not enabled.", {})]],
         size=18, color=INK, name="Red team result")
footer(s, 6)
notes(s, "What we verify, and what we do not claim. Six hundred seventy three automated tests pass; on a fresh clone twenty are "
         "skipped because the face models are not in the repository. We have not measured an error rate on real attacks, "
         "because no real corpus exists. It has not run on a real device or a phone, and no user has tried the screens yet. "
         "Our own red team found that, on synthetic replays with no flash light, the fusion accepted four of eighty five "
         "display pitches, and none with the flash cue. We logged it and did not turn on the proposed fix, because it would "
         "raise false rejections we cannot price without real data.")

# ----------------------------------------------------------------------------------------------------------------- 7
s = prs.slides.add_slide(BLANK)
title(s, "Impact and next steps", "Deployable on today's SBI, honest about what is unmeasured.")
add_text(s, 0.6, 2.2, 5.9, 0.5, ["For MOSIP"], size=22, color=BLUE, bold=True, name="For MOSIP heading")
add_text(s, 0.6, 2.75, 5.9, 2.0,
         ["Closes the capture-swap gap with no spec change", "Leaves proof a server can re-check or re-score",
          "MPL-2.0; JavaFX 11 as in the Registration Client", "Inclusion rules keep it from locking residents out"],
         size=17, color=INK, name="For MOSIP list", space_after=8)
add_text(s, 0.6, 4.75, 5.9, 0.5, ["What we would ask MOSIP"], size=22, color=BLUE, bold=True, name="Asks heading")
add_text(s, 0.6, 5.3, 5.9, 1.3, ["How should an SBI extension be proposed?", "Licence terms of two anti-spoofing models?"],
         size=17, color=INK, name="Asks list", space_after=8)
add_text(s, 6.9, 2.2, 5.8, 0.5, ["Next, in order"], size=22, color=BLUE, bold=True, name="Next heading")
nxt = [("1", "One real experiment: ", "does the flash cue separate a live face from a replay? (recorder and analyser "
                                      "built)"),
       ("2", "A consented corpus, ", "then every threshold from data (calibrator built)"),
       ("3", "A real device, ", "a second camera, a phone"),
       ("4", "Real users: ", "operators and residents, a native Hindi review")]
y = 2.8
for n, bold_part, rest in nxt:
    badge = add_box(s, 6.9, y, 0.5, 0.5, fill=BLUE, line=None, shape=MSO_SHAPE.OVAL, name="Step " + n)
    box_text(badge, [n], size=16, color=WHITE, bold=True, align=PP_ALIGN.CENTER)
    add_text(s, 7.55, y - 0.04, 5.15, 0.95, [[(bold_part, {"bold": True}), (rest, {})]], size=16, color=INK,
             name="Next step " + n)
    y += 0.98
add_text(s, 6.9, 6.55, 5.8, 0.4,
         [[("Repo: ", {"bold": True}), ("github.com/GargBhavya-tech/pramaan", {})]], size=14, color=INK, name="Repo")
footer(s, 7)
notes(s, "Next: one real experiment, a consented corpus, and a real device. We would like to ask MOSIP how an SBI extension "
         "should be proposed. The code is MPL two point zero, like MOSIP. Every claim and its reproduction command is in "
         "docs/claim-sheet.md. Thank you.")

prs.core_properties.title = "PRAMAAN: pitch (MOSIP Decode 2026, PS04)"
prs.core_properties.author = "Bhavya Garg"
prs.core_properties.subject = "Face liveness and presentation-attack detection for the MOSIP Registration Client"
OUT.parent.mkdir(parents=True, exist_ok=True)
prs.save(str(OUT))
print("wrote", OUT, "(%d slides)" % len(prs.slides))
sys.exit(0)

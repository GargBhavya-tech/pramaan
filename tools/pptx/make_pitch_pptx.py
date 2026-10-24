#!/usr/bin/env python3
"""Builds the PRAMAAN pitch as editable PowerPoint decks.

    docs/submission/PRAMAAN-pitch-7slides.pptx        exactly seven slides: the submission pitch
    docs/submission/PRAMAAN-pitch-full-10slides.pptx  the same seven plus a three-slide appendix (the real screens, our own
                                                      red-team result, the stack and licences)

Everything is native text and shapes (nothing is a picture of a slide) except the screenshots of the real UI, so every word and
every diagram can be edited in PowerPoint. Speaker notes carry what to say; pictures carry alt text.

The deck is built around one point: what is DIFFERENT about PRAMAAN's ideas, and how each one works. Six ideas, numbered 1 to 6,
carry through the deck: slide 3 sets them against the naive approach, slide 4 puts each on the architecture, slides 5 and 6
show how they work. Honest limits are on the slides: no real attack corpus, nothing run on a device or phone, a scaffold.

    python -m venv build/pptx-venv
    build/pptx-venv/Scripts/python -m pip install python-pptx
    build/pptx-venv/Scripts/python tools/pptx/make_pitch_pptx.py
"""
import sys
from pathlib import Path

from lxml import etree
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_CONNECTOR, MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.oxml.ns import qn
from pptx.util import Inches, Pt

REPO = Path(__file__).resolve().parents[2]
OUT_DIR = REPO / "docs" / "submission"
MEDIA = REPO / "docs" / "media"

# palette: the station UI's tokens
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
AMBER = "F5A623"
GREY_BG = "EEF1F4"
WHITE = "FFFFFF"
LINE = "CFD6DD"
FONT = "Calibri"
MONO = "Courier New"
DEVANAGARI = "Nirmala UI"

# the six flash colours the engine really uses (FlashColour.java), as display colours
FLASH = {"WHITE": "FFFFFF", "YELLOW": "FFE933", "GREEN": "33FF33", "CYAN": "33FFFF", "BLUE": "334DFF", "MAGENTA": "FF4DFF"}


def rgb(h):
    return RGBColor.from_string(h)


class Deck:
    def __init__(self, total):
        self.prs = Presentation()
        self.prs.slide_width = Inches(13.333)
        self.prs.slide_height = Inches(7.5)
        self.blank = self.prs.slide_layouts[6]
        self.total = total

    def slide(self):
        return self.prs.slides.add_slide(self.blank)


def style_run(run, size, color, bold=False, italic=False, font=FONT):
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.italic = italic
    run.font.name = font
    run.font.color.rgb = rgb(color)


def fill_frame(tf, paras, size, color, bold, align, space_after, line_spacing=None):
    for i, para in enumerate(paras):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        p.space_after = Pt(space_after)
        if line_spacing:
            p.line_spacing = line_spacing
        runs = [(para, {})] if isinstance(para, str) else para
        for text, o in runs:
            r = p.add_run()
            r.text = text
            style_run(r, o.get("size", size), o.get("color", color), o.get("bold", bold), o.get("italic", False),
                      o.get("font", FONT))


def text(slide, x, y, w, h, paras, size=16, color=INK, bold=False, align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP, name=None,
         space_after=6, line_spacing=1.05):
    tb = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    if name:
        tb.name = name
    tf = tb.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
    fill_frame(tf, paras, size, color, bold, align, space_after, line_spacing)
    return tb


def box(slide, x, y, w, h, fill=INFO_BG, line=BLUE, shape=MSO_SHAPE.ROUNDED_RECTANGLE, name=None, radius=0.08, line_w=1.75,
        dash=False, paras=None, size=14, color=INK, bold=False, align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.MIDDLE, space_after=3,
        pad=0.12):
    s = slide.shapes.add_shape(shape, Inches(x), Inches(y), Inches(w), Inches(h))
    if name:
        s.name = name
    if fill is None:
        s.fill.background()
    else:
        s.fill.solid()
        s.fill.fore_color.rgb = rgb(fill)
    if line is None:
        s.line.fill.background()
    else:
        s.line.color.rgb = rgb(line)
        s.line.width = Pt(line_w)
        if dash:
            ln = s.line._get_or_add_ln()
            d = etree.SubElement(ln, qn("a:prstDash"))
            d.set("val", "dash")
    s.shadow.inherit = False
    if shape == MSO_SHAPE.ROUNDED_RECTANGLE:
        s.adjustments[0] = radius
    tf = s.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    tf.margin_left = tf.margin_right = Inches(pad)
    tf.margin_top = tf.margin_bottom = Inches(0.06)
    if paras:
        fill_frame(tf, paras, size, color, bold, align, space_after)
    return s


def badge(slide, x, y, n, d=0.36, fill=AMBER, color=INK, size=14, name=None):
    b = box(slide, x, y, d, d, fill=fill, line=None, shape=MSO_SHAPE.OVAL, name=name or "Idea %s" % n, paras=[str(n)], size=size,
            color=color, bold=True, align=PP_ALIGN.CENTER, pad=0)
    b.text_frame.margin_top = b.text_frame.margin_bottom = 0
    return b


def arrow(slide, x1, y1, x2, y2, color=INK2, width=2.25, dash=False, name=None):
    c = slide.shapes.add_connector(MSO_CONNECTOR.STRAIGHT, Inches(x1), Inches(y1), Inches(x2), Inches(y2))
    if name:
        c.name = name
    c.line.color.rgb = rgb(color)
    c.line.width = Pt(width)
    ln = c.line._get_or_add_ln()
    if dash:
        d = etree.SubElement(ln, qn("a:prstDash"))
        d.set("val", "dash")
    etree.SubElement(ln, qn("a:tailEnd")).set("type", "triangle")
    return c


def title(slide, t, sub=None, color=BLUE):
    text(slide, 0.6, 0.38, 12.1, 0.75, [t], size=36, color=color, bold=True, name="Title")
    if sub:
        text(slide, 0.6, 1.12, 12.1, 0.6, [sub], size=18, color=INK2, name="Subtitle")


def footer(slide, deck, n, appendix=False):
    label = "PRAMAAN  ·  MOSIP Decode 2026  ·  PS04" + ("  ·  Appendix" if appendix else "")
    text(slide, 0.6, 7.08, 9, 0.28, [label], size=12, color=INK3, name="Footer")
    text(slide, 11.7, 7.08, 1.0, 0.28, ["%d / %d" % (n, deck.total)], size=12, color=INK3, align=PP_ALIGN.RIGHT,
         name="Slide number")


def notes(slide, t):
    slide.notes_slide.notes_text_frame.text = t


def picture(slide, path, x, y, w, alt, name):
    pic = slide.shapes.add_picture(str(path), Inches(x), Inches(y), width=Inches(w))
    pic.name = name
    pic._element.nvPicPr.cNvPr.set("descr", alt)
    pic.line.color.rgb = rgb(LINE)
    pic.line.width = Pt(1)
    return pic


# ============================================================================================================= slide 1
def slide_title(deck):
    s = deck.slide()
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = rgb(BLUE)
    text(s, 0.7, 1.2, 7.6, 1.2,
         [[("PRAMAAN ", {"size": 64, "bold": True, "color": WHITE}),
           ("प्रमाण", {"size": 64, "color": WHITE, "font": DEVANAGARI})]],
         name="Title", space_after=0)
    text(s, 0.7, 2.5, 7.6, 0.7, ["Proof, not a verdict."], size=36, color=WHITE, bold=True, name="Tagline")
    text(s, 0.7, 3.35, 7.2, 1.5,
         ["Face liveness and presentation-attack detection for the MOSIP Registration Client, desktop and Android, that "
          "leaves evidence a server can re-check."], size=22, color="DCE8F5", name="Summary")
    text(s, 0.7, 5.05, 7.4, 0.8,
         ["MOSIP Decode 2026  ·  Problem Statement 04", "Team: Bhavya Garg  ·  github.com/GargBhavya-tech/pramaan"],
         size=17, color="DCE8F5", name="Meta", space_after=3)
    box(s, 0.7, 6.1, 6.3, 0.58, fill=WARN_BG, line=WARN, radius=0.25, name="Honest status chip",
        paras=["A working engine, not yet measured on real attacks"], size=17, color="4A2F00", bold=True,
        align=PP_ALIGN.CENTER)
    # the hero: two paths that never met, and the thing that joins them
    box(s, 8.7, 1.2, 4.0, 1.05, fill=WHITE, line=None, name="Hero: preview",
        paras=[[("unsigned preview", {"bold": True, "color": ERR})], [("analysed for liveness", {"size": 15})]],
        size=19, color=INK)
    box(s, 8.7, 2.75, 4.0, 1.05, fill=WHITE, line=None, name="Hero: capture",
        paras=[[("signed capture", {"bold": True, "color": OK})], [("stored in the packet", {"size": 15})]], size=19,
        color=INK)
    arrow(s, 10.7, 2.3, 10.7, 2.7, color=WHITE, width=3)
    arrow(s, 10.7, 3.85, 10.7, 4.35, color=WHITE, width=3)
    box(s, 8.7, 4.4, 4.0, 1.35, fill=OK_BG, line=OK, line_w=3, name="Hero: binder",
        paras=[[("PRAMAAN links them", {"bold": True, "color": OK, "size": 20})],
               [("four locks, then signed evidence", {"size": 16})]], size=16, color=INK, align=PP_ALIGN.CENTER)
    notes(s, "PRAMAAN, which means proof. Face liveness and presentation attack detection for the MOSIP Registration Client, for "
             "MOSIP Decode 2026, problem statement four. The idea in one picture: the preview that gets analysed and the "
             "capture that gets stored are two separate paths that nothing links. PRAMAAN links them and keeps the proof. One "
             "honest note first: this is a working engine that has not yet been measured on real attacks.")


# ============================================================================================================= slide 2
def slide_gap(deck, n=2):
    s = deck.slide()
    title(s, "The gap nobody names", "The preview is unsigned. The capture is signed. Nothing links them.")
    box(s, 0.6, 2.0, 2.2, 1.55, name="Camera and device",
        paras=[[("Camera /", {"bold": True})], [("SBI device", {"bold": True})]], size=19, color=INFO, align=PP_ALIGN.CENTER)
    arrow(s, 2.85, 2.45, 6.2, 2.45, color=ERR, dash=True, width=3, name="Unsigned preview arrow")
    text(s, 3.1, 2.0, 3.0, 0.35, ["unsigned preview"], size=15, color=ERR, bold=True, align=PP_ALIGN.CENTER)
    box(s, 6.3, 2.0, 6.4, 0.85, fill=ERR_BG, line=ERR, name="Liveness passes on face A",
        paras=["Liveness check passes on face A"], size=20, color=ERR, bold=True, align=PP_ALIGN.CENTER)
    arrow(s, 2.85, 3.15, 6.2, 3.15, color=OK, width=3, name="Signed capture arrow")
    text(s, 3.1, 2.72, 3.0, 0.35, ["signed capture"], size=15, color=OK, bold=True, align=PP_ALIGN.CENTER)
    box(s, 6.3, 2.95, 6.4, 0.85, fill=ERR_BG, line=ERR, name="Packet stores face B",
        paras=["Registration packet stores face B"], size=20, color=ERR, bold=True, align=PP_ALIGN.CENTER)
    # the attacker
    box(s, 3.45, 3.62, 2.7, 0.62, fill=ERR, line=None, name="The attacker",
        paras=["Attacker controls the preview"], size=14, color=WHITE, bold=True, align=PP_ALIGN.CENTER)
    arrow(s, 5.95, 3.6, 5.95, 2.5, color=ERR, width=2, name="Attacker arrow")
    threats = [
        ("Capture swap", "Whoever controls the preview can pass liveness with one face and enrol another."),
        ("Android never sees it", "The client gets only the signed capture; the device app shows the stream."),
        ("Injection and insiders", "A virtual camera or deepfake is outside ISO/IEC 30107. An operator can log in with a "
                                   "supervisor's photo."),
        ("No audit trail", "A client-side pass or fail cannot be re-checked by the server later, or re-scored with a "
                           "better model."),
    ]
    w, gap = 2.95, 0.1
    for i, (h, b) in enumerate(threats):
        c = box(s, 0.6 + i * (w + gap), 4.65, w, 2.1, fill=GREY_BG, line=LINE, name="Threat: " + h,
                paras=[[(h, {"bold": True, "size": 18, "color": ERR})], [(b, {"size": 15})]], size=15, color=INK,
                anchor=MSO_ANCHOR.TOP, space_after=6)
    footer(s, deck, n)
    notes(s, "The preview is unsigned. The capture that is stored is signed. Nothing links the two. So whoever controls the "
             "preview can pass liveness with one face while another face is enrolled. On Android the client never even sees the "
             "preview. A virtual camera or a deepfake is outside the standard that covers presentation attacks. A corrupt "
             "operator can log in with a supervisor's photo. And a pass or fail made on the client cannot be re-checked by the "
             "server later.")


# ============================================================================================================= slide 3
def slide_different(deck, n=3):
    s = deck.slide()
    title(s, "What is different: from a verdict to proof", "Six ideas. Each one replaces a shortcut the naive approach takes.")
    text(s, 0.6, 1.72, 4.6, 0.3, ["THE NAIVE APPROACH"], size=13, color=INK3, bold=True)
    text(s, 5.75, 1.72, 4.6, 0.3, ["PRAMAAN"], size=13, color=BLUE, bold=True)
    text(s, 11.35, 1.72, 1.4, 0.3, ["SEE SLIDE"], size=13, color=INK3, bold=True, align=PP_ALIGN.RIGHT)
    rows = [
        ("Liveness is judged on the preview; the capture is stored separately.",
         "Preview and capture are bound: four locks prove the signed capture is the face that was watched.", "5"),
        ("The client says pass or fail, and the server has to trust it.",
         "Signed evidence a server can re-check, or re-score with a better model years later.", "6"),
        ("One model, or a blink check.",
         "Six cues that fail differently, fused. A cue that cannot run abstains; it never fails anyone.", "6"),
        ("A fixed challenge (blink, smile) that a recording or deepfake can follow.",
         "A random challenge: a 20.7-bit flash code and a random onset, different every session.", "6"),
        ("Camera only: a virtual camera or injected video is invisible.",
         "Device identity, the OS's camera registrations and a stream fingerprint.", "4"),
        ("Elderly, disabled and darker-skinned residents fail blink and pulse checks.",
         "Those cues only ever vote for life; Hindi, icons and speech; a dual-control exception path.", "A1"),
    ]
    y, h, gap = 2.05, 0.7, 0.08
    for i, (naive, ours, ref) in enumerate(rows):
        yy = y + i * (h + gap)
        box(s, 0.6, yy, 4.75, h, fill=GREY_BG, line=LINE, name="Naive %d" % (i + 1), paras=[naive], size=14, color=INK2)
        arrow(s, 5.38, yy + h / 2, 5.72, yy + h / 2, color=INK3, width=2.5)
        box(s, 5.75, yy, 5.45, h, fill=INFO_BG, line=BLUE, name="PRAMAAN %d" % (i + 1), paras=[ours], size=14, color=INK,
            pad=0.55)
        badge(s, 5.88, yy + (h - 0.36) / 2, i + 1)
        text(s, 11.4, yy + h / 2 - 0.15, 1.3, 0.3, [ref], size=15, color=BLUE, bold=True, align=PP_ALIGN.RIGHT)
    footer(s, deck, n)
    notes(s, "This is the main point. Six ideas, each replacing a shortcut. One: judge the preview but store a separate capture, "
             "versus binding the two with four locks. Two: a pass or fail the server must trust, versus signed evidence it can "
             "re-check or re-score. Three: one model, versus six cues that fail differently. Four: a challenge a recording can "
             "follow, versus a random one with a twenty point seven bit flash code. Five: camera only, versus also checking "
             "the device identity, the operating system's camera list and the stream fingerprint. Six: checks that fail "
             "elderly, disabled and darker skinned residents for innocent reasons, versus cues that only ever vote for life. "
             "Each of these is built and tested; none is claimed beyond what the claim sheet allows.")


# ============================================================================================================= slide 4
def slide_architecture(deck, n=4):
    s = deck.slide()
    title(s, "Architecture: two paths leave the device")
    # device zone
    box(s, 0.5, 1.35, 2.6, 5.15, fill=None, line=INK3, dash=True, line_w=1.5, name="Device trust boundary")
    text(s, 0.65, 1.42, 2.4, 0.5, [[("Device trust boundary", {"bold": True})], [("L0 key in software; L1 in hardware", {"size": 12})]],
         size=13, color=INK3, space_after=0)
    box(s, 0.7, 2.15, 2.2, 1.1, name="Camera", paras=[[("Camera", {"bold": True})], [("the sensor", {"size": 13})]], size=16,
        color=INFO, align=PP_ALIGN.CENTER)
    arrow(s, 1.8, 3.27, 1.8, 3.78, color=INK2, width=2)
    box(s, 0.7, 3.8, 2.2, 2.4, name="SBI device service",
        paras=[[("SBI device service", {"bold": True})], [("127.0.0.1, ports 4501-4600", {"size": 12})],
               [("signs every capture, the session nonce inside", {"size": 12})],
               [("optional: Liveness Profile", {"size": 12})]], size=15, color=INFO, anchor=MSO_ANCHOR.TOP,
        space_after=5)
    # the two paths
    arrow(s, 3.12, 2.7, 4.22, 2.7, color=ERR, dash=True, width=3, name="Unsigned path")
    text(s, 3.12, 2.12, 1.1, 0.5, ["unsigned", "preview"], size=12, color=ERR, bold=True, align=PP_ALIGN.CENTER, space_after=0)
    arrow(s, 3.12, 4.55, 4.22, 4.55, color=OK, width=3, name="Signed path")
    text(s, 3.12, 3.97, 1.1, 0.5, ["signed", "capture"], size=12, color=OK, bold=True, align=PP_ALIGN.CENTER, space_after=0)
    # station zone
    box(s, 4.25, 1.35, 6.05, 5.15, fill=None, line=INK3, dash=True, line_w=1.5, name="Station")
    text(s, 4.4, 1.42, 5.8, 0.3, ["Station: Registration Client (desktop) or SBI app (Android)"], size=13, color=INK3, bold=True)
    box(s, 4.45, 2.15, 2.4, 1.25, name="SbiFrameSource",
        paras=[[("SbiFrameSource", {"bold": True})], [("frames and the signed capture, one object", {"size": 12})]], size=15,
        color=INFO, anchor=MSO_ANCHOR.TOP)
    badge(s, 6.5, 2.0, 5)
    box(s, 7.15, 2.0, 3.0, 2.55, name="LivenessEngine",
        paras=[[("LivenessEngine", {"bold": True})],
               [("source integrity, injection veto", {"size": 12})], [("one usable face, quality gate", {"size": 12})],
               [("passive model: asks nothing", {"size": 12})], [("attack cues, each may abstain", {"size": 12})],
               [("fusion: accept / challenge / reject", {"size": 12})], [("random challenge", {"size": 12})],
               [("fails closed; no score to the screen", {"size": 12, "bold": True})]], size=15, color=INFO,
        anchor=MSO_ANCHOR.TOP, space_after=2)
    badge(s, 8.7, 1.85, 3)
    badge(s, 9.1, 1.85, 4)
    badge(s, 9.5, 1.85, 6)
    arrow(s, 6.87, 2.6, 7.13, 2.6, color=INK2, width=2)
    box(s, 4.45, 3.8, 2.4, 1.3, fill=OK_BG, line=OK, name="CaptureBinder",
        paras=[[("CaptureBinder", {"bold": True})], [("four locks: nonce, chain, same face, time", {"size": 12})]], size=15,
        color=OK, anchor=MSO_ANCHOR.TOP)
    badge(s, 6.5, 3.65, 1)
    arrow(s, 5.65, 3.42, 5.65, 3.78, color=INK2, width=2)
    arrow(s, 7.13, 4.3, 6.87, 4.3, color=INK2, width=2)
    box(s, 7.15, 4.95, 3.0, 1.15, name="EvidencePackager",
        paras=[[("EvidencePackager", {"bold": True})], [("signed JWS: decision, cues, model hashes, binding level", {"size": 12})]],
        size=15, color=INFO, anchor=MSO_ANCHOR.TOP)
    badge(s, 9.8, 4.8, 2)
    arrow(s, 6.87, 4.75, 7.13, 5.3, color=INK2, width=2)
    box(s, 4.45, 5.4, 2.4, 0.85, fill=WARN_BG, line=WARN, name="Station screens",
        paras=[[("Station screens", {"bold": True})], [("one sentence and an icon", {"size": 12})]], size=14, color=WARN,
        anchor=MSO_ANCHOR.TOP)
    badge(s, 6.5, 5.25, 6)
    # right: packet and server
    box(s, 10.6, 4.55, 2.2, 1.55, name="Registration packet",
        paras=[[("Registration packet", {"bold": True})], [("face BIR: bdb untouched", {"size": 12})],
               [("others = signed evidence", {"size": 12})]], size=15, color=INFO, anchor=MSO_ANCHOR.TOP)
    arrow(s, 10.17, 5.5, 10.57, 5.5, color=INK2, width=2)
    arrow(s, 11.7, 4.52, 11.7, 4.15, color=INK2, width=2)
    text(s, 11.8, 4.2, 0.9, 0.3, ["sync"], size=12, color=INK3)
    box(s, 10.6, 1.9, 2.2, 2.2, fill=OK_BG, line=OK, name="Server",
        paras=[[("Server, any time later", {"bold": True, "size": 14})], [("verifyEvidence: one command", {"size": 12})],
               [("rescanEvidence: a newer model", {"size": 12})], [("transparency log", {"size": 12})]], size=15, color=OK,
        anchor=MSO_ANCHOR.TOP, space_after=4)
    badge(s, 12.45, 1.75, 2)
    # legend
    legend = ["Binding", "Signed evidence", "Differing cues", "Random challenge", "Injection defence", "Inclusion"]
    lx = 0.5
    for i, label in enumerate(legend):
        badge(s, lx, 6.7, i + 1, d=0.3, size=12, name="Legend %d" % (i + 1))
        wlab = 0.085 * len(label) + 0.1
        text(s, lx + 0.38, 6.7, wlab, 0.3, [label], size=12, color=INK, anchor=MSO_ANCHOR.MIDDLE, space_after=0)
        lx += 0.38 + wlab + 0.12
    footer(s, deck, n)
    notes(s, "Two paths leave the device. The preview, dashed and red, is unsigned and goes to the engine for analysis. The "
             "capture, solid and green, is signed by the device with our session nonce inside, and goes to the binder. The "
             "binder is what links them: four locks decide whether the signed capture is the face that was watched. Only then "
             "is evidence packaged, signed, stored in the face record's others map and sent to the server, which can re-check "
             "it or re-score it later. The numbered badges are the six ideas from the previous slide, placed on the part of "
             "the system where each one lives. On Android the engine runs inside the device app, because the client never "
             "sees the preview there.")


# ============================================================================================================= slide 5
def slide_binding(deck, n=5):
    s = deck.slide()
    title(s, "How idea 1 works: four locks stop the capture swap")
    steps = [("Start", "The client makes a random 32-character nonce for this session."),
             ("Preview", "Every frame it analyses is hashed into a chain."),
             ("Challenge", "A random prompt, at a random time."),
             ("Capture", "The nonce goes to the device as the transaction ID; the device signs it inside the capture."),
             ("Decide", "Four locks must all hold before anything is accepted.")]
    w, gap = 2.3, 0.15
    for i, (h, b) in enumerate(steps):
        x = 0.6 + i * (w + gap)
        box(s, x, 1.35, w, 1.55, fill=OK_BG if i == 4 else INFO_BG, line=OK if i == 4 else BLUE, name="Step " + h,
            paras=[[("%d  %s" % (i + 1, h), {"bold": True, "size": 16, "color": OK if i == 4 else BLUE})],
                   [(b, {"size": 13})]], size=13, color=INK, anchor=MSO_ANCHOR.TOP, space_after=4)
        if i < 4:
            arrow(s, x + w + 0.01, 2.1, x + w + gap - 0.01, 2.1, color=INK2, width=2)
    locks = [("1  Nonce", "the signed capture echoes this session's nonce", "an old or foreign capture replayed", False),
             ("2  Chain", "a hash chain over the analysed frames links to the device's capture chain",
              "frames dropped, inserted or reordered", False),
             ("3  Same face", "the face in the signed capture matches the face tracked in the preview",
              "THE CAPTURE SWAP", True),
             ("4  Time", "the capture is recent compared with the last verified frame", "a capture taken long after the analysis",
              False)]
    w, gap = 2.95, 0.1
    text(s, 0.6, 3.08, 6, 0.3, ["THE FOUR LOCKS"], size=13, color=INK3, bold=True)
    for i, (h, checks, stops, key) in enumerate(locks):
        x = 0.6 + i * (w + gap)
        box(s, x, 3.4, w, 2.0, fill=ERR_BG if key else GREY_BG, line=ERR if key else LINE, line_w=2.5 if key else 1.5,
            name="Lock " + h,
            paras=[[(h, {"bold": True, "size": 17, "color": ERR if key else BLUE})], [("Checks: ", {"bold": True, "size": 13}),
                                                                                   (checks, {"size": 13})],
                   [("Stops: ", {"bold": True, "size": 13, "color": ERR}), (stops, {"size": 13, "bold": key})]],
            size=13, color=INK, anchor=MSO_ANCHOR.TOP, space_after=5)
    box(s, 0.6, 5.6, 6.25, 1.3, fill=ERR_BG, line=ERR, name="The attack",
        paras=[[("The attack, and what happens", {"bold": True, "size": 15, "color": ERR})],
               [("Pass liveness with face A, then return a signed capture of face B. Lock 3 fails and the session ends with "
                 "REJECT_BINDING. Each swap is a test: BindingAttacksTest, 8 cases, all passing.", {"size": 13})]],
        size=13, color=INK, anchor=MSO_ANCHOR.TOP, space_after=4)
    box(s, 7.0, 5.6, 5.7, 1.3, fill="F6F8FA", line=LINE, name="Real response",
        paras=[[("Real response from the reference device (signed data)", {"bold": True, "size": 12, "font": FONT, "color": INK2})],
               [('"transactionId": "0123456789abcdef0123456789abcdef"', {"size": 11, "font": MONO})],
               [('"streamTagHead": "158b9f4cdc6ca35c7d22a3bd7b0535..."', {"size": 11, "font": MONO})],
               [("Locks 1 to 4 need no device change. The optional Liveness Profile adds a signed stream and a burst.",
                 {"size": 12, "font": FONT})]], size=11, color=INK, anchor=MSO_ANCHOR.TOP, space_after=3)
    footer(s, deck, n)
    notes(s, "Here is how the first idea works. At the start the client makes a random nonce. Every frame it analyses goes into a "
             "hash chain. When it asks the device for the capture, it sends the nonce as the transaction ID, and the device "
             "signs it inside the capture. Then four locks decide. Lock one, the nonce comes back inside the signed data, which "
             "stops an old or foreign capture. Lock two, the hash chain, stops dropped or inserted frames. Lock three, the face "
             "in the signed capture must match the face in the preview, and that is the capture swap. Lock four, the capture "
             "must be recent. Each of these is a test. The first four locks need no change to the device. The real response on "
             "the slide is from our reference device.")


# ============================================================================================================= slide 6
def slide_cues(deck, n=6):
    s = deck.slide()
    title(s, "Ideas 2 to 4: cues, challenge and evidence")
    # left: cues that fail differently
    badge(s, 0.6, 1.4, 3)
    text(s, 1.05, 1.4, 4.7, 0.36, ["Cues that fail differently"], size=18, color=BLUE, bold=True, anchor=MSO_ANCHOR.MIDDLE)
    cues = [("Passive model", "print, screen", "what it was not trained on"),
            ("Moiré", "screen recapture", "some display pitches (we found them)"),
            ("Flash reflection", "replay, injection", "a projected mask; needs a light"),
            ("Scene + light", "the wrong room", "a replay of the right room"),
            ("Stereo depth", "flat things", "needs a second camera"),
            ("Stream fingerprint", "a re-encoding virtual camera", "one that copies the encoder")]
    text(s, 0.6, 1.9, 1.5, 0.25, ["CUE"], size=11, color=INK3, bold=True)
    text(s, 2.2, 1.9, 1.6, 0.25, ["CATCHES"], size=11, color=OK, bold=True)
    text(s, 3.95, 1.9, 1.8, 0.25, ["BLIND TO"], size=11, color=ERR, bold=True)
    ry, rh = 2.17, 0.62
    for i, (cue, catches, blind) in enumerate(cues):
        yy = ry + i * (rh + 0.06)
        box(s, 0.6, yy, 1.55, rh, fill=INFO_BG, line=None, name="Cue " + cue, paras=[cue], size=12, color=INFO, bold=True, pad=0.08)
        box(s, 2.2, yy, 1.7, rh, fill=OK_BG, line=None, name="Catches " + cue, paras=[catches], size=12, color=INK, pad=0.08)
        box(s, 3.95, yy, 1.95, rh, fill=ERR_BG, line=None, name="Blind " + cue, paras=[blind], size=12, color=INK, pad=0.08)
    text(s, 0.6, 6.25, 5.3, 0.7,
         [[("Every cue has a hole; the holes should not overlap. ", {"bold": True}),
           ("A cue that cannot run abstains. It is never a failure.", {})]], size=13, color=INK)
    # middle: random challenge
    badge(s, 6.2, 1.4, 4)
    text(s, 6.65, 1.4, 3.1, 0.36, ["A random challenge"], size=18, color=BLUE, bold=True, anchor=MSO_ANCHOR.MIDDLE)
    text(s, 6.2, 1.95, 3.6, 0.5, ["The screen flashes a random colour code; a live face reflects it. Example (random every session):"],
         size=12, color=INK2)
    seq = ["GREEN", "BLUE", "WHITE", "YELLOW", "CYAN", "BLUE", "MAGENTA", "GREEN"]
    sq, sg = 0.4, 0.02
    for i, c in enumerate(seq):
        box(s, 6.2 + i * (sq + sg), 2.6, sq, sq, fill=FLASH[c], line=INK3, line_w=0.75, shape=MSO_SHAPE.RECTANGLE,
            name="Flash step %d %s" % (i + 1, c))
    text(s, 6.2, 3.08, 3.6, 0.25, ["step 1 ... step 8, 400 to 450 ms each"], size=11, color=INK3)
    box(s, 6.2, 3.5, 3.6, 1.3, fill=INFO_BG, line=BLUE, name="Flash maths",
        paras=[[("6 colours × 8 steps", {"bold": True, "size": 15})], [("= 1,679,616 sequences", {"size": 15})],
               [("= 20.7 bits", {"bold": True, "size": 20, "color": BLUE})]], size=15, color=INK, align=PP_ALIGN.CENTER,
        space_after=0)
    text(s, 6.2, 4.95, 3.6, 1.9,
         [[("Random onset", {"bold": True}), (" for the prompt, and a random action.", {})],
          [("At most 3 colour changes a second", {"bold": True}), (", no saturated red: checked on 900 sequences.", {})],
          [("Honest limit: ", {"bold": True, "color": WARN}), ("built and tested on synthetic data; it needs a light facing the "
                                                              "resident.", {})]], size=12, color=INK, space_after=5)
    # right: evidence
    badge(s, 10.15, 1.4, 2)
    text(s, 10.6, 1.4, 2.2, 0.36, ["Lasting evidence"], size=18, color=BLUE, bold=True, anchor=MSO_ANCHOR.MIDDLE)
    ev = [("Signed JWS in the face BIR", "bdb untouched; evidence in others"),
          ("Server re-checks", "verifyEvidence, one command"),
          ("Years later", "re-score with a newer model"),
          ("Transparency log", "omission is detectable (Merkle)")]
    ey, eh = 2.0, 0.95
    for i, (h, b) in enumerate(ev):
        yy = ey + i * (eh + 0.3)
        box(s, 10.15, yy, 2.55, eh, fill=OK_BG if i == 3 else INFO_BG, line=OK if i == 3 else BLUE, name="Evidence " + h,
            paras=[[(h, {"bold": True, "size": 14})], [(b, {"size": 12})]], size=12, color=INK, anchor=MSO_ANCHOR.TOP,
            space_after=2)
        if i < 3:
            arrow(s, 11.42, yy + eh + 0.01, 11.42, yy + eh + 0.29, color=INK2, width=2)
    footer(s, deck, n)
    notes(s, "Three more ideas. Idea three: cues that fail differently. The passive model, moire, flash reflection, scene and "
             "light consistency, stereo depth and a stream fingerprint each catch something and are blind to something else. "
             "The design goal is that the holes do not overlap, and a cue that cannot run abstains instead of failing "
             "anyone. Our own red team found that the moire cue is blind at some display pitches, and we show that openly. "
             "Idea four: a challenge nobody can pre-record. The screen flashes a random sequence of six colours over eight "
             "steps, which is one million six hundred seventy nine thousand six hundred sixteen sequences, or twenty point "
             "seven bits, and a live face reflects it. It is limited to three colour changes a second for photosensitivity. "
             "It is built and tested on synthetic data and needs a light facing the resident. Idea two: evidence, not a "
             "verdict. A signed record in the face record's others map that the server can re-check with one command, "
             "re-score years later with a newer model, and that a transparency log makes impossible to quietly omit.")


# ============================================================================================================= slide 7
def slide_status(deck, n=7):
    s = deck.slide()
    title(s, "Honest status, impact and next steps",
          "No real attack corpus exists, so no APCER or BPCER is claimed. Zero failures in 60 attacks would still allow a true "
          "rate near 6%.")
    box(s, 0.6, 1.95, 4.0, 3.75, fill=OK_BG, line=OK, name="Verified", anchor=MSO_ANCHOR.TOP, size=14, color=INK, space_after=5,
        paras=[[("Verified (one command each)", {"bold": True, "size": 17, "color": OK})],
               [("673 automated tests pass", {"bold": True}), (" (20 skip on a fresh clone: no models)", {"size": 12})],
               "Each capture-swap attack is a test",
               "Flash code: 20.7 bits; ≤ 3 changes a second",
               "Insider signal: 6 in 40 at 2% gives z ≈ 5.87",
               "Transparency log matches the CT test vectors"])
    box(s, 4.75, 1.95, 4.0, 3.75, fill=WARN_BG, line=WARN, name="Not claimed", anchor=MSO_ANCHOR.TOP, size=14, color=INK,
        space_after=5,
        paras=[[("Not done, not claimed", {"bold": True, "size": 17, "color": WARN})],
               "No error rate on real attacks; thresholds provisional",
               "Not run on a device, a phone or a certified SBI device",
               "A scaffold, not the Registration Client; no user has tried the screens",
               [("We attacked ourselves: ", {"bold": True}), ("4 of 85 synthetic pitches accepted without a flash cue, 0 with "
                                                               "one. Logged, fix proposed, not enabled.", {})]])
    box(s, 8.9, 1.95, 3.8, 3.75, name="Impact", anchor=MSO_ANCHOR.TOP, size=14, color=INK, space_after=5,
        paras=[[("Impact and next", {"bold": True, "size": 17, "color": BLUE})],
               [("For MOSIP: ", {"bold": True}), ("closes the capture-swap gap on unmodified SBI; leaves proof a server can "
                                                   "re-check.", {})],
               [("Next: ", {"bold": True}), ("one real experiment (does the flash cue separate a live face from a replay?), a "
                                             "consented corpus, a real device and phone, real users.", {})],
               [("Ask: ", {"bold": True}), ("how should an SBI extension be proposed?", {})]])
    box(s, 0.6, 5.9, 12.1, 0.95, fill=GREY_BG, line=LINE, name="Stack and audience", anchor=MSO_ANCHOR.MIDDLE, size=13, color=INK,
        space_after=3,
        paras=[[("Stack: ", {"bold": True}), ("Java (Java 11 bytecode) · ONNX Runtime · JavaFX 11 · MOSIP SBI 1.x · "
                                              "JUnit 5 · Android library", {})],
               [("For: ", {"bold": True}), ("registration centres, operators and supervisors, residents, and server operators.  ", {}),
                ("Code: ", {"bold": True}), ("github.com/GargBhavya-tech/pramaan (MPL-2.0)", {})]])
    footer(s, deck, n)
    notes(s, "What we verify and what we do not claim. Six hundred seventy three automated tests pass; on a fresh clone twenty "
             "skip because the face models are not in the repository. We have not measured an error rate on real attacks because "
             "no real corpus exists. It has not run on a real device or a phone, it is a scaffold, and no user has tried the "
             "screens yet. Our own red team found that, on synthetic replays with no flash light, the fusion accepted four of "
             "eighty five display pitches, and none with the flash cue; we logged it and did not turn on the proposed fix. "
             "Next: one real experiment, a consented corpus, a real device, real users. And one question for MOSIP: how should "
             "an SBI extension be proposed? Thank you.")


# ============================================================================================================= appendix
def slide_a1(deck, n):
    s = deck.slide()
    title(s, "Appendix: the station, as built",
          "Rendered from the real code, offscreen. One plain sentence, an icon, no score. No live camera in these images.")
    shots = [("capture-screen.png", "Resident capture mid-challenge", "A head-turn prompt with an arrow, an icon and one sentence."),
             ("hindi-accessibility.png", "Hindi, one click",
              "Larger text and high contrast are also one click each. Machine-drafted Hindi."),
             ("fails-closed.png", "Fails closed", "If anything does not verify, nothing can be registered and the screen says "
                                                  "what to check.")]
    alts = ["The resident capture screen mid-challenge with a step rail, a face oval, an arrow for a head turn and one plain "
            "instruction; the camera area shows a placeholder.", "The same screen in Hindi.",
            "The screen shown when the engine cannot start: an error banner telling the operator what to check."]
    w = 3.95
    for i, (f, h, c) in enumerate(shots):
        x = 0.6 + i * (w + 0.12)
        picture(s, MEDIA / f, x, 1.85, w, alts[i], "Screenshot: " + h)
        text(s, x, 4.65, w, 1.0, [[(h, {"bold": True, "size": 16, "color": BLUE})], [(c, {"size": 13})]], size=13, color=INK,
             space_after=3)
    box(s, 0.6, 5.75, 12.1, 1.15, fill=GREY_BG, line=LINE, name="Accessibility", anchor=MSO_ANCHOR.MIDDLE, size=13, color=INK,
        paras=[[("Built to be inclusive: ", {"bold": True}), ("every status is an icon and a word, never colour alone; 44 px targets and a "
                                                              "3 px focus ring; larger-text and high-contrast modes; Hindi; spoken prompts; "
                                                              "accessibility profiles; contrast checked at 4.5:1 or better on the palette.", {})],
               [("Not done: ", {"bold": True, "color": WARN}), ("no operator or resident has used these screens, and no screen "
                                                              "reader was tried.", {})]])
    footer(s, deck, n, appendix=True)
    notes(s, "These are the real screens rendered from the actual code, so there is no live camera in them. They are built to be "
             "inclusive, but they have not been tried with real people or a screen reader yet.")


def slide_a2(deck, n):
    s = deck.slide()
    title(s, "Appendix: we attacked ourselves",
          "An automated red team searched our own cues. It found two real weaknesses, which are logged, not hidden.")
    text(s, 0.6, 1.85, 7.4, 0.3, ["FUSED DECISION ON SYNTHETIC SCREEN REPLAYS (SCENE AND MOIRÉ FOOLED, NO FLASH LIGHT)"], size=12, color=INK3,
         bold=True)
    hdr = ["Display pitch", "Without the flash cue", "With the flash cue"]
    xs = [0.6, 2.6, 5.4]
    ws = [1.95, 2.75, 2.6]
    for x, w, h in zip(xs, ws, hdr):
        box(s, x, 2.2, w, 0.5, fill=BLUE, line=None, paras=[h], size=13, color=WHITE, bold=True, align=PP_ALIGN.CENTER)
    data = [("0.80", "ACCEPT  (p 0.986)", "CHALLENGE  (p 0.459)"), ("1.00", "ACCEPT  (p 0.989)", "CHALLENGE  (p 0.526)"),
            ("1.20", "ACCEPT  (p 0.994)", "CHALLENGE  (p 0.657)"), ("2.00", "ACCEPT  (p 0.949)", "REJECT  (p 0.185)")]
    for r, (pitch, a, b) in enumerate(data):
        yy = 2.78 + r * 0.58
        box(s, xs[0], yy, ws[0], 0.5, fill=GREY_BG, line=None, paras=[pitch], size=14, color=INK, align=PP_ALIGN.CENTER)
        box(s, xs[1], yy, ws[1], 0.5, fill=ERR_BG, line=None, paras=[a], size=13, color=ERR, bold=True, align=PP_ALIGN.CENTER)
        box(s, xs[2], yy, ws[2], 0.5, fill=OK_BG, line=None, paras=[b], size=13, color=OK, bold=True, align=PP_ALIGN.CENTER)
    box(s, 0.6, 5.2, 7.4, 0.75, fill=INFO_BG, line=BLUE, name="Totals",
        paras=[[("Of 85 pitches: ", {"bold": True}), ("4 accepted without the flash cue, 0 with it, 3 left to the challenge.", {})]],
        size=15, color=INK)
    text(s, 0.6, 6.1, 7.4, 0.7, ["Synthetic data, the passive scorer assumed fooled, the challenge not modelled. "
                                 "Reproduce: ./gradlew :harness:fusedRedTeam"], size=12, color=INK2)
    box(s, 8.3, 1.85, 4.4, 4.9, fill=WARN_BG, line=WARN, name="Findings", anchor=MSO_ANCHOR.TOP, size=14, color=INK, space_after=7,
        paras=[[("What we found, and did", {"bold": True, "size": 17, "color": WARN})],
               [("R-01: ", {"bold": True}), ("the moiré cue is blind at some display pitches. Disclosed.", {})],
               [("R-03: ", {"bold": True}), ("two attacker-controlled cues can sum to an accept when there is no flash light. "
                                             "Disclosed.", {})],
               [("R-02: ", {"bold": True}), ("a scene-cue false rejection after exposure changes. Fixed.", {})],
               [("Proposed fix for R-03: ", {"bold": True}), ("an accept needs a vote from a cue the display cannot fake. Not "
                                                              "enabled: it would raise false rejections at stations without a "
                                                              "light, which cannot be priced without a real corpus.", {})]])
    footer(s, deck, n, appendix=True)
    notes(s, "Our own automated red team searched the recapture parameters for what fools our cues. On synthetic screen replays, "
             "with the passive model assumed fooled and no flash light, four of eighty five display pitches were accepted; with "
             "the flash cue, none. We logged it as R-03, with R-01, the blind pitches of the moire cue, and R-02, which we fixed. "
             "We proposed a fix and deliberately did not turn it on, because it would raise false rejections that we cannot "
             "price without real data.")


def slide_a3(deck, n):
    s = deck.slide()
    title(s, "Appendix: stack, standards and licences")
    cols = [
        ("Stack", ["Java, compiled to Java 11 bytecode (JDK 24 build)", "Gradle (Kotlin DSL)", "ONNX Runtime 1.20, pinned on purpose",
                   "JavaFX 11, the Registration Client's version", "Jackson, JUnit 5", "Android library (AAR) for Mode D",
                   "MOSIP SBI 1.x; models: YuNet, MiniFASNet, MediaPipe, SFace"]),
        ("Standards followed as targets", ["ISO/IEC 30107-1 and -3: APCER and BPCER per attack type, with intervals; ACER not "
                                           "reported", "ISO/IEC 19794-5: the face image", "ISO/IEC 29794-5 and OFIQ: quality",
                                           "ISO/IEC 19795-10: demographic differentials", "CEN/TS 18099: injection attacks",
                                           "Not certified: no iBeta or FIDO test"]),
        ("Licences", ["Code: MPL-2.0, like MOSIP's repositories", "YuNet: MIT", "MiniFASNet, MediaPipe, SFace: Apache-2.0",
                      "SFace: development use only until its training-data terms are answered",
                      "JPEG 2000 reader: conditional; reuse MOSIP's copy in the client",
                      "Every dependency and model has a row in docs/licences.md"]),
    ]
    w = 3.95
    for i, (h, items) in enumerate(cols):
        x = 0.6 + i * (w + 0.12)
        box(s, x, 1.45, w, 5.35, name=h, anchor=MSO_ANCHOR.TOP, size=14, color=INK, space_after=7,
            paras=[[(h, {"bold": True, "size": 18, "color": BLUE})]] + items)
    footer(s, deck, n, appendix=True)
    notes(s, "The stack is Java, ONNX Runtime pinned to 1.20, JavaFX 11 as in the Registration Client, and the MOSIP SBI. We follow "
             "the ISO and CEN standards as design targets; we are not certified. The code is MPL two point zero like MOSIP; "
             "every dependency and model has a licence row, and SFace is development use only until its training data question "
             "is answered.")


def build(path, with_appendix):
    deck = Deck(10 if with_appendix else 7)
    slide_title(deck)
    slide_gap(deck)
    slide_different(deck)
    slide_architecture(deck)
    slide_binding(deck)
    slide_cues(deck)
    slide_status(deck)
    if with_appendix:
        slide_a1(deck, 8)
        slide_a2(deck, 9)
        slide_a3(deck, 10)
    p = deck.prs
    p.core_properties.title = "PRAMAAN: pitch (MOSIP Decode 2026, PS04)"
    p.core_properties.author = "Bhavya Garg"
    p.core_properties.subject = "Face liveness and presentation-attack detection for the MOSIP Registration Client"
    p.save(str(path))
    print("wrote", path, "(%d slides)" % len(p.slides))


if __name__ == "__main__":
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    build(OUT_DIR / "PRAMAAN-pitch-7slides.pptx", False)
    build(OUT_DIR / "PRAMAAN-pitch-full-10slides.pptx", True)
    sys.exit(0)

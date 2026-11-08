"""Generates the app icon (adaptive: background + foreground) and the TV banner as Android
VectorDrawables, plus SVG previews. The banner text is converted to outlines with fontTools, so the
app needs no font file; change TITLE or SUBTITLE and run again.

    python3 art/icons.py <preview-dir> app/src/main/res    # previews: rsvg-convert <dir>/banner.svg

Needs fontTools and the Noto Serif Display / Noto Sans fonts (paths below)."""
import math
import sys
from pathlib import Path

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

OUT = Path(sys.argv[1])
RES = Path(sys.argv[2]) if len(sys.argv) > 2 else None

GOLD = [(0.0, "#F4D07A"), (1.0, "#C8923A")]
BG_DARK = "#0D1015"
SERIF = "/usr/share/fonts/noto/NotoSerifDisplay-Medium.ttf"
SANS = "/usr/share/fonts/noto/NotoSans-Bold.ttf"
TITLE, SUBTITLE = "Courses TV", "LECTURES ON YOUR TV"


def num(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


# ---- The mark: a classical portico with a play button, in a 100-wide design box (y down) ----

def rect(x0, y0, x1, y1):
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


def portico():
    shapes = [[(0, 30), (50, 3), (100, 30)]]            # pediment
    shapes.append(rect(3, 33.5, 97, 40.5))               # entablature
    shapes.append([(40, 51.5), (40, 77.5), (62.5, 64.5)])  # play, between the column pairs
    for cx in (12.5, 29, 71, 87.5):
        w = 6.5
        shapes.append(rect(cx - w, 43.5, cx + w, 47.5))  # capital
        shapes.append(rect(cx - w + 2.5, 47.5, cx + w - 2.5, 81.5))  # shaft
    shapes.append(rect(3, 84.5, 97, 90))                 # stylobate
    shapes.append(rect(-3, 92.5, 103, 98))               # lower step
    return shapes


def place(shapes, scale, dx, dy):
    return [[(x * scale + dx, y * scale + dy) for x, y in s] for s in shapes]


def poly_d(points):
    first, *rest = points
    return f"M{num(first[0])},{num(first[1])}" + "".join(f"L{num(x)},{num(y)}" for x, y in rest) + "Z"


def mark_d(shapes):
    return "".join(poly_d(s) for s in shapes)


def bbox(shapes):
    xs = [x for s in shapes for x, _ in s]
    ys = [y for s in shapes for _, y in s]
    return min(xs), min(ys), max(xs), max(ys)


# ---- Text as outlines -------------------------------------------------------------------------

def text_glyphs(font_path, text, size, x, baseline, tracking=0.0):
    """One path per glyph (keeps each VectorDrawable path short); returns (paths, width)."""
    font = TTFont(font_path)
    glyphs, cmap, upm = font.getGlyphSet(), font.getBestCmap(), font["head"].unitsPerEm
    hmtx = font["hmtx"]
    s = size / upm
    cursor, paths = 0.0, []
    for i, ch in enumerate(text):
        name = cmap[ord(ch)]
        pen = SVGPathPen(glyphs, ntos=num)
        glyphs[name].draw(TransformPen(pen, (s, 0, 0, -s, x + cursor, baseline)))
        d = pen.getCommands()
        if d:
            paths.append(d)
        cursor += hmtx[name][0] * s + (tracking if i < len(text) - 1 else 0)
    return paths, cursor


def text_width(font_path, text, size, tracking=0.0):
    return text_glyphs(font_path, text, size, 0, 0, tracking)[1]


# ---- Output: VectorDrawable and SVG from the same primitives ---------------------------------
# A primitive is (pathData, fill); fill is "#hex" or ("linear"|"radial", geometry, stops).

def vd(width, height, prims, comment):
    out = ['<?xml version="1.0" encoding="utf-8"?>', f"<!-- {comment} -->",
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    xmlns:aapt="http://schemas.android.com/aapt"',
           f'    android:width="{width}dp"', f'    android:height="{height}dp"',
           f'    android:viewportWidth="{width}"', f'    android:viewportHeight="{height}">']
    for d, fill in prims:
        if isinstance(fill, str):
            out += ["    <path", f'        android:fillColor="{fill}"', f'        android:pathData="{d}" />']
            continue
        kind, geo, stops = fill
        if kind == "linear":
            x1, y1, x2, y2 = geo
            g = (f'android:type="linear" android:startX="{num(x1)}" android:startY="{num(y1)}" '
                 f'android:endX="{num(x2)}" android:endY="{num(y2)}"')
        else:
            cx, cy, r = geo
            g = f'android:type="radial" android:centerX="{num(cx)}" android:centerY="{num(cy)}" android:gradientRadius="{num(r)}"'
        out += ["    <path", f'        android:pathData="{d}">', '        <aapt:attr name="android:fillColor">',
                f"            <gradient {g}>"]
        out += [f'                <item android:offset="{o}" android:color="{c}" />' for o, c in stops]
        out += ["            </gradient>", "        </aapt:attr>", "    </path>"]
    out.append("</vector>")
    return "\n".join(out) + "\n"


def css(c):
    return "#" + c[3:] + c[1:3] if len(c) == 9 else c


def svg(width, height, prims, extra_defs="", clip=None, px=None):
    px = px or width
    defs, body = [], []
    for i, (d, fill) in enumerate(prims):
        if isinstance(fill, str):
            body.append(f'<path d="{d}" fill="{css(fill)}"/>')
            continue
        kind, geo, stops = fill
        st = "".join(f'<stop offset="{o}" stop-color="{css(c)[:7]}" stop-opacity="{int(css(c)[7:] or "FF", 16) / 255:.3f}"/>' for o, c in stops)
        if kind == "linear":
            x1, y1, x2, y2 = geo
            defs.append(f'<linearGradient id="g{i}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">{st}</linearGradient>')
        else:
            cx, cy, r = geo
            defs.append(f'<radialGradient id="g{i}" gradientUnits="userSpaceOnUse" cx="{cx}" cy="{cy}" r="{r}">{st}</radialGradient>')
        body.append(f'<path d="{d}" fill="url(#g{i})"/>')
    clip_attr = ""
    if clip:
        defs.append(f'<clipPath id="clip">{clip}</clipPath>')
        clip_attr = ' clip-path="url(#clip)"'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{px}" height="{round(px * height / width)}" '
            f'viewBox="0 0 {width} {height}"><defs>{"".join(defs)}{extra_defs}</defs><g{clip_attr}>{"".join(body)}</g></svg>')


# ---- Icon: 108 dp, the mark inside the 66 dp safe circle --------------------------------------

def icon():
    shapes = portico()
    x0, y0, x1, y1 = bbox(shapes)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    # Largest scale that keeps every corner within radius 30 of the centre (safe zone is 33).
    far = max(math.hypot(x - cx, y - cy) for s in shapes for x, y in s)
    scale = 31.5 / far
    placed = place(shapes, scale, 54 - cx * scale, 54.5 - cy * scale)
    top, bottom = bbox(placed)[1], bbox(placed)[3]
    fg = [(mark_d(placed), ("linear", (0, top, 0, bottom), GOLD))]
    bg = [("M0,0h108v108h-108z", ("radial", (54, 40, 76), [(0.0, "#232A37"), (1.0, "#0A0D12")]))]
    return bg, fg


# ---- Banner: 320 x 180 dp ------------------------------------------------------------------------

def banner():
    prims = [("M0,0h320v180h-320z", ("linear", (0, 0, 320, 180), [(0.0, "#151A23"), (1.0, "#0A0D12")])),
             ("M0,0h320v180h-320z", ("radial", (66, 90, 110), [(0.0, "#2A2618"), (1.0, "#002A2618")]))]
    shapes = portico()
    x0, y0, x1, y1 = bbox(shapes)
    scale = 78 / (y1 - y0)
    mx, my = 30, 90 - (y1 - y0) * scale / 2
    placed = place(shapes, scale, mx - x0 * scale, my - y0 * scale)
    bx0, by0, bx1, by1 = bbox(placed)
    prims.append((mark_d(placed), ("linear", (0, by0, 0, by1), GOLD)))

    left = bx1 + 22
    room = 320 - 24 - left
    title, sub = TITLE, SUBTITLE
    size = 40.0
    while text_width(SERIF, title, size) > room:
        size -= 0.5
    sub_size, sub_track = 7.6, 1.55
    while text_width(SANS, sub, sub_size, sub_track) > room:
        sub_size -= 0.1
    # Title cap height ~0.7 em; centre the two lines on the mark.
    block = size * 0.72 + 12 + sub_size * 0.72
    base1 = 90 - block / 2 + size * 0.72
    base2 = base1 + 12 + sub_size * 0.72
    t, _ = text_glyphs(SERIF, title, size, left, base1)
    s, _ = text_glyphs(SANS, sub, sub_size, left + 1, base2, sub_track)
    prims += [(d, "#F4F1EA") for d in t] + [(d, "#E2B254") for d in s]
    return prims, (size, sub_size)


bg, fg = icon()
bp, sizes = banner()
OUT.mkdir(parents=True, exist_ok=True)
(OUT / "icon_round.svg").write_text(svg(108, 108, bg + fg, clip='<circle cx="54" cy="54" r="54"/>', px=432))
(OUT / "icon_square.svg").write_text(svg(108, 108, bg + fg, clip='<rect x="0" y="0" width="108" height="108" rx="24"/>', px=432))
(OUT / "icon_small.svg").write_text(svg(108, 108, bg + fg, clip='<circle cx="54" cy="54" r="54"/>', px=72))
(OUT / "banner.svg").write_text(svg(320, 180, bp, px=960))
print("title size", sizes)
if RES:
    (RES / "drawable").mkdir(parents=True, exist_ok=True)
    (RES / "drawable" / "ic_app_background.xml").write_text(vd(108, 108, bg, "App icon background: a dark radial glow."))
    (RES / "drawable" / "ic_app_foreground.xml").write_text(
        vd(108, 108, fg, "App icon mark: a gold classical portico inside the adaptive icon's 66 dp safe zone."))
    banner_xml = vd(320, 180, bp, "TV launcher banner, 320×180 dp: the portico mark and the app's name (text as outlines).")
    banner_xml = banner_xml.replace('xmlns:aapt="http://schemas.android.com/aapt"',
                                    'xmlns:aapt="http://schemas.android.com/aapt"\n    xmlns:tools="http://schemas.android.com/tools"\n    tools:ignore="VectorRaster"')
    (RES / "drawable" / "tv_banner.xml").write_text(banner_xml)

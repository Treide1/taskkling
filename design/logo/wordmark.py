# Outline a string using the repo's bundled JetBrains Mono into a single SVG
# path (y-flipped to SVG coords, baseline at y=0 -> glyphs occupy negative y,
# so we translate by the ascender). Prints the path `d` plus geometry facts.
import sys
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.misc.transform import Transform

font_path, text = sys.argv[1], sys.argv[2]
f = TTFont(font_path)
cmap = f.getBestCmap()
glyphs = f.getGlyphSet()
upm = f["head"].unitsPerEm
asc = f["hhea"].ascent

x = 0.0
parts = []
for ch in text:
    gname = cmap[ord(ch)]
    g = glyphs[gname]
    pen = SVGPathPen(glyphs)
    # y-flip and shift so baseline sits at y = asc (top of ascender = 0)
    tpen = TransformPen(pen, Transform(1, 0, 0, -1, x, asc))
    g.draw(tpen)
    d = pen.getCommands()
    if d:
        parts.append(f'<path data-ch="{ch}" d="{d}"/>')
    x += g.width

print(f"<!-- upm={upm} ascent={asc} descent={f['hhea'].descent} advance_total={x} -->")
for p in parts:
    print(p)

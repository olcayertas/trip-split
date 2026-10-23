"""TripSplit app icon: a pizza cut in four equal slices, pulled a little apart.\n\nRun `python3 design/icon/gen_icon.py` (needs rsvg-convert). It writes the SVG sources next to this file and the\nPNGs into the iOS asset catalog and the Android mipmap folders. Edit this file, never the PNGs.\n"""
import math, os, subprocess, sys

C = 512.0
R = 312.0          # pizza radius on the 1024 canvas
GAP = 40.0         # how far each slice moves out along its middle angle

PALETTES = {
    "light": dict(bg=("#008a83", "#004f4b"), shadow="#00332f", crust_out="#c98633", crust="#eeb05a",
                  sauce="#d4462f", cheese="#ffd36a", cheese_hi="#ffe597", pep_out="#a92c22", pep="#d8432f",
                  pep_hi="#ec6a52", basil="#3f9a4b", basil_vein="#2c7a38"),
    "dark":  dict(bg=("#14201f", "#070c0c"), shadow="#000000", crust_out="#b8782c", crust="#e2a452",
                  sauce="#c43f2a", cheese="#f5c85e", cheese_hi="#fbdb86", pep_out="#9c281f", pep="#cc3e2b",
                  pep_hi="#e0624b", basil="#3a8f45", basil_vein="#296e34"),
}

def wedge(cx, cy, r, a0, a1):
    p0 = (cx + r * math.cos(math.radians(a0)), cy + r * math.sin(math.radians(a0)))
    p1 = (cx + r * math.cos(math.radians(a1)), cy + r * math.sin(math.radians(a1)))
    return f"M{cx:.1f} {cy:.1f} L{p0[0]:.1f} {p0[1]:.1f} A{r:.1f} {r:.1f} 0 0 1 {p1[0]:.1f} {p1[1]:.1f} Z"

def polar(r, a):
    return C + r * math.cos(math.radians(a)), C + r * math.sin(math.radians(a))

def toppings(p, a0):
    """Same toppings on each slice, placed by the slice start angle, so each share looks equal."""
    out = []
    for (rr, da, size) in [(205, 22, 40), (120, 62, 36)]:
        x, y = polar(rr, a0 + da)
        out.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{size}" fill="{p["pep_out"]}"/>')
        out.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{size-6}" fill="{p["pep"]}"/>')
        out.append(f'<circle cx="{x-size*0.3:.1f}" cy="{y-size*0.3:.1f}" r="{size*0.22:.1f}" fill="{p["pep_hi"]}" opacity="0.8"/>')
    x, y = polar(222, a0 + 64)
    rot = a0 + 64 + 90
    out.append(f'<g transform="translate({x:.1f} {y:.1f}) rotate({rot:.1f})">'
               f'<ellipse rx="17" ry="34" fill="{p["basil"]}"/>'
               f'<line x1="0" y1="-26" x2="0" y2="26" stroke="{p["basil_vein"]}" stroke-width="4" stroke-linecap="round"/></g>')
    # Small cheese highlights.
    for (rr, da, rx) in [(160, 30, 26), (250, 50, 18)]:
        x, y = polar(rr, a0 + da)
        out.append(f'<ellipse cx="{x:.1f}" cy="{y:.1f}" rx="{rx}" ry="{rx*0.6:.1f}" fill="{p["cheese_hi"]}" opacity="0.9"/>')
    return "".join(out)

def slice_layers(p, a0, a1, mono=None):
    w = lambda r: wedge(C, C, r, a0, a1)
    if mono:  # one flat shape with cut-out holes, for the tinted and themed icons
        holes = "".join(
            f'<circle cx="{polar(rr, a0+da)[0]:.1f}" cy="{polar(rr, a0+da)[1]:.1f}" r="{s-4}" fill="#000"/>'
            for (rr, da, s) in [(205, 22, 40), (120, 62, 36)])
        p0, p1 = polar(R - 30, a0), polar(R - 30, a1)
        crust_line = (f'<path d="M{p0[0]:.1f} {p0[1]:.1f} A{R-30} {R-30} 0 0 1 {p1[0]:.1f} {p1[1]:.1f}" '
                      f'fill="none" stroke="#000" stroke-width="10"/>')
        mid = f"m{int(a0) % 360}"
        return (f'<mask id="{mid}" maskUnits="userSpaceOnUse" x="0" y="0" width="1024" height="1024">'
                f'<path d="{w(R)}" fill="#fff"/>{crust_line}{holes}</mask>'
                f'<rect width="1024" height="1024" fill="{mono}" mask="url(#{mid})"/>')
    return (f'<path d="{w(R)}" fill="{p["crust_out"]}"/><path d="{w(R-8)}" fill="{p["crust"]}"/>'
            f'<path d="{w(R-34)}" fill="{p["sauce"]}"/><path d="{w(R-44)}" fill="{p["cheese"]}"/>' + toppings(p, a0))

def pizza(p, mono=None, shadow=True):
    out = []
    for k in range(4):
        a0 = -90 + 90 * k + 0.0
        a1 = a0 + 90
        mid = math.radians(a0 + 45)
        dx, dy = GAP * math.cos(mid), GAP * math.sin(mid)
        if shadow and not mono:
            out.append(f'<g transform="translate({dx:.1f} {dy+18:.1f})" opacity="0.45"><path d="{wedge(C, C, R, a0, a1)}" fill="{p["shadow"]}"/></g>')
        out.append(f'<g transform="translate({dx:.1f} {dy:.1f})">{slice_layers(p, a0, a1, mono)}</g>')
    return "".join(out)

def svg(body, bg=None, size=1024):
    defs = bg_rect = ""
    if bg:
        defs = (f'<defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">'
                f'<stop offset="0" stop-color="{bg[0]}"/><stop offset="1" stop-color="{bg[1]}"/></linearGradient></defs>')
        bg_rect = '<rect width="1024" height="1024" fill="url(#bg)"/>'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 1024 1024">'
            f'{defs}{bg_rect}{body}</svg>')

def variant(name):
    if name in PALETTES:
        p = PALETTES[name]
        return svg(pizza(p), bg=p["bg"])
    if name == "tinted":   # grayscale, the system adds the tint
        return svg(pizza(None, mono="#ffffff"), bg=("#000000", "#000000"))
    if name == "android-foreground":   # 108 dp canvas: keep the pizza in the middle 66 dp
        p = PALETTES["light"]
        return svg(f'<g transform="translate(512 512) scale(0.84) translate(-512 -512)">{pizza(p)}</g>')
    if name == "android-monochrome":
        return svg(f'<g transform="translate(512 512) scale(0.84) translate(-512 -512)">{pizza(None, mono="#ffffff")}</g>')
    raise SystemExit(f"unknown variant {name}")

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
IOS = os.path.join(ROOT, "apple/App/Resources/Assets.xcassets/AppIcon.appiconset")
ANDROID_RES = os.path.join(ROOT, "android/app/src/main/res")
ANDROID_DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

def render(svg_path, png_path, size, flatten=False):
    cmd = ["rsvg-convert", "-w", str(size), "-h", str(size), svg_path, "-o", png_path]
    if flatten:  # the App Store icon must have no alpha channel
        cmd[1:1] = ["--background-color", "#000000"]
    subprocess.run(cmd, check=True)

def android_background():
    p = PALETTES["light"]
    return svg("", bg=p["bg"])

if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    sources = {v: variant(v) for v in ["light", "dark", "tinted", "android-foreground", "android-monochrome"]}
    sources["android-background"] = android_background()
    for name, text in sources.items():
        open(os.path.join(here, f"{name}.svg"), "w").write(text)
    if "--svg-only" in sys.argv:
        sys.exit(0)
    for name, file in [("light", "AppIcon.png"), ("dark", "AppIcon-dark.png"), ("tinted", "AppIcon-tinted.png")]:
        render(os.path.join(here, f"{name}.svg"), os.path.join(IOS, file), 1024, flatten=(name == "light"))
    for density, size in ANDROID_DENSITIES.items():
        folder = os.path.join(ANDROID_RES, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        for name, file in [("android-foreground", "ic_launcher_foreground.png"),
                           ("android-background", "ic_launcher_background.png"),
                           ("android-monochrome", "ic_launcher_monochrome.png")]:
            render(os.path.join(here, f"{name}.svg"), os.path.join(folder, file), size)

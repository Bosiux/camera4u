#!/usr/bin/env python3
"""Composes Google Play graphics from raw app screenshots.

Usage:
    python3 scripts/make-play-store-graphics.py <raw-screenshots-dir> <output-dir>

Writes 1080 × 1920 phone screenshots to <output-dir>/screenshots, plus
icon-512.png and feature-graphic.png. Raw screenshots must be named as in
PAGES below. Pages are laid out in HTML and rendered with headless Chrome.
"""

import pathlib
import re
import subprocess
import sys
import tempfile

PAGES = [
    ("1-portra", "01-shoot-on-film", "Shoot on film.", "Hundreds of classic film looks,<br>live in your viewfinder."),
    ("2-bw", "02-black-and-white", "Timeless<br>black &amp; white.", "Real grain that moves like film."),
    ("3-library", "03-every-film", "Every film.<br>Offline.", "Browse the whole collection and<br>download your favorites."),
    ("4-adjust", "04-glow-halation", "Glow &amp; halation.", "Fine-tune tone, color and film<br>effects, all live."),
    ("5-velvia", "05-what-you-see", "What you see<br>is what you get.", "Full-resolution photos,<br>rendered on-device on the GPU."),
]
# Raw screenshots used for the feature graphic's three film tiles.
FEATURE_TILES = ["1-portra", "2-bw", "5-velvia"]

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
ROOT = pathlib.Path(__file__).resolve().parent.parent
FONTS = ('<link rel="stylesheet" href="https://fonts.googleapis.com/css2?'
         'family=Roboto+Flex:opsz,wght@8..144,500;8..144,700;8..144,800&display=block">')


def base_css(width, height):
    return (f"*{{margin:0;padding:0;box-sizing:border-box}}"
            f"html,body{{width:{width}px;height:{height}px;overflow:hidden}}"
            "body{font-family:'Roboto Flex',sans-serif;-webkit-font-smoothing:antialiased}")


def icon_svg():
    """The launcher icon (film strip and lens) on its cream background, from the app's vector drawable."""
    drawable = (ROOT / "app/src/main/res/drawable/ic_launcher_foreground.xml").read_text()
    frame = re.search(r'fillType="evenOdd"\s+android:pathData="([^"]+)"', drawable).group(1)
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">'
            '<rect width="1024" height="1024" fill="#FFF8F1"/>'
            '<g transform="translate(512 512) scale(0.86) translate(-512 -512)">'
            f'<path fill="#F8C72B" fill-rule="evenodd" d="{frame}"/>'
            '<circle cx="512" cy="512" r="72" fill="#3D2E00"/></g></svg>')


def screenshot_page(raw, title, subtitle):
    # Two-line headlines push the device down a little further.
    device_top = 560 if "<br>" in title else 500
    return f"""<!doctype html><html><head><meta charset="utf-8">{FONTS}<style>{base_css(1080, 1920)}
body{{background:radial-gradient(900px 700px at 50% 62%,rgba(228,195,108,.26),rgba(228,195,108,0) 70%),
  linear-gradient(#2E2A21,#16130B 45%,#0D0B06);color:#FFF8F1;text-align:center}}
h1{{font-weight:800;font-size:96px;line-height:1.0;letter-spacing:-2px;margin-top:130px;font-variation-settings:'opsz' 144}}
p{{font-weight:500;font-size:44px;line-height:1.25;color:#E4C36C;margin-top:30px}}
.device{{position:absolute;left:50%;top:{device_top}px;transform:translateX(-50%);width:780px;border-radius:72px;
  padding:18px;background:#0b0a07;border:3px solid #4C4639;box-shadow:0 40px 120px rgba(0,0,0,.6)}}
.device img{{display:block;width:100%;border-radius:56px}}
</style></head><body><h1>{title}</h1><p>{subtitle}</p>
<div class="device"><img src="{raw.as_uri()}"></div></body></html>"""


def feature_page(raw_dir):
    tiles = "".join(f'<div class="tile"><img src="{(raw_dir / f"{name}.png").as_uri()}"></div>' for name in FEATURE_TILES)
    return f"""<!doctype html><html><head><meta charset="utf-8">{FONTS}<style>{base_css(1024, 500)}
body{{background:radial-gradient(520px 380px at 78% 50%,rgba(228,195,108,.22),rgba(228,195,108,0) 70%),
  linear-gradient(120deg,#2E2A21,#16130B 55%,#0D0B06);color:#FFF8F1;position:relative}}
.text{{position:absolute;left:64px;top:0;bottom:0;display:flex;flex-direction:column;justify-content:center;width:430px}}
.icon{{width:96px;height:96px;border-radius:24px;overflow:hidden;margin-bottom:26px}}
.icon svg{{width:96px;height:96px;display:block}}
h1{{font-weight:800;font-size:64px;letter-spacing:-1.5px;line-height:1;font-variation-settings:'opsz' 144}}
p{{font-weight:500;font-size:28px;color:#E4C36C;margin-top:16px;line-height:1.25}}
.tiles{{position:absolute;right:48px;top:50%;transform:translateY(-50%) rotate(-4deg);display:flex;gap:18px}}
.tile{{width:150px;height:330px;border-radius:22px;overflow:hidden;border:3px solid rgba(228,195,108,.55);
  box-shadow:0 20px 50px rgba(0,0,0,.55)}}
.tile:nth-child(2){{margin-top:-30px}}
.tile img{{width:360px;margin-left:-105px;margin-top:-178px}}
</style></head><body><div class="text"><div class="icon">{icon_svg()}</div><h1>Film Camera</h1>
<p>Classic film looks,<br>live in your viewfinder.</p></div><div class="tiles">{tiles}</div></body></html>"""


def icon_page():
    return f"<!doctype html><html><head><style>{base_css(512, 512)}svg{{width:512px;height:512px;display:block}}</style></head><body>{icon_svg()}</body></html>"


def render(html, size, output, workdir):
    page = workdir / (output.stem + ".html")
    page.write_text(html)
    subprocess.run(
        [CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
         "--allow-file-access-from-files", "--virtual-time-budget=8000",
         f"--window-size={size[0]},{size[1]}", f"--screenshot={output}", page.as_uri()],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    print(output)


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    raw_dir = pathlib.Path(sys.argv[1]).resolve()
    out_dir = pathlib.Path(sys.argv[2]).resolve()
    (out_dir / "screenshots").mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        workdir = pathlib.Path(tmp)
        for raw, output, title, subtitle in PAGES:
            source = raw_dir / f"{raw}.png"
            if not source.exists():
                sys.exit(f"Missing {source}")
            render(screenshot_page(source, title, subtitle), (1080, 1920), out_dir / "screenshots" / f"{output}.png", workdir)
        render(feature_page(raw_dir), (1024, 500), out_dir / "feature-graphic.png", workdir)
        render(icon_page(), (512, 512), out_dir / "icon-512.png", workdir)


if __name__ == "__main__":
    main()

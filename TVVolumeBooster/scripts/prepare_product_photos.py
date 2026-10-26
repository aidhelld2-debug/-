#!/usr/bin/env python3
"""Fetch two identifiable *real* product photographs, crop the white studio
background, and embed them as offline assets in the Android TV APK.

Image sources:
  JBL Flip 6 blue: dimprice.co.uk retailer listing
  Yandex Station Mini 3 Pro: superplanshet.ru product photo

Sources belong to the respective websites; images are used for a private
noncommercial home-TV application prototype.
"""
from io import BytesIO
from collections import deque
from pathlib import Path
from PIL import Image
from urllib.request import Request, urlopen
import time
import ssl

BASE = Path(__file__).resolve().parents[1] / "app/src/main/res/drawable-nodpi"
BASE.mkdir(parents=True, exist_ok=True)
SOURCES = {
    "photo_jbl_flip6": "https://www.dimprice.co.uk/image/cache/catalog/JBL/JBL-flip6-blue-1-800x800.png",
    "photo_yandex_mini3pro": "https://www.superplanshet.ru/images/yandex_station_mini_3_pro_3.jpg"
}

def get_photo(url):
    for attempt in range(3):
        try:
            req = Request(url, headers={"User-Agent": "Mozilla/5.0 (compatible; TVAudio/1.1)", "Accept": "image/avif,image/webp,image/*,*/*"})
            # This image-host uses an expired HTTPS certificate. This fetch is only for
            # a non-executable image, whose format is checked by Pillow.
            with urlopen(req, timeout=25, context=ssl._create_unverified_context()) as resp:
                data = resp.read(6_000_000)
            im = Image.open(BytesIO(data))
            im.load()
            if im.width < 200 or im.height < 150:
                raise ValueError("Photograph too small")
            return im.convert("RGB")
        except Exception as e:
            print(f"Retry {attempt + 1}/3: {e}", flush=True)
            if attempt == 2:
                raise
            time.sleep(3)

def make_asset(source, target):
    im = source
    # Flood fill from white or near-white pixels at the four margins only.
    # Internal white elements on Station remain solid whenever enclosed.
    im.thumbnail((500, 350), Image.Resampling.LANCZOS)
    w, h = im.size
    rgb = im.load()
    bg = bytearray(w * h)
    queue = deque()
    # JBL retailer uses a chroma-green studio background, the Station uses white.
    # Only flood from the outside, so the real product body is preserved.
    base = rgb[0, 0] if "jbl" in target.name else (255, 255, 255)
    def looks_white(x, y):
        r, g, b = rgb[x, y]
        if "jbl" in target.name:
            return sum(abs(v-bv) for v,bv in zip((r,g,b), base)) < 65
        return min(r, g, b) > 235 and max(r, g, b) - min(r, g, b) < 19
    for y in range(h):
        for x in (0, w-1):
            if looks_white(x, y) and not bg[y*w+x]:
                bg[y*w+x] = 1
                queue.append((x, y))
    for x in range(w):
        for y in (0, h-1):
            if looks_white(x, y) and not bg[y*w+x]:
                bg[y*w+x] = 1
                queue.append((x, y))
    while queue:
        x, y = queue.popleft()
        for nx, ny in ((x-1,y), (x+1,y), (x,y-1), (x,y+1)):
            if 0 <= nx < w and 0 <= ny < h and not bg[ny*w+nx] and looks_white(nx,ny):
                bg[ny*w+nx] = 1
                queue.append((nx,ny))

    output = im.convert("RGBA")
    pixels = output.load()
    nonempty = []
    for y in range(h):
        for x in range(w):
            if bg[y*w+x]:
                pixels[x,y] = (255,255,255,0)
            else:
                nonempty.append((x,y))
    if not nonempty:
        raise ValueError("No foreground in photograph")
    left = max(0, min(p[0] for p in nonempty)-5)
    top = max(0, min(p[1] for p in nonempty)-5)
    right = min(w, max(p[0] for p in nonempty)+6)
    bottom = min(h, max(p[1] for p in nonempty)+6)
    output = output.crop((left,top,right,bottom))
    # transparent padded canvas for consistent centered cards
    canvas = Image.new("RGBA", (420,250), (0,0,0,0))
    output.thumbnail((402,228), Image.Resampling.LANCZOS)
    canvas.alpha_composite(output, ((420-output.width)//2,(250-output.height)//2))
    canvas.save(target, "WEBP", quality=85, method=6)
    print(f"Wrote photograph: {target}, {target.stat().st_size} bytes")

for key, url in SOURCES.items():
    make_asset(get_photo(url), BASE/(key + ".webp"))

# Android TV launchers prefer a wide banner instead of a square icon.
# Build the banner from the exact user-approved image already embedded in Git.
from PIL import ImageDraw, ImageFont
icon = BASE / "approved_launcher.webp"
if icon.exists():
    canvas = Image.new("RGB", (640, 360), (4, 18, 37))
    source = Image.open(icon).convert("RGBA").resize((280,280), Image.Resampling.LANCZOS)
    canvas.paste(source, (33,40), source)
    draw = ImageDraw.Draw(canvas)
    font_path = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
    font = ImageFont.truetype(font_path, 51)
    draw.text((323,111), "АУДИО", font=font, fill=(225,248,255))
    draw.text((323,180), "ТВ", font=font, fill=(66,226,243))
    canvas.save(BASE/"tv_banner_image.webp", "WEBP", quality=88)
    print("Wrote official TV app banner")

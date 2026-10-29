"""Generate launcher assets from the approved image. Requires Pillow."""

from pathlib import Path
from PIL import Image, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "assets/mobile/app-icon.png"


def main():
    with Image.open(SOURCE) as original:
        image = ImageOps.exif_transpose(original).convert("RGB")
    if image.width != image.height or image.width < 1024:
        raise ValueError("App icon source must be square and at least 1024 pixels")
    res = ROOT / "apps/mobile/android/app/src/main/res"
    for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        folder = res / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        # Compatibility icons have distinct silhouettes; API 26+ uses the
        # adaptive XML, whose outer mask belongs to the system launcher.
        padding = max(1, size // 24)
        square = Image.new("RGBA", (size, size))
        square.paste(image.resize((size - 2 * padding, size - 2 * padding), Image.Resampling.LANCZOS), (padding, padding))
        square.save(folder / "ic_launcher.png", optimize=True)
        scaled = image.resize((size, size), Image.Resampling.LANCZOS).convert("RGBA")
        mask = Image.new("L", (size * 4, size * 4), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
        scaled.putalpha(mask.resize((size, size), Image.Resampling.LANCZOS))
        scaled.save(folder / "ic_launcher_round.png", optimize=True)
    artwork = res / "drawable-nodpi/app_icon_artwork.png"
    artwork.parent.mkdir(parents=True, exist_ok=True)
    image.resize((432, 432), Image.Resampling.LANCZOS).save(artwork, optimize=True)
    appicon = ROOT / "apps/mobile/ios/Resources/Assets.xcassets/AppIcon.appiconset"
    appicon.mkdir(parents=True, exist_ok=True)
    image.resize((1024, 1024), Image.Resampling.LANCZOS).save(appicon / "AppIcon-1024.png", optimize=True)

    # Preview the nominal 72dp visible viewport of the inset 108dp layer.
    preview = Image.new("RGB", (960, 360), "#edf2f4")
    draw = ImageDraw.Draw(preview)
    scaled = image.resize((256, 256), Image.Resampling.LANCZOS)
    for index, kind in enumerate(("circle", "rounded-square", "iOS source")):
        mask = Image.new("L", (256, 256), 0)
        painter = ImageDraw.Draw(mask)
        if kind == "circle":
            painter.ellipse((0, 0, 255, 255), fill=255)
        elif kind == "rounded-square":
            painter.rounded_rectangle((0, 0, 255, 255), radius=56, fill=255)
        else:
            painter.rectangle((0, 0, 255, 255), fill=255)
        x = 32 + index * 320
        preview.paste(scaled, (x, 40), mask)
        draw.text((x, 312), kind, fill="#182c35")
    destination = ROOT / "artifacts/test-logs/m35-icon-preview.png"
    destination.parent.mkdir(parents=True, exist_ok=True)
    preview.save(destination)
    print("Generated Android and iOS assets from the approved source image")


if __name__ == "__main__":
    main()

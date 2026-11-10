#!/usr/bin/env python3
"""Check the opaque day/night color pairs used by the vehicle UI, including button ripple."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PAIRS = (
    ("ui_text", "ui_background"),
    ("ui_text", "ui_surface"),
    ("ui_secondary", "ui_background"),
    ("ui_on_accent", "ui_accent"),
    ("ui_text", "ui_disabled"),
    ("ui_on_disabled", "ui_disabled"),
    ("ui_on_disabled", "ui_background"),
    ("ui_outline", "ui_surface"),
)


def rgb(hex_color):
    return tuple(int(hex_color[i:i + 2], 16) / 255 for i in (1, 3, 5))


def luminance(color):
    linear = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in color]
    return sum(v * weight for v, weight in zip(linear, (0.2126, 0.7152, 0.0722)))


def check(foreground, background, label):
    low, high = sorted((luminance(foreground), luminance(background)))
    ratio = (high + 0.05) / (low + 0.05)
    if ratio < 4.5:
        raise SystemExit(f"FAIL: {label}: {ratio:.2f}:1 < 4.5:1")
    print(f"PASS: {label}: {ratio:.2f}:1")


def main():
    for mode in ("values", "values-night"):
        path = ROOT / "android/app/src/main/res" / mode / "colors.xml"
        colors = {node.attrib["name"]: rgb(node.text) for node in ET.parse(path).getroot()}
        for foreground, background in PAIRS:
            check(colors[foreground], colors[background], f"{mode}/{foreground}/{background}")
        for foreground, background in (("ui_on_accent", "ui_accent"), ("ui_text", "ui_surface")):
            alpha = 0x22 / 255
            pressed = tuple(a * alpha + b * (1 - alpha)
                            for a, b in zip(colors["ui_outline"], colors[background]))
            check(colors[foreground], pressed, f"{mode}/{foreground}/{background}/pressed")


if __name__ == "__main__":
    main()

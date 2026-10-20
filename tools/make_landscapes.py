"""Draws the home screen's time-of-day landscapes (app/assets/landscapes/dusk.jpg, night.jpg, day.jpg).

Usage: python3 tools/make_landscapes.py   (needs numpy and Pillow)
Procedural mountains under a sky; fixed seeds, so the output is the same every run.
"""
import os

import numpy as np
from PIL import Image, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app/assets/landscapes")
os.makedirs(OUT, exist_ok=True)


def save(img, name, q=84):
    img.save(os.path.join(OUT, name), "JPEG", quality=q, optimize=True, progressive=True)

W, H = 1920, 1080
rng = np.random.default_rng(7)

def ridge(n, rough, seed):
    r = np.random.default_rng(seed)
    pts = np.zeros(2 ** 11 + 1)
    step, amp = len(pts) - 1, 1.0
    pts[0], pts[-1] = r.uniform(-.3, .3), r.uniform(-.3, .3)
    while step > 1:
        half = step // 2
        for i in range(half, len(pts) - 1, step):
            pts[i] = (pts[i - half] + pts[i + half]) / 2 + r.uniform(-amp, amp)
        amp *= rough
        step = half
    xs = np.linspace(0, len(pts) - 1, W)
    return np.interp(xs, np.arange(len(pts)), pts)

def lerp(a, b, t):
    return a + (b - a) * t

def scene(name, sky_top, sky_mid, sky_bottom, glow, glow_pos, layers, haze, stars=False, grain=5):
    y = np.linspace(0, 1, H)[:, None]
    x = np.linspace(0, 1, W)[None, :]
    t1 = np.clip(y / .55, 0, 1)
    t2 = np.clip((y - .55) / .45, 0, 1)
    sky = np.where((y < .55)[..., None],lerp(np.array(sky_top), np.array(sky_mid), t1[..., None]),
                   lerp(np.array(sky_mid), np.array(sky_bottom), t2[..., None]))
    sky = np.broadcast_to(sky, (H, W, 3)).copy().astype(np.float32)
    gx, gy, gr = glow_pos
    dist = np.sqrt(((x - gx) * 1.7) ** 2 + (y - gy) ** 2)
    g = np.exp(-(dist / gr) ** 2)[..., None]
    sky = sky + np.array(glow) * g
    if stars:
        s = rng.random((H, W))
        mask = (s > .9993) & (y[:, 0][:, None] < .6)
        sky[mask] = np.minimum(255, sky[mask] + rng.uniform(60, 190, mask.sum())[:, None])
    img = sky
    for i, (base, height, rough, color, seed) in enumerate(layers):
        r = ridge(0, rough, seed)
        r = (r - r.min()) / (r.max() - r.min() + 1e-6)
        top = (base - r * height) * H
        mask = (np.arange(H)[:, None] >= top[None, :]).astype(np.float32)
        mask = np.array(Image.fromarray((mask * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.2))).astype(np.float32)[..., None] / 255
        depth = np.clip((np.arange(H)[:, None] - top[None, :]) / (H * .5), 0, 1)[..., None]
        col = np.array(color, np.float32)
        layer = lerp(col, col * .55, depth)
        img = img * (1 - mask) + layer * mask
        # haze above each layer, thickest near its ridge line
        hz = np.exp(-np.clip(top[None, :] - np.arange(H)[:, None], 0, None) / (H * .05))[..., None]
        img = lerp(img, np.array(haze, np.float32), hz * .22 * (1 - mask) * (len(layers) - i) / len(layers))
    img = img + rng.normal(0, grain, img.shape)
    out = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    save(out, name, 84)

scene("dusk.jpg", (54, 58, 74), (196, 140, 104), (232, 176, 120), (90, 60, 20), (.66, .56, .28),
      [(.66, .16, .55, (92, 74, 70), 3), (.76, .2, .52, (60, 48, 48), 11), (.88, .22, .5, (34, 28, 30), 21), (1.0, .2, .48, (18, 15, 17), 33)],
      (230, 170, 120))
scene("night.jpg", (6, 10, 20), (20, 34, 52), (52, 54, 64), (22, 18, 14), (.5, .78, .3),
      [(.8, .12, .55, (38, 40, 48), 5), (.9, .16, .5, (22, 22, 28), 13), (1.0, .16, .48, (10, 10, 14), 27)],
      (60, 62, 74), stars=True, grain=4)
scene("day.jpg", (150, 170, 186), (206, 210, 208), (226, 222, 212), (20, 18, 10), (.3, .4, .4),
      [(.62, .12, .56, (168, 176, 178), 8), (.72, .18, .52, (126, 138, 140), 17), (.84, .2, .5, (86, 98, 100), 29), (1.0, .22, .48, (50, 60, 62), 41)],
      (220, 222, 218), grain=3)

import os
import sys
from collections import deque
import numpy as np
from PIL import Image, ImageDraw


def _compute_exterior_corner_mask(rgb_arr: np.ndarray) -> np.ndarray:
    """Identify exterior white JPEG corners via border-seeded BFS plus 2px neutral fringe."""
    h, w, _ = rgb_arr.shape
    r = rgb_arr[:, :, 0].astype(np.int16)
    g = rgb_arr[:, :, 1].astype(np.int16)
    b = rgb_arr[:, :, 2].astype(np.int16)

    is_bright = (r > 180) & (g > 180) & (b > 180)
    mask = np.zeros((h, w), dtype=bool)
    queue: deque[tuple[int, int]] = deque()

    def try_seed(y: int, x: int) -> None:
        if 0 <= y < h and 0 <= x < w and not mask[y, x] and is_bright[y, x]:
            mask[y, x] = True
            queue.append((y, x))

    for x in range(w):
        try_seed(0, x)
        try_seed(h - 1, x)
    for y in range(h):
        try_seed(y, 0)
        try_seed(y, w - 1)

    while queue:
        cy, cx = queue.popleft()
        for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            ny, nx = cy + dy, cx + dx
            if 0 <= ny < h and 0 <= nx < w and not mask[ny, nx] and is_bright[ny, nx]:
                mask[ny, nx] = True
                queue.append((ny, nx))

    # Expand by 6px around the border-connected white corners to eliminate JPEG ringing / AA fringe
    for _ in range(6):
        expanded = mask.copy()
        expanded[1:, :] |= mask[:-1, :]
        expanded[:-1, :] |= mask[1:, :]
        expanded[:, 1:] |= mask[:, :-1]
        expanded[:, :-1] |= mask[:, 1:]
        mask = expanded

    return mask


def generate_icons(source_image_path: str, res_dir: str) -> None:
    src_rgb = Image.open(source_image_path).convert("RGB")
    rgb_arr = np.array(src_rgb)
    exterior_mask = _compute_exterior_corner_mask(rgb_arr)

    clean_arr = np.zeros((rgb_arr.shape[0], rgb_arr.shape[1], 4), dtype=np.uint8)
    clean_arr[:, :, :3] = rgb_arr
    clean_arr[:, :, 3] = 255
    clean_arr[exterior_mask] = [18, 15, 32, 255]  # #120F20
    src_clean = Image.fromarray(clean_arr, mode="RGBA")

    densities = {
        "mipmap-mdpi": (48, 108),
        "mipmap-hdpi": (72, 162),
        "mipmap-xhdpi": (96, 216),
        "mipmap-xxhdpi": (144, 324),
        "mipmap-xxxhdpi": (192, 432),
    }

    # Prepare alpha-masked adaptive foreground against #120F20
    fg_arr = clean_arr.astype(float)
    bg_color = np.array([18.0, 15.0, 32.0])
    diff = np.sqrt(np.sum((fg_arr[:, :, :3] - bg_color) ** 2, axis=2))
    alpha = np.clip((diff - 10.0) / 24.0, 0.0, 1.0) * 255.0
    alpha[exterior_mask] = 0.0
    fg_arr[:, :, 3] = alpha
    src_transparent = Image.fromarray(fg_arr.astype(np.uint8), mode="RGBA")

    for folder, (std_size, adaptive_size) in densities.items():
        out_dir = os.path.join(res_dir, folder)
        os.makedirs(out_dir, exist_ok=True)

        # 1. Standard Square Icon (ic_launcher.png)
        launcher_square = src_clean.resize((std_size, std_size), Image.Resampling.LANCZOS)
        mask = Image.new("L", (std_size, std_size), 0)
        draw = ImageDraw.Draw(mask)
        corner_radius = int(std_size * 0.20)
        draw.rounded_rectangle((0, 0, std_size, std_size), radius=corner_radius, fill=255)
        launcher_square.putalpha(mask)
        launcher_square.save(os.path.join(out_dir, "ic_launcher.png"), "PNG")

        # 2. Standard Round Icon (ic_launcher_round.png)
        launcher_round = src_clean.resize((std_size, std_size), Image.Resampling.LANCZOS)
        round_mask = Image.new("L", (std_size, std_size), 0)
        round_draw = ImageDraw.Draw(round_mask)
        round_draw.ellipse((0, 0, std_size, std_size), fill=255)
        launcher_round.putalpha(round_mask)
        launcher_round.save(os.path.join(out_dir, "ic_launcher_round.png"), "PNG")

        # 3. Adaptive Icon Foreground (ic_launcher_foreground.png)
        fg_scaled_size = int(adaptive_size * 0.76)
        fg_scaled = src_transparent.resize((fg_scaled_size, fg_scaled_size), Image.Resampling.LANCZOS)

        fg_canvas = Image.new("RGBA", (adaptive_size, adaptive_size), (0, 0, 0, 0))
        offset = ((adaptive_size - fg_scaled_size) // 2, (adaptive_size - fg_scaled_size) // 2)
        fg_canvas.paste(fg_scaled, offset, fg_scaled)
        fg_canvas.save(os.path.join(out_dir, "ic_launcher_foreground.png"), "PNG")

        print(f"[{folder}] std={std_size}x{std_size}, adaptive_fg={adaptive_size}x{adaptive_size}")

    # 4. Play Store 512x512
    play_store = src_clean.resize((512, 512), Image.Resampling.LANCZOS)
    play_store.save(os.path.join(res_dir, "..", "..", "play_store_512.png"), "PNG")
    print("Generated play_store_512.png")

    # 5. Global Top-Bar Brand Icon (128x128 full-bleed)
    drawable_dir = os.path.join(res_dir, "drawable")
    os.makedirs(drawable_dir, exist_ok=True)
    brand_icon = src_clean.resize((128, 128), Image.Resampling.LANCZOS)
    brand_icon.save(os.path.join(drawable_dir, "ic_app_brand.png"), "PNG")
    print("Generated drawable/ic_app_brand.png")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python generate_icons.py <source_image> <res_dir>")
        sys.exit(1)
    generate_icons(sys.argv[1], sys.argv[2])

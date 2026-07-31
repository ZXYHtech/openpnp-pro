from pathlib import Path
import importlib.util
import math
import subprocess

from PIL import Image, ImageDraw


HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("static_video", HERE / "make_video.py")
STATIC = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(STATIC)

W, H, FPS = STATIC.W, STATIC.H, STATIC.FPS
FFMPEG = STATIC.FFMPEG
SCENES = STATIC.SCENES
SILENT = HERE / "animated-silent.mp4"
FINAL = HERE / "OpenPnP-Pro-2.6P2-功能介绍-动态版.mp4"
ACCENT = "#91aab5"


def clamp(value, low=0.0, high=1.0):
    return max(low, min(high, value))


def smooth(value):
    value = clamp(value)
    return value * value * (3.0 - 2.0 * value)


def out_cubic(value):
    value = clamp(value)
    return 1.0 - (1.0 - value) ** 3


def out_back(value):
    value = clamp(value)
    c1 = 1.70158
    c3 = c1 + 1
    return 1 + c3 * (value - 1) ** 3 + c1 * (value - 1) ** 2


def local_progress(time_value, start, duration=0.55):
    return clamp((time_value - start) / duration)


def alpha_region(source, box, opacity):
    region = source.crop(box).convert("RGBA")
    if opacity < 0.999:
        alpha = region.getchannel("A").point(lambda value: int(value * clamp(opacity)))
        region.putalpha(alpha)
    return region


def place_segment(canvas, source, box, progress, direction="left", distance=60,
                  pop=False, offset=(0, 0)):
    if progress <= 0:
        return
    eased = out_back(progress) if pop else out_cubic(progress)
    region = alpha_region(source, box, smooth(progress))
    width, height = region.size
    if pop:
        scale = 0.82 + 0.18 * eased
        region = region.resize((max(1, int(width * scale)), max(1, int(height * scale))),
                               Image.Resampling.LANCZOS)
    dx = dy = 0
    if direction == "left":
        dx = int((1.0 - eased) * -distance)
    elif direction == "right":
        dx = int((1.0 - eased) * distance)
    elif direction == "up":
        dy = int((1.0 - eased) * distance)
    elif direction == "down":
        dy = int((1.0 - eased) * -distance)
    x = box[0] + (width - region.width) // 2 + dx + offset[0]
    y = box[1] + (height - region.height) // 2 + dy + offset[1]
    canvas.paste(region, (x, y), region)


def bullet_boxes(scene):
    probe = ImageDraw.Draw(Image.new("RGB", (8, 8)))
    title_lines = len(STATIC.text_wrap(probe, scene["title"], STATIC.font(37, True), 570))
    subtitle_y = 168 + title_lines * 49 + 10
    y = subtitle_y + 55
    boxes = []
    for bullet in scene["bullets"]:
        lines = len(STATIC.text_wrap(probe, bullet, STATIC.font(18), 570))
        height = lines * 29 + 13
        boxes.append((48, y - 5, 652, y + height))
        y += height
    return subtitle_y, boxes


def draw_cursor(draw, x, y, pulse, label=None):
    points = [(x, y), (x + 8, y + 27), (x + 15, y + 18), (x + 26, y + 28),
              (x + 32, y + 22), (x + 21, y + 12), (x + 30, y + 7)]
    draw.polygon(points, fill="#f3f5f6", outline="#263238")
    ring = 18 + int(28 * pulse)
    draw.ellipse((x - ring // 2 + 5, y - ring // 2 + 5,
                  x + ring // 2 + 5, y + ring // 2 + 5),
                 outline=(145, 170, 181, int(240 * (1.0 - pulse))), width=3)
    if label:
        draw.rounded_rectangle((x - 25, y + 40, x + 105, y + 71), 13,
                               fill="#35434a", outline="#718a95")
        draw.text((x - 10, y + 47), label, font=STATIC.font(13, True), fill="#edf2f4")


def draw_dynamic_overlay(image, scene, time_value):
    draw = ImageDraw.Draw(image, "RGBA")
    kind = scene["kind"]
    phase = (time_value / 1.65) % 1.0
    pulse = abs(math.sin(phase * math.pi))
    if kind == "screen":
        highlight = scene.get("highlight")
        if highlight:
            px, py, pw, ph = 690, 125, 528, 459
            hx = px + int(highlight[0] * pw)
            hy = py + int(highlight[1] * ph)
            hw = int(highlight[2] * pw)
            hh = int(highlight[3] * ph)
            scan_y = hy + int((0.08 + 0.84 * phase) * hh)
            draw.line((hx + 4, scan_y, hx + hw - 4, scan_y),
                      fill=(145, 170, 181, 190), width=3)
            cursor_x = hx + int(hw * (0.25 + 0.5 * smooth(phase)))
            cursor_y = hy + int(hh * 0.45)
            draw_cursor(draw, cursor_x, cursor_y, pulse)
    elif kind == "shortcut":
        pressed = 0.18 < phase < 0.72
        offset = 6 if pressed else 0
        draw.rounded_rectangle((760, 205 + offset, 915, 315 + offset), 18,
                               fill=(48, 56, 61, 235), outline=ACCENT, width=4)
        draw.text((808, 232 + offset), "Ctrl", font=STATIC.font(38, True),
                  fill="#e6ecef")
        mouse_x = 1077
        mouse_y = int(205 + 45 * (1.0 - out_cubic(phase)))
        draw_cursor(draw, mouse_x, mouse_y, pulse, "绑定成功" if phase > 0.72 else None)
    elif kind == "table":
        divider_x = 795 + int(62 * (0.5 + 0.5 * math.sin(phase * math.pi * 2)))
        draw.line((divider_x, 162, divider_x, 525), fill=(168, 186, 194, 235), width=4)
        draw.polygon([(divider_x - 10, 172), (divider_x, 160), (divider_x + 10, 172)],
                     fill=(168, 186, 194, 235))
        row_y = 310 + int(88 * smooth(phase))
        draw.rounded_rectangle((745, row_y, 1160, row_y + 42), 8,
                               fill=(113, 138, 149, 82), outline=(145, 170, 181, 210),
                               width=2)
        draw_cursor(draw, 1120, row_y + 8, pulse)
    elif kind == "dashboard":
        base_y = 545
        heights = [62, 105, 78, 138, 96, 158]
        for index, height in enumerate(heights):
            delay = index * 0.08
            grow = smooth(clamp((phase - delay) / 0.48))
            x = 735 + index * 70
            draw.rounded_rectangle((x, base_y - int(height * grow), x + 42, base_y),
                                   8, fill=(113, 138, 149, 175))
    elif kind == "floating":
        edge = int(24 + 20 * pulse)
        draw.line((1192, 190, 1192, 510), fill=(145, 170, 181, 190), width=3)
        draw.polygon([(1192 - edge, 335), (1192, 320), (1192, 350)],
                     fill=(145, 170, 181, 220))
        if phase > 0.55:
            draw.rounded_rectangle((825, 275, 1110, 365), 16,
                                   fill=(100, 35, 39, 220), outline=(191, 108, 111, 245),
                                   width=3)
            draw.text((855, 292), "⚠ 视觉识别异常", font=STATIC.font(22, True),
                      fill="#f4e8e8")
            draw.text((857, 328), "任一侧确认 · 同步关闭", font=STATIC.font(16),
                      fill="#e1c8c9")
    elif kind in ("library", "job", "alignment", "autosave", "backup"):
        points = [(790, 185), (1030, 185), (1030, 385), (790, 385)]
        active = int(phase * 4) % 4
        for index, (x, y) in enumerate(points):
            if index == active:
                radius = 48 + int(10 * pulse)
                draw.ellipse((x - radius, y - radius, x + radius, y + radius),
                             outline=(145, 170, 181, 170), width=4)
        route = [(870, 185), (950, 185), (1030, 228), (1030, 342),
                 (950, 385), (870, 385), (790, 342), (790, 228)]
        segment = phase * (len(route) - 1)
        index = min(len(route) - 2, int(segment))
        fraction = segment - index
        x = int(route[index][0] + (route[index + 1][0] - route[index][0]) * fraction)
        y = int(route[index][1] + (route[index + 1][1] - route[index][1]) * fraction)
        draw.ellipse((x - 8, y - 8, x + 8, y + 8), fill=(220, 231, 235, 245))


def render_cover(scene, full, time_value):
    canvas = STATIC.base_image()
    place_segment(canvas, full, (505, 90, 775, 330),
                  local_progress(time_value, 0.15, 0.75),
                  direction="up", distance=45, pop=True)
    place_segment(canvas, full, (400, 326, 880, 368),
                  local_progress(time_value, 0.55), direction="down", distance=24)
    place_segment(canvas, full, (160, 375, 1120, 455),
                  local_progress(time_value, 0.85), direction="left", distance=90)
    place_segment(canvas, full, (215, 455, 1065, 505),
                  local_progress(time_value, 1.15), direction="right", distance=80)
    box = (440, 525, 840, 610) if scene["kind"] == "cover" else (430, 525, 850, 590)
    place_segment(canvas, full, box, local_progress(time_value, 1.55),
                  direction="up", distance=40, pop=scene["kind"] == "cover")
    draw = ImageDraw.Draw(canvas, "RGBA")
    for particle in range(10):
        angle = particle * math.pi * 0.2 + time_value * 0.35
        radius = 215 + 18 * math.sin(time_value + particle)
        x = int(W / 2 + math.cos(angle) * radius)
        y = int(245 + math.sin(angle) * radius * 0.36)
        size = 3 + particle % 3
        draw.ellipse((x - size, y - size, x + size, y + size),
                     fill=(145, 170, 181, 75 + particle * 8))
    underline = smooth(local_progress(time_value, 1.05, 1.1))
    draw.rounded_rectangle((W // 2 - int(230 * underline), 451,
                            W // 2 + int(230 * underline), 455), 2,
                           fill=(113, 138, 149, 210))
    return canvas


def render_scene(scene, full, time_value, duration):
    if scene["kind"] in ("cover", "end"):
        canvas = render_cover(scene, full, time_value)
    else:
        canvas = STATIC.base_image()
        subtitle_y, bullets = bullet_boxes(scene)
        place_segment(canvas, full, (42, 28, 360, 102),
                      local_progress(time_value, 0.08), direction="down", distance=28)
        place_segment(canvas, full, (45, 116, 655, subtitle_y - 2),
                      local_progress(time_value, 0.28, 0.7),
                      direction="left", distance=92)
        place_segment(canvas, full, (45, subtitle_y - 3, 655, subtitle_y + 42),
                      local_progress(time_value, 0.72), direction="left", distance=65)
        visual_offset = (0, 0)
        if scene["kind"] == "floating" and time_value > 3.2:
            dock_phase = smooth(clamp((time_value - 3.2) / 0.8))
            if time_value > 4.3:
                dock_phase = 1.0 - smooth(clamp((time_value - 4.3) / 0.75))
            visual_offset = (int(82 * dock_phase), 0)
        place_segment(canvas, full, (670, 105, 1235, 605),
                      local_progress(time_value, 0.55, 0.75),
                      direction="right", distance=90, pop=True, offset=visual_offset)
        for bullet_index, box in enumerate(bullets):
            place_segment(canvas, full, box,
                          local_progress(time_value, 1.15 + bullet_index * 0.43, 0.48),
                          direction="left", distance=54)
        place_segment(canvas, full, (45, 617, 660, 690),
                      local_progress(time_value, 3.65, 0.65),
                      direction="up", distance=38)
        if time_value > 1.35:
            draw_dynamic_overlay(canvas, scene, time_value - 1.35)
    draw = ImageDraw.Draw(canvas, "RGBA")
    if time_value < 0.42:
        wipe = 1.0 - smooth(time_value / 0.42)
        x = int(W * (1.0 - wipe))
        draw.rectangle((x, 0, W, H), fill=(42, 50, 54, int(245 * wipe)))
        draw.rectangle((max(0, x - 8), 0, x, H), fill=(145, 170, 181, 220))
    if duration - time_value < 0.28:
        p = smooth((0.28 - (duration - time_value)) / 0.28)
        x = int(W * (1.0 - p))
        draw.rectangle((x, 0, W, H), fill=(42, 50, 54, int(245 * p)))
        draw.rectangle((max(0, x - 7), 0, x, H), fill=(145, 170, 181, 220))
    return canvas.convert("RGB")


def encode_animation():
    command = [
        str(FFMPEG), "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
        "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
        "-an", "-c:v", "mpeg4", "-q:v", "2", "-pix_fmt", "yuv420p",
        str(SILENT),
    ]
    process = subprocess.Popen(command, stdin=subprocess.PIPE,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for index, scene in enumerate(SCENES):
            full = STATIC.make_slide(scene, index)
            frame_count = round(scene["duration"] * FPS)
            for frame_index in range(frame_count):
                frame = render_scene(scene, full, frame_index / FPS, scene["duration"])
                process.stdin.write(frame.tobytes())
    finally:
        if process.stdin:
            process.stdin.close()
    result = process.wait()
    if result != 0:
        raise RuntimeError(f"Video encoder exited with {result}")


def mux_audio():
    audio = STATIC.make_audio(sum(scene["duration"] for scene in SCENES))
    subprocess.run([
        str(FFMPEG), "-y", "-i", str(SILENT), "-i", str(audio),
        "-c:v", "copy", "-c:a", "aac", "-b:a", "160k",
        "-shortest", "-movflags", "+faststart",
        "-metadata", "title=OpenPnP Pro 2.6P2 动态功能介绍",
        str(FINAL),
    ], check=True)


def main():
    encode_animation()
    mux_audio()
    print(FINAL)


if __name__ == "__main__":
    main()

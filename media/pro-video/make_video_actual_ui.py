from pathlib import Path
import importlib.util
import math
import subprocess

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter


HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("animated_video", HERE / "make_video_animated.py")
ANIM = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ANIM)
STATIC = ANIM.STATIC

W, H, FPS = ANIM.W, ANIM.H, ANIM.FPS
FFMPEG = ANIM.FFMPEG
SCENES = ANIM.SCENES
ACTUAL = HERE / "actual-ui"
GENERATED = HERE / "generated"
SILENT = HERE / "actual-ui-silent.mp4"
FINAL = HERE / "OpenPnP-Pro-2.6P2-功能介绍.mp4"

UI_BOX = (682, 126, 1228, 533)
UI_INNER = (696, 160, 1214, 451)
ACCENT = (145, 170, 181)

PAGE_FILES = {
    "workspace": "actual-workspace.png",
    "dashboard": "actual-dashboard.png",
    "parts": "actual-parts.png",
    "packages": "actual-packages.png",
    "feeders": "actual-feeders.png",
    "boards": "actual-boards.png",
    "backup": "actual-backup.png",
}

SCENE_PAGES = {
    1: ("workspace",),
    2: ("workspace",),
    3: ("workspace",),
    4: ("parts",),
    5: ("parts", "packages"),
    6: ("boards", "workspace"),
    7: ("feeders", "packages"),
    8: ("dashboard",),
    9: ("workspace",),
    10: ("workspace",),
    11: ("backup",),
}

GENERATED_SCENES = {
    0: "smt-machine-cinematic.png",
    7: "bottom-vision-inspection.png",
    11: "shared-data-backup.png",
    12: "smt-machine-cinematic.png",
}


def clamp(value, low=0.0, high=1.0):
    return max(low, min(high, value))


def smooth(value):
    value = clamp(value)
    return value * value * (3.0 - 2.0 * value)


def fit_crop(image, size, offset=(0.5, 0.5)):
    target_w, target_h = size
    scale = max(target_w / image.width, target_h / image.height)
    resized = image.resize((round(image.width * scale), round(image.height * scale)),
                           Image.Resampling.LANCZOS)
    extra_x = max(0, resized.width - target_w)
    extra_y = max(0, resized.height - target_h)
    left = round(extra_x * clamp(offset[0]))
    top = round(extra_y * clamp(offset[1]))
    return resized.crop((left, top, left + target_w, top + target_h))


def rounded_mask(size, radius):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1),
                                           radius=radius, fill=255)
    return mask


def load_assets():
    pages = {}
    inner_size = (UI_INNER[2] - UI_INNER[0], UI_INNER[3] - UI_INNER[1])
    for name, filename in PAGE_FILES.items():
        source = Image.open(ACTUAL / filename).convert("RGB")
        pages[name] = fit_crop(source, inner_size, (0.5, 0.08))
    generated = {}
    generated_size = (UI_BOX[2] - UI_BOX[0], UI_BOX[3] - UI_BOX[1])
    for filename in set(GENERATED_SCENES.values()):
        source = Image.open(GENERATED / filename).convert("RGB")
        generated[filename] = fit_crop(source, generated_size)
    return pages, generated


PAGES, GEN = load_assets()
SLIDES = [STATIC.make_slide(scene, index).convert("RGB") for index, scene in enumerate(SCENES)]


def draw_click(draw, x, y, phase):
    phase = phase % 1.0
    radius = 12 + int(32 * phase)
    alpha = int(230 * (1.0 - phase))
    draw.ellipse((x - radius, y - radius, x + radius, y + radius),
                 outline=(*ACCENT, alpha), width=4)


def draw_ui_window(canvas, page_name, time_value, opacity=1.0, focus=None):
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer, "RGBA")
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    shadow_draw = ImageDraw.Draw(shadow, "RGBA")
    shadow_draw.rounded_rectangle((UI_BOX[0] + 7, UI_BOX[1] + 12,
                                   UI_BOX[2] + 7, UI_BOX[3] + 12),
                                  radius=18, fill=(0, 0, 0, 120))
    shadow = shadow.filter(ImageFilter.GaussianBlur(12))
    layer.alpha_composite(shadow)
    draw.rounded_rectangle(UI_BOX, radius=18, fill=(38, 45, 49, 252),
                           outline=(*ACCENT, 210), width=2)
    draw.ellipse((701, 140, 709, 148), fill=(193, 102, 105, 235))
    draw.ellipse((716, 140, 724, 148), fill=(196, 166, 105, 235))
    draw.ellipse((731, 140, 739, 148), fill=(111, 165, 131, 235))
    draw.text((755, 135), "OPENPNP PRO · 实际界面演示",
              font=STATIC.font(13, True), fill=(224, 231, 234, 245))

    screenshot = PAGES[page_name].convert("RGBA")
    screenshot.putalpha(rounded_mask(screenshot.size, 10))
    layer.alpha_composite(screenshot, (UI_INNER[0], UI_INNER[1]))
    draw.rounded_rectangle(UI_INNER, radius=10, outline=(124, 145, 154, 175), width=2)
    draw.rounded_rectangle((696, 469, 1214, 516), radius=12,
                           fill=(50, 61, 66, 235), outline=(101, 121, 130, 180))
    labels = {
        "workspace": "任务工作区 · 顶部快捷功能 · 底部进度",
        "dashboard": "生产看板 · 最近任务 · 异常分析",
        "parts": "元件库 · 检索匹配 · 行拖拽",
        "packages": "封装库 · 别名归类 · 对位检查",
        "feeders": "飞达 · 当前任务浅绿高亮",
        "boards": "单板与拼版 · 任务关联数据",
        "backup": "备份与恢复中心 · 分类和文件级选择",
    }
    draw.text((714, 482), labels[page_name], font=STATIC.font(15, True),
              fill=(227, 233, 236, 245))
    draw.text((1138, 483), "2.6P2", font=STATIC.font(14, True),
              fill=(*ACCENT, 255))

    if focus:
        x, y, w, h = focus
        box = (UI_INNER[0] + int(x * (UI_INNER[2] - UI_INNER[0])),
               UI_INNER[1] + int(y * (UI_INNER[3] - UI_INNER[1]),
               ),
               UI_INNER[0] + int((x + w) * (UI_INNER[2] - UI_INNER[0])),
               UI_INNER[1] + int((y + h) * (UI_INNER[3] - UI_INNER[1])))
        scan = box[1] + int((0.12 + 0.76 * ((time_value / 1.8) % 1.0)) * (box[3] - box[1]))
        draw.rounded_rectangle(box, radius=8, outline=(*ACCENT, 235), width=3)
        draw.line((box[0] + 5, scan, box[2] - 5, scan), fill=(*ACCENT, 175), width=2)

    if opacity < 1.0:
        layer.putalpha(layer.getchannel("A").point(lambda alpha: int(alpha * opacity)))
    canvas.alpha_composite(layer)


def cursor_path(scene_index, time_value):
    paths = {
        1: ((728, 198), (735, 390)),
        2: ((778, 190), (1055, 188)),
        3: ((850, 195), (1070, 210)),
        4: ((860, 245), (1060, 350)),
        5: ((930, 260), (1110, 400)),
        6: ((900, 245), (1135, 382)),
        7: ((890, 260), (1090, 382)),
        8: ((825, 248), (1110, 365)),
        9: ((1035, 205), (1170, 475)),
        10: ((770, 210), (1095, 470)),
        11: ((790, 245), (1130, 460)),
    }
    start, end = paths.get(scene_index, ((800, 210), (1080, 380)))
    progress = smooth((time_value / 2.2) % 1.0)
    return (int(start[0] + (end[0] - start[0]) * progress),
            int(start[1] + (end[1] - start[1]) * progress))


def draw_generated(canvas, filename, time_value, opacity=1.0):
    image = GEN[filename]
    drift_x = int(9 * math.sin(time_value * 0.7))
    drift_y = int(6 * math.cos(time_value * 0.55))
    enlarged = image.resize((image.width + 26, image.height + 18), Image.Resampling.LANCZOS)
    left = 13 + drift_x
    top = 9 + drift_y
    crop = enlarged.crop((left, top, left + image.width, top + image.height)).convert("RGBA")
    crop.putalpha(rounded_mask(crop.size, 18))
    if opacity < 1.0:
        crop.putalpha(crop.getchannel("A").point(lambda alpha: int(alpha * opacity)))
    canvas.alpha_composite(crop, (UI_BOX[0], UI_BOX[1]))
    draw = ImageDraw.Draw(canvas, "RGBA")
    scan_y = UI_BOX[1] + int(((time_value / 2.1) % 1.0) * (UI_BOX[3] - UI_BOX[1]))
    draw.line((UI_BOX[0] + 10, scan_y, UI_BOX[2] - 10, scan_y),
              fill=(*ACCENT, int(170 * opacity)), width=3)
    draw.rounded_rectangle(UI_BOX, radius=18, outline=(*ACCENT, int(220 * opacity)), width=2)


def cover_with_generated(index, time_value):
    generated = GEN[GENERATED_SCENES[index]]
    offset = (0.48 + 0.025 * math.sin(time_value * 0.35),
              0.5 + 0.02 * math.cos(time_value * 0.28))
    background = fit_crop(generated, (W, H), offset).filter(ImageFilter.GaussianBlur(0.35))
    background = ImageEnhance.Brightness(background).enhance(0.52).convert("RGBA")
    shade = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    shade_draw = ImageDraw.Draw(shade, "RGBA")
    for x in range(760):
        alpha = int(212 * (1.0 - x / 760.0))
        shade_draw.line((x, 0, x, H), fill=(23, 29, 32, alpha))
    background.alpha_composite(shade)
    foreground = SLIDES[index].convert("RGBA")
    mask = Image.new("L", (W, H), 0)
    mask_draw = ImageDraw.Draw(mask)
    mask_draw.rectangle((0, 0, 690, H), fill=255)
    mask_draw.rectangle((690, 0, W, 120), fill=230)
    foreground.putalpha(mask.filter(ImageFilter.GaussianBlur(22)))
    background.alpha_composite(foreground)
    draw = ImageDraw.Draw(background, "RGBA")
    draw.text((970, 666), "真实软件界面 + 生成式工业视觉",
              font=STATIC.font(15, True), fill=(232, 238, 240, 225))
    return background


def render_frame(scene, index, time_value):
    if index in (0, 12):
        canvas = cover_with_generated(index, time_value)
    else:
        canvas = SLIDES[index].convert("RGBA")
        draw = ImageDraw.Draw(canvas, "RGBA")
        draw.rounded_rectangle((666, 103, 1244, 550), radius=22,
                               fill=(32, 39, 43, 240), outline=(94, 112, 120, 160), width=2)
        pages = SCENE_PAGES.get(index, ("workspace",))
        active_page = pages[0]
        if len(pages) > 1 and time_value > scene["duration"] * 0.52:
            active_page = pages[1]

        generated_filename = GENERATED_SCENES.get(index)
        if generated_filename and time_value < 2.35:
            draw_generated(canvas, generated_filename, time_value)
            transition = smooth((time_value - 1.75) / 0.6)
            if transition > 0:
                draw_ui_window(canvas, active_page, time_value, transition)
        else:
            transition_start = scene["duration"] * 0.52
            if len(pages) > 1 and transition_start - 0.4 < time_value < transition_start + 0.4:
                blend = smooth((time_value - (transition_start - 0.4)) / 0.8)
                draw_ui_window(canvas, pages[0], time_value, 1.0)
                draw_ui_window(canvas, pages[1], time_value, blend)
            else:
                focus = {
                    1: (0.00, 0.05, 0.20, 0.84),
                    2: (0.18, 0.00, 0.78, 0.17),
                    4: (0.20, 0.08, 0.75, 0.72),
                    5: (0.18, 0.08, 0.77, 0.75),
                    8: (0.03, 0.10, 0.92, 0.76),
                    11: (0.02, 0.08, 0.95, 0.78),
                }.get(index)
                draw_ui_window(canvas, active_page, time_value, 1.0, focus)

        draw = ImageDraw.Draw(canvas, "RGBA")
        cursor_x, cursor_y = cursor_path(index, time_value)
        ANIM.draw_cursor(draw, cursor_x, cursor_y, abs(math.sin(time_value * math.pi)))
        if (time_value % 2.2) > 1.7:
            draw_click(draw, cursor_x + 5, cursor_y + 5, (time_value % 2.2 - 1.7) / 0.5)

        if index == 3:
            pressed = 1.15 < (time_value % 2.7) < 2.2
            y_offset = 5 if pressed else 0
            draw.rounded_rectangle((728, 465 + y_offset, 814, 516 + y_offset), 12,
                                   fill=(48, 58, 63, 245), outline=(*ACCENT, 240), width=3)
            draw.text((744, 477 + y_offset), "Ctrl", font=STATIC.font(22, True),
                      fill=(238, 242, 244, 255))
        if index == 7 and time_value < 2.35:
            angle = math.sin(time_value * 1.5) * 10
            draw.arc((865, 235, 1055, 425), start=20 + angle, end=325 + angle,
                     fill=(*ACCENT, 225), width=4)
            draw.line((960, 225, 960, 435), fill=(*ACCENT, 165), width=2)
            draw.line((850, 330, 1070, 330), fill=(*ACCENT, 165), width=2)
        if index == 9:
            dock = smooth((time_value - 2.0) / 0.8)
            x0 = int(1035 + 135 * dock)
            draw.rounded_rectangle((x0, 204, x0 + 165, 382), 18,
                                   fill=(39, 48, 52, 244), outline=(*ACCENT, 230), width=3)
            draw.rectangle((x0 + 14, 239, x0 + 151, 318), fill=(27, 34, 37, 255))
            for button in range(4):
                bx = x0 + 12 + button * 38
                draw.rounded_rectangle((bx, 334, bx + 31, 365), 9,
                                       fill=(73, 91, 99, 245), outline=(119, 140, 149, 210))

    draw = ImageDraw.Draw(canvas, "RGBA")
    if time_value < 0.42:
        opacity = int(240 * (1.0 - smooth(time_value / 0.42)))
        draw.rectangle((0, 0, W, H), fill=(30, 37, 40, opacity))
    remaining = scene["duration"] - time_value
    if remaining < 0.32:
        opacity = int(230 * smooth((0.32 - remaining) / 0.32))
        draw.rectangle((0, 0, W, H), fill=(30, 37, 40, opacity))
    return canvas.convert("RGB")


def encode():
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
            frame_count = round(scene["duration"] * FPS)
            for frame_index in range(frame_count):
                process.stdin.write(render_frame(scene, index, frame_index / FPS).tobytes())
    finally:
        if process.stdin:
            process.stdin.close()
    result = process.wait()
    if result != 0:
        raise RuntimeError(f"Video encoder exited with {result}")


def mux():
    audio = STATIC.make_audio(sum(scene["duration"] for scene in SCENES))
    subprocess.run([
        str(FFMPEG), "-y", "-i", str(SILENT), "-i", str(audio),
        "-c:v", "copy", "-c:a", "aac", "-b:a", "160k",
        "-shortest", "-movflags", "+faststart",
        "-metadata", "title=OpenPnP Pro 2.6P2 实际界面功能演示",
        str(FINAL),
    ], check=True)


def main():
    encode()
    mux()
    print(FINAL)


if __name__ == "__main__":
    main()

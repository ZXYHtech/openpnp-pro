from pathlib import Path
import math
import subprocess
import wave

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter


ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
SLIDES = OUT / "slides"
CLIPS = OUT / "clips"
W, H = 1280, 720
FPS = 30
FFMPEG = Path(r"C:\ProgramFiles\38_SolidWorks2016\SOLIDWORKS\FloXpress\bin\ffmpeg.exe")
FONT = Path(r"C:\Windows\Fonts\msyh.ttc")
FONT_BOLD = Path(r"C:\Windows\Fonts\msyhbd.ttc")
LOGO = ROOT / "src/main/resources/branding/openpnp-pro-logo.png"
SCREENSHOT = ROOT / "openpnp-pro-modern-ui-final.png"
FINAL = OUT / "OpenPnP-Pro-2.6P2-功能介绍.mp4"


SCENES = [
    {
        "duration": 4.5,
        "eyebrow": "OPENPNP PRO · 2.6P2",
        "title": "为高效率贴片而重新设计",
        "subtitle": "界面、任务、资料、监控与安全工作流全面进化",
        "bullets": [],
        "kind": "cover",
    },
    {
        "duration": 6.5,
        "eyebrow": "01 / 工作空间",
        "title": "现代界面，一眼掌握生产",
        "subtitle": "更清晰、更紧凑，也更适合长时间操作",
        "bullets": [
            "可折叠左侧导航，释放任务工作区",
            "低饱和石墨视觉与 Win7 / Win10 兼容深色主题",
            "坐标、总进度、预计完成和剩余数量合并到底部",
            "全新启动进度页、Pro Logo、版本号与 Pro 日志",
        ],
        "chips": ["深色主题", "启动进度", "底部状态"],
        "kind": "screen",
        "highlight": (0.01, 0.04, 0.22, 0.88),
    },
    {
        "duration": 6.5,
        "eyebrow": "02 / 快速操作",
        "title": "常用功能，不再藏在面板里",
        "subtitle": "把生产动作送到顶部，减少页面往返",
        "bullets": [
            "控制面板按钮可添加到顶部快捷任务栏",
            "特殊功能与工具头选择同步显示，并可快速删减",
            "顶部安全取料：有料时先确认抛料，无料时直接取料",
            "“吸嘴贴完暂停”可置于贴片板列表和顶部栏",
        ],
        "chips": ["快捷任务栏", "安全取料", "贴完暂停"],
        "kind": "screen",
        "highlight": (0.20, 0.02, 0.77, 0.15),
    },
    {
        "duration": 6.5,
        "eyebrow": "03 / 快捷键与引导",
        "title": "Ctrl + 点击，现场定义快捷键",
        "subtitle": "像专业设计软件一样，边操作边设置",
        "bullets": [
            "按住 Ctrl 单击菜单或按钮，立即绑定自定义快捷键",
            "首次使用引导直接覆盖真实界面，逐区聚光讲解",
            "引导包含快捷键、顶部栏、悬浮窗、看板与更新内容",
            "界面中不再长期显示干扰操作的快捷键提示文字",
        ],
        "chips": ["Ctrl", "鼠标单击", "首次引导"],
        "kind": "shortcut",
    },
    {
        "duration": 6.5,
        "eyebrow": "04 / 表格效率",
        "title": "每张表，都按你的方式工作",
        "subtitle": "宽度、顺序、筛选和批量编辑全面统一",
        "bullets": [
            "实线列边界更易定位；双击或按钮自动适配列宽",
            "长文字列按 50% 可见宽度收敛，悬停显示全称",
            "所有主要表格支持选中行拖拽换位和默认排序",
            "最大化与窗口模式分别记忆每张表的列宽",
            "多选后输入并回车，可批量修改优先级及其他字段",
        ],
        "chips": ["自动列宽", "拖拽换位", "批量修改"],
        "kind": "table",
    },
    {
        "duration": 6.5,
        "eyebrow": "05 / 物料与封装",
        "title": "一次归类，跨任务复用",
        "subtitle": "减少重复型号和来回改料带来的风险",
        "bullets": [
            "物料与封装支持分类、别名和同 ID 内部型号",
            "输入检索式匹配替代超长原生下拉列表",
            "元件、封装和飞达的任务用料以浅绿色区分",
            "可筛选“仅当前任务”或“非当前任务”数据",
            "修改参数时保持视图稳定，仅切换显示条件后刷新",
        ],
        "chips": ["别名", "智能检索", "任务高亮"],
        "kind": "library",
    },
    {
        "duration": 6.5,
        "eyebrow": "06 / 贴片任务",
        "title": "把高频动作放在第一层",
        "subtitle": "任务执行更快，异常恢复更直接",
        "bullets": [
            "右键首层提供启用、禁用、已贴片与未贴片",
            "多选器件可统一修改优先级和常用属性",
            "底部视觉异常提供抛料、重新拾取等恢复选择",
            "任务统计显示总耗时、预计完成时间和剩余数量",
            "吸嘴现有物料全部贴完后再安全暂停",
        ],
        "chips": ["首层右键", "异常恢复", "时间统计"],
        "kind": "job",
    },
    {
        "duration": 6.5,
        "eyebrow": "07 / 对位与飞达",
        "title": "任务用料与对位状态更直观",
        "subtitle": "在机器动作前发现配置问题",
        "bullets": [
            "载入任务后，正在使用的飞达和物料自动区分显示",
            "封装对位检查移动至单板原点并展示全部器件",
            "任务工具头与右下角工具头保持双向同步",
            "Mark、两点定位、相机与工具移动可加入快捷入口",
            "自动巡检与 AI 器件中心校验已纳入后续路线",
        ],
        "chips": ["飞达高亮", "封装检查", "对位入口"],
        "kind": "alignment",
    },
    {
        "duration": 6.5,
        "eyebrow": "08 / 生产看板",
        "title": "每次任务，都留下可分析的数据",
        "subtitle": "从结果回看速度、质量和异常原因",
        "bullets": [
            "最近任务独占看板，可直接重新加载任务",
            "统计耗时、单板数、器件数、平均速度与报错次数",
            "汇总错误内容，分析可能原因并给出改进建议",
            "实时展示完成率、剩余量、吞吐、停机与节拍指标",
            "最近任务关联单板和拼版表，不复制用户任务文件",
        ],
        "chips": ["最近任务", "产能指标", "错误分析"],
        "kind": "dashboard",
    },
    {
        "duration": 6.5,
        "eyebrow": "09 / 桌面悬浮监控",
        "title": "离开主界面，也能盯住生产",
        "subtitle": "实时画面、快捷操作和错误处理合为一体",
        "bullets": [
            "显示当前活跃摄像头，可缩放、最小化或停靠屏幕边缘",
            "软件位于前台时自动收起画面，只保留标题与按钮",
            "支持 4–8 个自定义按钮，覆盖 50 多项软件功能",
            "必须可选“吸嘴贴完暂停”，并保留启动、停止、取料",
            "错误发生时强制展开；任一侧确认，主界面与悬浮窗同步",
        ],
        "chips": ["实时画面", "4–8 按钮", "错误同步"],
        "kind": "floating",
    },
    {
        "duration": 6.5,
        "eyebrow": "10 / 自动保存与恢复",
        "title": "工作持续保存，操作不中断",
        "subtitle": "任务、单板和拼版获得临时保护副本",
        "bullets": [
            "配置、任务、单板与拼版定时保存到临时目录",
            "自动保存后台执行，尽量不影响贴片操作",
            "异常退出后可利用临时副本恢复工作",
            "保存错误与悬浮监控共享同一个确认状态",
            "退出可靠性修复，避免关闭软件时再次报错",
        ],
        "chips": ["定时保存", "异常恢复", "可靠退出"],
        "kind": "autosave",
    },
    {
        "duration": 6.5,
        "eyebrow": "11 / 数据安全与安装",
        "title": "升级程序，不分裂机器数据",
        "subtitle": "原版与 Pro 可共存，但关键生产参数保持唯一",
        "bullets": [
            "备份中心可按类别和文件选择，并显示用途、大小和数据项数",
            "首次接入旧配置目录，必须先完成全量安全备份",
            "恢复前自动创建保护副本，避免误覆盖",
            "原版与 Pro 共用飞达、吸嘴、相机、物料和封装数据",
            "检测多个 OpenPnP 进程，只提示关闭，不强制结束",
            "2.6P2 修复 Win10 同版本重新安装不显示的问题",
        ],
        "chips": ["选择性备份", "共享机器数据", "升级安装"],
        "kind": "backup",
    },
    {
        "duration": 5.0,
        "eyebrow": "OPENPNP PRO · 2.6P2",
        "title": "更少切换 · 更快贴装 · 更安全的数据",
        "subtitle": "为真实生产现场持续进化",
        "bullets": [],
        "kind": "end",
    },
]


def font(size, bold=False):
    return ImageFont.truetype(str(FONT_BOLD if bold else FONT), size)


def rounded(draw, box, radius, fill, outline=None, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def text_wrap(draw, text, fnt, max_width):
    lines, current = [], ""
    for char in text:
        trial = current + char
        if draw.textbbox((0, 0), trial, font=fnt)[2] <= max_width:
            current = trial
        else:
            if current:
                lines.append(current)
            current = char
    if current:
        lines.append(current)
    return lines


def base_image():
    image = Image.new("RGB", (W, H), "#1b1f22")
    draw = ImageDraw.Draw(image)
    for y in range(H):
        p = y / H
        color = (
            int(25 + 8 * p),
            int(29 + 10 * p),
            int(31 + 11 * p),
        )
        draw.line((0, y, W, y), fill=color)
    draw.ellipse((W - 440, -270, W + 130, 300), fill="#252d31")
    draw.ellipse((-260, H - 250, 280, H + 280), fill="#20282c")
    return image


def paste_logo(image, size=64, x=54, y=36):
    logo = Image.open(LOGO).convert("RGBA")
    logo.thumbnail((size, size), Image.Resampling.LANCZOS)
    image.paste(logo, (x, y), logo)


def screen_panel(image, highlight=None):
    screenshot = Image.open(SCREENSHOT).convert("RGB")
    panel = (690, 125, 1218, 584)
    pw, ph = panel[2] - panel[0], panel[3] - panel[1]
    scale = max(pw / screenshot.width, ph / screenshot.height)
    screenshot = screenshot.resize(
        (int(screenshot.width * scale), int(screenshot.height * scale)),
        Image.Resampling.LANCZOS,
    )
    left = (screenshot.width - pw) // 2
    top = (screenshot.height - ph) // 2
    screenshot = screenshot.crop((left, top, left + pw, top + ph))
    shadow = Image.new("RGBA", image.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sd.rounded_rectangle((panel[0] - 14, panel[1] + 12, panel[2] + 14, panel[3] + 26),
                         radius=24, fill=(0, 0, 0, 95))
    shadow = shadow.filter(ImageFilter.GaussianBlur(16))
    image.paste(shadow, (0, 0), shadow)
    mask = Image.new("L", (pw, ph), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, pw, ph), radius=18, fill=255)
    image.paste(screenshot, (panel[0], panel[1]), mask)
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle(panel, radius=18, outline="#53626a", width=2)
    if highlight:
        x, y, w, h = highlight
        box = (
            panel[0] + int(x * pw),
            panel[1] + int(y * ph),
            panel[0] + int((x + w) * pw),
            panel[1] + int((y + h) * ph),
        )
        draw.rounded_rectangle(box, radius=10, outline="#91aab5", width=4)


def draw_diagram(image, kind):
    draw = ImageDraw.Draw(image)
    panel = (690, 125, 1218, 584)
    rounded(draw, panel, 20, "#22292d", "#4b585f", 2)
    if kind == "shortcut":
        rounded(draw, (760, 205, 915, 315), 18, "#30383d", "#718a95", 3)
        draw.text((808, 232), "Ctrl", font=font(38, True), fill="#e6ecef")
        draw.line((935, 260, 1035, 260), fill="#718a95", width=5)
        draw.polygon([(1035, 260), (1014, 248), (1014, 272)], fill="#718a95")
        rounded(draw, (1055, 205, 1150, 315), 18, "#35434a", "#91aab5", 3)
        draw.ellipse((1080, 228, 1125, 288), outline="#e6ecef", width=4)
        draw.line((1102, 229, 1102, 259), fill="#e6ecef", width=3)
        draw.text((795, 380), "按住  +  单击功能按钮", font=font(29, True), fill="#d7e0e4")
        draw.text((840, 440), "立即设置快捷键", font=font(25), fill="#91aab5")
    elif kind == "table":
        x0, y0 = 735, 180
        widths = [90, 165, 120, 200]
        for row in range(6):
            x = x0
            for col, width in enumerate(widths):
                fill = "#35434a" if row == 0 else ("#293034" if row % 2 else "#252b2f")
                draw.rectangle((x, y0 + row * 55, x + width, y0 + (row + 1) * 55),
                               fill=fill, outline="#59666d", width=1)
                if row:
                    draw.line((x + 12, y0 + row * 55 + 27, x + width - 12,
                               y0 + row * 55 + 27), fill="#718087", width=3)
                x += width
        draw.line((x0 + 90, y0 - 14, x0 + 90, y0 + 344), fill="#a8bac2", width=4)
        draw.polygon([(x0 + 77, y0 - 5), (x0 + 90, y0 - 15), (x0 + 103, y0 - 5)],
                     fill="#a8bac2")
    elif kind == "dashboard":
        colors = ["#718a95", "#82986e", "#a48c68", "#786f8f"]
        for i, (label, value) in enumerate([("平均速度", "6.8k/h"), ("完成单板", "24"),
                                             ("器件数量", "3,860"), ("异常次数", "3")]):
            x = 725 + (i % 2) * 235
            y = 165 + (i // 2) * 155
            rounded(draw, (x, y, x + 210, y + 125), 16, "#2b3337", "#4d5a60")
            draw.text((x + 18, y + 17), label, font=font(19), fill="#9eacb2")
            draw.text((x + 18, y + 54), value, font=font(31, True), fill=colors[i])
        points = [(740, 510), (805, 470), (870, 485), (940, 420), (1010, 438),
                  (1080, 360), (1160, 382)]
        draw.line(points, fill="#91aab5", width=5)
        for point in points:
            draw.ellipse((point[0] - 6, point[1] - 6, point[0] + 6, point[1] + 6),
                         fill="#d8e2e6")
    elif kind == "floating":
        rounded(draw, (760, 160, 1150, 530), 22, "#12181b", "#718a95", 3)
        draw.rectangle((780, 205, 1130, 405), fill="#293337")
        draw.ellipse((905, 255, 1005, 355), outline="#718a95", width=8)
        draw.ellipse((945, 295, 965, 315), fill="#dbe5e8")
        for i, label in enumerate(["启动", "停止", "取料", "贴完暂停"]):
            x = 780 + i * 87
            rounded(draw, (x, 430, x + 78, 485), 13, "#35434a", "#607078")
            draw.text((x + 15, 446), label, font=font(15, True), fill="#edf2f4")
        draw.polygon([(1138, 270), (1180, 295), (1138, 320)], fill="#91aab5")
    else:
        nodes = {
            "library": [("物料", 790, 185), ("别名", 1030, 185), ("封装", 790, 385),
                        ("任务复用", 1030, 385)],
            "job": [("任务", 790, 185), ("批量编辑", 1030, 185), ("视觉异常", 790, 385),
                    ("安全恢复", 1030, 385)],
            "alignment": [("单板原点", 790, 185), ("封装检查", 1030, 185),
                          ("飞达高亮", 790, 385), ("工具头同步", 1030, 385)],
            "autosave": [("任务", 790, 185), ("临时副本", 1030, 185),
                         ("单板 / 拼版", 790, 385), ("异常恢复", 1030, 385)],
            "backup": [("共享数据", 790, 185), ("选择性备份", 1030, 185),
                       ("升级安装", 790, 385), ("冲突检测", 1030, 385)],
        }.get(kind, [])
        for label, x, y in nodes:
            rounded(draw, (x - 80, y - 42, x + 80, y + 42), 18, "#30393d", "#718a95", 2)
            bbox = draw.textbbox((0, 0), label, font=font(20, True))
            draw.text((x - (bbox[2] - bbox[0]) / 2, y - 14), label,
                      font=font(20, True), fill="#e5ecef")
        if nodes:
            draw.line((870, 185, 950, 185), fill="#718a95", width=4)
            draw.line((870, 385, 950, 385), fill="#718a95", width=4)
            draw.line((790, 228, 790, 342), fill="#718a95", width=4)
            draw.line((1030, 228, 1030, 342), fill="#718a95", width=4)


def make_slide(scene, index):
    image = base_image()
    draw = ImageDraw.Draw(image)
    if scene["kind"] in ("cover", "end"):
        logo = Image.open(LOGO).convert("RGBA")
        logo.thumbnail((210, 210), Image.Resampling.LANCZOS)
        image.paste(logo, ((W - logo.width) // 2, 105), logo)
        eyebrow = scene["eyebrow"]
        eb = draw.textbbox((0, 0), eyebrow, font=font(19, True))
        draw.text(((W - (eb[2] - eb[0])) / 2, 332), eyebrow, font=font(19, True),
                  fill="#91aab5")
        title_size = 48 if scene["kind"] == "cover" else 42
        tb = draw.textbbox((0, 0), scene["title"], font=font(title_size, True))
        draw.text(((W - (tb[2] - tb[0])) / 2, 382), scene["title"],
                  font=font(title_size, True), fill="#eef2f3")
        sb = draw.textbbox((0, 0), scene["subtitle"], font=font(24))
        draw.text(((W - (sb[2] - sb[0])) / 2, 462), scene["subtitle"],
                  font=font(24), fill="#aab5ba")
        if scene["kind"] == "cover":
            rounded(draw, (466, 540, 814, 592), 26, "#35434a", "#718a95")
            draw.text((510, 553), "全新 Pro 功能速览", font=font(20, True),
                      fill="#e7edef")
        else:
            draw.text((524, 550), "ZXYHtech", font=font(18, True), fill="#718a95")
        return image

    paste_logo(image)
    draw.text((132, 43), "OPENPNP PRO", font=font(20, True), fill="#e8edef")
    draw.text((132, 70), "2.6P2 · PRO FEATURES", font=font(13, True), fill="#718a95")
    draw.text((58, 131), scene["eyebrow"], font=font(16, True), fill="#91aab5")
    for line_no, line in enumerate(text_wrap(draw, scene["title"], font(37, True), 570)):
        draw.text((58, 168 + line_no * 49), line, font=font(37, True), fill="#f1f4f5")
    sub_y = 168 + len(text_wrap(draw, scene["title"], font(37, True), 570)) * 49 + 10
    draw.text((58, sub_y), scene["subtitle"], font=font(18), fill="#9eabb0")
    bullet_y = sub_y + 55
    for bullet in scene["bullets"]:
        lines = text_wrap(draw, bullet, font(18), 570)
        draw.ellipse((61, bullet_y + 9, 69, bullet_y + 17), fill="#8299a3")
        for line_index, line in enumerate(lines):
            draw.text((84, bullet_y + line_index * 29), line, font=font(18),
                      fill="#d4dcdf")
        bullet_y += len(lines) * 29 + 13
    chips = scene.get("chips", [])
    chip_x = 58
    for chip in chips:
        width = draw.textbbox((0, 0), chip, font=font(14, True))[2] + 34
        rounded(draw, (chip_x, 635, chip_x + width, 674), 18, "#303a3f", "#52636b")
        draw.text((chip_x + 17, 645), chip, font=font(14, True), fill="#b9c8ce")
        chip_x += width + 12
    if scene["kind"] == "screen":
        screen_panel(image, scene.get("highlight"))
    else:
        draw_diagram(image, scene["kind"])
    draw.text((1188, 660), f"{index:02d}", font=font(20, True), fill="#65747b")
    return image


def make_audio(total_seconds):
    rate = 44100
    count = int(total_seconds * rate)
    t = np.arange(count, dtype=np.float64) / rate
    audio = np.zeros(count, dtype=np.float64)
    chord_sets = [
        (110.0, 164.81, 220.0),
        (98.0, 146.83, 196.0),
        (123.47, 185.0, 246.94),
        (110.0, 146.83, 220.0),
    ]
    bar = 8.0
    for start in np.arange(0, total_seconds, bar):
        chord = chord_sets[int(start / bar) % len(chord_sets)]
        mask = (t >= start) & (t < min(start + bar, total_seconds))
        local = t[mask] - start
        envelope = np.minimum(local / 1.2, 1.0) * np.minimum((bar - local) / 1.5, 1.0)
        for harmonic, frequency in enumerate(chord):
            audio[mask] += (0.030 / (harmonic + 1)) * np.sin(2 * np.pi * frequency * local)
        audio[mask] *= np.clip(envelope, 0, 1)
    beat = (np.sin(2 * np.pi * 1.25 * t) > 0.94).astype(float)
    audio += 0.008 * beat * np.sin(2 * np.pi * 55 * t)
    fade = np.minimum(t / 2.0, 1.0) * np.minimum((total_seconds - t) / 3.0, 1.0)
    audio *= np.clip(fade, 0, 1)
    pcm = np.int16(np.clip(audio, -1, 1) * 32767)
    path = OUT / "ambient.wav"
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(rate)
        wav.writeframes(pcm.tobytes())
    return path


def run(command):
    subprocess.run([str(item) for item in command], check=True)


def main():
    if not FFMPEG.exists():
        raise FileNotFoundError(f"ffmpeg not found: {FFMPEG}")
    SLIDES.mkdir(parents=True, exist_ok=True)
    CLIPS.mkdir(parents=True, exist_ok=True)
    clip_paths = []
    for index, scene in enumerate(SCENES):
        slide = SLIDES / f"{index:02d}.bmp"
        make_slide(scene, index).save(slide)
        clip = CLIPS / f"{index:02d}.mp4"
        frames = max(1, round(scene["duration"] * FPS))
        fade_out = max(0.0, scene["duration"] - 0.35)
        vf = (
            f"zoompan=z='min(zoom+0.00012,1.025)':d={frames}:s={W}x{H}:fps={FPS},"
            f"fade=t=in:st=0:d=0.3,fade=t=out:st={fade_out:.2f}:d=0.35,"
            "format=yuv420p"
        )
        run([
            FFMPEG, "-y", "-loop", "1", "-i", slide,
            "-vf", vf, "-frames:v", str(frames),
            "-c:v", "mpeg4", "-q:v", "2",
            "-pix_fmt", "yuv420p", clip,
        ])
        clip_paths.append(clip)
    concat_file = OUT / "concat.txt"
    concat_file.write_text(
        "".join(f"file '{path.as_posix()}'\n" for path in clip_paths),
        encoding="utf-8",
    )
    silent_video = OUT / "silent.mp4"
    run([FFMPEG, "-y", "-f", "concat", "-safe", "0", "-i", concat_file,
         "-c", "copy", silent_video])
    total = sum(scene["duration"] for scene in SCENES)
    audio = make_audio(total)
    run([
        FFMPEG, "-y", "-i", silent_video, "-i", audio,
        "-c:v", "copy", "-c:a", "aac", "-b:a", "160k",
        "-shortest", "-movflags", "+faststart",
        "-metadata", "title=OpenPnP Pro 2.6P2 功能介绍",
        FINAL,
    ])
    print(FINAL)


if __name__ == "__main__":
    main()

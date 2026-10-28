#!/usr/bin/env python3
"""
主题配色检查（第 331 版加）：跟着主题走的界面里，不许写死深色底、深色遮罩、浅色字。
写死的颜色换到白色、渐变这些浅色主题就会「深底深字」或「浅底浅字」看不清。
该用的：遮罩 Skin.scrim(a)，菜单 / 小面板 Skin.pop，抽屉 Skin.sheet，提示条 Skin.toast + Skin.toastInk，
聊天卡片 Skin.cardFill(a)，薄层 veil(a)，字 Page.ink / Page.muted，点缀色 Skin.accent，按钮上的字 Skin.onAccent，红 Skin.danger。
一直深色的地方（看视频、浏览网页、下载页、看大图、作品全屏）列在 ALWAYS_DARK 里，不查。
压在图片上的小标签（带 Color.Black.copy 的那一行）不算。
用法：python3 android/theme-check.py   有问题就列出来，退出码 1。
"""
import glob, os, re, sys

SRC = os.path.join(os.path.dirname(__file__), "app/src/main/java/com/fuguang/lueying")
ALWAYS_DARK = {
    "FullPlayer.kt", "InBrowser.kt", "PhotoBars.kt", "PhotoInk.kt", "PhotoLayer.kt", "WorkViewer.kt", "VideoTouch.kt",
    "BrowserHome.kt", "NoteImages.kt", "DownloadUi.kt", "DownloadTabs.kt", "DlFail.kt", "DlPlay.kt", "AvatarCrop.kt",
    "IconCrop.kt", "FullGesture.kt", "ClipPlay.kt",
    # 定义颜色的地方、主题小样、导出文件
    "Skin.kt", "Theme.kt", "ThemePage.kt", "ThemeGrad.kt", "OfficeExport.kt", "Illust.kt", "Background.kt",
}
COLOR = re.compile(r'rgba\((\d+), (\d+), (\d+), [^)]+\)|Color\(0x([0-9A-Fa-f]{2})([0-9A-Fa-f]{6})\)|css\("#([0-9a-fA-F]{6})"\)')
SURFACE = re.compile(r'background\(|fill =|pressedFill =|glass\(|frost\(')
SKIP = re.compile(r'listOf\(|mapOf\(|hsl|Brush\.|draw(Circle|Rect|Path|Line|Oval)\(|Shadow\(|Color\.Black\.copy|Color\(0x80|DrawnCheck|0xFF[0-9A-F]{6}L')


def lum(h):
    r, g, b = (int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))
    f = lambda c: c / 12.92 if c <= .04045 else ((c + .055) / 1.055) ** 2.4
    return .2126 * f(r) + .7152 * f(g) + .0722 * f(b)


bad = []
for path in sorted(glob.glob(os.path.join(SRC, "*.kt"))):
    name = os.path.basename(path)
    if name in ALWAYS_DARK:
        continue
    for n, line in enumerate(open(path, encoding="utf-8"), 1):
        if SKIP.search(line):
            continue
        if re.search(r"Page\.(INK|MUTED|LINE)\b", line):
            bad.append(f"{name}:{n} 写死浅色字 / 线（换 Page.ink / muted / line）")
        if SURFACE.search(line):
            for m in COLOR.finditer(line):
                h = "%02x%02x%02x" % tuple(int(x) for x in m.group(1, 2, 3)) if m.group(1) else (m.group(5) or m.group(6))
                if lum(h) < .06:
                    bad.append(f"{name}:{n} 写死深色底 #{h}（换 Skin.scrim / pop / sheet / toast / cardFill）")
                elif lum(h) > .85 and "Color.White" not in line:
                    bad.append(f"{name}:{n} 写死浅色底 #{h}（换 veil / Skin.pop）")

print("\n".join(bad) if bad else "主题配色检查：没发现写死的颜色")
sys.exit(1 if bad else 0)

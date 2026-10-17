"""把项目打包成免安装的 Windows 程序（PyInstaller）。

    python tools/build_exe.py              # 目录版（默认，启动快）→ dist/RecipeBalancer/
    python tools/build_exe.py --onefile    # 单文件版 → dist/RecipeBalancer.exe

打包结果不需要目标机器装 Python：tkinter / numpy / scipy / networkx / pyvis
全部随包分发。首次构建会自动生成图标 ``assets/app.ico``（纯代码绘制，无外部素材）。

为什么默认用目录版：单文件 exe 每次启动都要把 ~150 MB 解压到临时目录，
GUI 冷启动要好几秒；目录版只有首次拷贝成本，之后 1~2 秒即可打开。
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENTRY = ROOT / "src" / "recipe_balancer" / "__main__.py"
ICON = ROOT / "assets" / "app.ico"
DEFAULT_NAME = "RecipeBalancer"

USAGE_TXT = """配方树自动配平（RecipeBalancer）
================================

运行
----
双击 {exe} 打开界面。

- 求解结果的 HTML 流程图、导出的场景 JSON 默认都保存在**本文件所在目录**。
- 想换位置：界面左下「设置…」里指定「可视化输出目录」/「导出默认目录」，
  设置保存在 %USERPROFILE%\\.recipe_balancer\\config.json。

自检
----
当程序打不开或生成不了流程图时，在该目录执行：

    {exe} --selftest

会在本目录生成 selftest.log 并弹窗告知「求解 / 报告 / 可视化」三项是否通过。

说明
----
本程序自带 Python 运行时与全部依赖，无需安装 Python、无需联网。
"""


def ensure_icon() -> Path | None:
    """没有图标就用 PIL 画一个（深色圆角底 + 三节点流程图）。"""
    if ICON.exists():
        return ICON
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        return None

    size = 256
    bg = "#161a21"
    accent = "#5b8dff"
    edge = "#7f93b8"

    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((8, 8, size - 8, size - 8), radius=52, fill=bg)

    nodes = {"a": (78, 84), "b": (182, 96), "c": (128, 186)}
    for left, right in (("a", "b"), ("b", "c"), ("c", "a")):
        draw.line([nodes[left], nodes[right]], fill=edge, width=16)
    radius = 24
    for cx, cy in nodes.values():
        draw.ellipse(
            (cx - radius, cy - radius, cx + radius, cy + radius), fill=accent
        )

    ICON.parent.mkdir(parents=True, exist_ok=True)
    image.save(ICON, format="ICO", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print(f"[icon] 已生成 {ICON}")
    return ICON


def project_version() -> str:
    """从 pyproject.toml 读版本号（不引第三方 tomllib 依赖）。"""
    text = (ROOT / "pyproject.toml").read_text(encoding="utf-8")
    match = re.search(r'^version\s*=\s*"([^"]+)"', text, re.MULTILINE)
    return match.group(1) if match else "0.0.0"


def write_version_file(version: str) -> Path:
    """生成 PyInstaller 的版本资源，让 exe 属性页显示版本号/产品名。"""
    numbers = [int(part) for part in re.findall(r"\d+", version)][:4]
    numbers += [0] * (4 - len(numbers))
    nums = tuple(numbers)
    text = f"""# -*- coding: utf-8 -*-
VSVersionInfo(
  ffi=FixedFileInfo(filevers={nums}, prodvers={nums}, mask=0x3f, flags=0x0,
                    OS=0x40004, fileType=0x1, subtype=0x0, date=(0, 0)),
  kids=[
    StringFileInfo([
      StringTable('080404B0', [
        StringStruct('CompanyName', 'JesseM1024'),
        StringStruct('FileDescription', 'MC 配方树自动配平（RecipeBalancer）'),
        StringStruct('FileVersion', '{version}'),
        StringStruct('InternalName', 'RecipeBalancer'),
        StringStruct('OriginalFilename', 'RecipeBalancer.exe'),
        StringStruct('ProductName', 'RecipeBalancer'),
        StringStruct('ProductVersion', '{version}')
      ])
    ]),
    VarFileInfo([VarStruct('Translation', [2052, 1200])])
  ]
)
"""
    path = ROOT / "build" / "version_info.txt"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


def build(onefile: bool, name: str, console: bool, clean: bool) -> int:
    try:
        import PyInstaller  # noqa: F401
    except ImportError:
        print("缺少 PyInstaller，请先安装：\n  pip install pyinstaller", file=sys.stderr)
        return 2

    icon = ensure_icon()
    version = project_version()
    version_file = write_version_file(version)
    dist = ROOT / "dist"
    args = [
        sys.executable,
        "-m",
        "PyInstaller",
        str(ENTRY),
        "--name",
        name,
        "--noconfirm",
        "--distpath",
        str(dist),
        "--workpath",
        str(ROOT / "build"),
        "--specpath",
        str(ROOT / "build"),
        "--paths",
        str(ROOT / "src"),
        # pyvis 的 HTML 模板 / 内联 JS 是包数据，必须显式收集
        "--collect-data",
        "pyvis",
        # 清掉用不到的重型可选依赖，减小体积
        "--exclude-module",
        "matplotlib",
        "--exclude-module",
        "pandas",
        "--exclude-module",
        "IPython",
        "--exclude-module",
        "pytest",
        "--exclude-module",
        "PyQt5",
        "--exclude-module",
        "PIL.ImageQt",
    ]
    args.append("--console" if console else "--windowed")
    if onefile:
        args.append("--onefile")
    if clean:
        args.append("--clean")
    if icon is not None:
        # exe 图标 + 运行时窗口图标（gui._set_window_icon 从 assets/app.ico 取）
        args += ["--icon", str(icon), "--add-data", f"{icon}{os.pathsep}assets"]
    args += ["--version-file", str(version_file)]

    print(f"版本: {version}")
    print("执行:", " ".join(args))
    result = subprocess.run(args, cwd=str(ROOT))
    if result.returncode != 0:
        return result.returncode

    # 目标目录里放一份使用说明（目录版放在 exe 旁边）
    target_dir = dist if onefile else dist / name
    exe_name = f"{name}.exe"
    if target_dir.is_dir():
        (target_dir / "使用说明.txt").write_text(
            USAGE_TXT.format(exe=exe_name), encoding="utf-8"
        )

    # 报告体积
    exe = target_dir / exe_name
    if exe.exists():
        if onefile:
            size_mb = exe.stat().st_size / 1024 / 1024
            print(f"\n[ok] {exe}（单文件 {size_mb:.0f} MB）")
        else:
            total = sum(p.stat().st_size for p in target_dir.rglob("*") if p.is_file())
            print(f"\n[ok] {exe}")
            print(f"[ok] 目录体积约 {total / 1024 / 1024:.0f} MB")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="打包为免安装的 Windows 程序")
    parser.add_argument("--onefile", action="store_true", help="打成单个 exe（启动较慢）")
    parser.add_argument("--name", default=DEFAULT_NAME, help="可执行文件名")
    parser.add_argument("--console", action="store_true", help="保留控制台窗口（调试用）")
    parser.add_argument("--no-clean", action="store_true", help="复用上次的构建缓存")
    args = parser.parse_args()

    if not ENTRY.exists():
        print(f"找不到入口脚本: {ENTRY}", file=sys.stderr)
        return 2
    return build(args.onefile, args.name, args.console, not args.no_clean)


if __name__ == "__main__":
    raise SystemExit(main())

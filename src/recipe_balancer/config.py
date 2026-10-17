"""应用配置：主题、可视化输出目录、默认导出目录。

配置文件位置：``~/.recipe_balancer/config.json``（UTF-8）。
读取时与默认值合并，损坏或缺失时静默回退默认值。

默认输出目录见 :func:`app_dir`：打包成 exe 后是**可执行文件所在目录**，
源码运行时是当前工作目录——用户不必再去找临时目录里生成的文件。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any, Dict

DEFAULT_CONFIG: Dict[str, Any] = {
    "theme": "light",
    "html_output_dir": "",
    "export_dir": "",
}


def app_dir() -> Path:
    """程序所在目录：冻结（PyInstaller 单文件/单目录）后为 exe 目录，否则为 cwd。"""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path.cwd()


def config_path() -> Path:
    return Path.home() / ".recipe_balancer" / "config.json"


def load_config(path: Path | None = None) -> Dict[str, Any]:
    target = Path(path) if path is not None else config_path()
    data = dict(DEFAULT_CONFIG)
    try:
        with open(target, "r", encoding="utf-8") as f:
            loaded = json.load(f)
        if isinstance(loaded, dict):
            data.update({k: v for k, v in loaded.items() if k in DEFAULT_CONFIG})
    except (OSError, ValueError):
        pass
    if data.get("theme") not in ("light", "dark"):
        data["theme"] = "light"
    return data


def save_config(config: Dict[str, Any], path: Path | None = None) -> bool:
    """写入配置，返回是否成功（目录不可写时返回 ``False``，不抛异常）。"""
    target = Path(path) if path is not None else config_path()
    payload = {k: config.get(k, v) for k, v in DEFAULT_CONFIG.items()}
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        with open(target, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
    except OSError:
        return False
    return True

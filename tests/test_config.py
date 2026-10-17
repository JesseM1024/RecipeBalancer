"""配置模块测试（主题 / 输出目录持久化）。"""

from __future__ import annotations

from recipe_balancer.config import DEFAULT_CONFIG, load_config, save_config


def test_config_defaults(tmp_path):
    path = tmp_path / "config.json"
    cfg = load_config(path)
    assert cfg == DEFAULT_CONFIG


def test_config_roundtrip(tmp_path):
    path = tmp_path / "sub" / "config.json"
    save_config(
        {"theme": "dark", "html_output_dir": "D:/out", "export_dir": "D:/export"},
        path,
    )
    cfg = load_config(path)
    assert cfg["theme"] == "dark"
    assert cfg["html_output_dir"] == "D:/out"
    assert cfg["export_dir"] == "D:/export"


def test_config_ignores_broken_file(tmp_path):
    path = tmp_path / "config.json"
    path.write_text("{not json", encoding="utf-8")
    assert load_config(path) == DEFAULT_CONFIG


def test_config_rejects_unknown_theme(tmp_path):
    path = tmp_path / "config.json"
    save_config({"theme": "neon"}, path)
    assert load_config(path)["theme"] == "light"


def test_save_config_reports_failure_instead_of_raising(tmp_path):
    # 把一个文件当成目录用 -> mkdir 必然失败，函数应返回 False 而不是抛异常
    blocker = tmp_path / "blocked"
    blocker.write_text("x", encoding="utf-8")
    ok = save_config({"theme": "dark"}, blocker / "config.json")
    assert ok is False

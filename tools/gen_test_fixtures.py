"""从上游 Python 项目导出测试夹具（一次性工具，结果提交进仓库）。

做的事：
1. 读上游 ``tests/conftest.py`` 的 ``SCENARIOS``（9 个场景），导出为
   ``src/test/resources/scenarios/<name>.json``（浮点走 ``json`` 的 repr，逐位保真）；
2. 复制 ``tests/golden/*.json`` 到 ``src/test/resources/golden/``（原样不改数字）。

为什么要用 Python 生成而不是手抄：``20 / 9``、``1 / 6 * 9.6``、``ceil(800 / 65)``
这类表达式的 double 值与十进制字面量差 1 ulp，手抄会改变 LP 的顶点选择。

用法（上游 venv）：
    .venv\\Scripts\\python.exe tools\\gen_test_fixtures.py
"""

from __future__ import annotations

import json
import shutil
import sys
import types
from pathlib import Path

UPSTREAM = Path(r"C:\Users\JesseTech\Desktop\JesseTech\code\py\配方树自动配平")
REPO = Path(__file__).resolve().parents[1]
OUT = REPO / "src" / "test" / "resources"


def load_scenarios() -> dict:
    """执行上游 conftest（注入 pytest 替身，避免依赖上游 venv 的 pytest）。"""
    pytest_stub = types.ModuleType("pytest")

    def fixture(*args, **kwargs):
        if args and callable(args[0]):
            return args[0]
        return lambda fn: fn

    pytest_stub.fixture = fixture
    sys.modules.setdefault("pytest", pytest_stub)

    source = (UPSTREAM / "tests" / "conftest.py").read_text(encoding="utf-8")
    namespace: dict = {}
    exec(compile(source, str(UPSTREAM / "tests" / "conftest.py"), "exec"), namespace)
    return namespace["SCENARIOS"]


def jsonable(value):
    """set → 排序 list；dict/list 递归；其余原样（float 由 json 用 repr 输出）。"""
    if isinstance(value, (set, frozenset)):
        return sorted(value)
    if isinstance(value, dict):
        return {k: jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [jsonable(v) for v in value]
    return value


def main() -> int:
    scenarios_dir = OUT / "scenarios"
    golden_dir = OUT / "golden"
    scenarios_dir.mkdir(parents=True, exist_ok=True)
    golden_dir.mkdir(parents=True, exist_ok=True)

    scenarios = load_scenarios()
    for name, scenario in scenarios.items():
        data = {
            "recipes": jsonable(scenario["recipes"]),
            "target_products": jsonable(scenario["target"]),
            "options": jsonable(scenario["options"]),
        }
        path = scenarios_dir / f"{name}.json"
        path.write_text(
            json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        # 回读自检：浮点必须完全相等（保证没有精度丢失）
        check = json.loads(path.read_text(encoding="utf-8"))
        assert check["recipes"] == data["recipes"], name
        print(f"scenario  {name:<16} -> {path.relative_to(REPO)}")

    golden_src = UPSTREAM / "tests" / "golden"
    for path in sorted(golden_src.glob("*.json")):
        shutil.copyfile(path, golden_dir / path.name)
        print(f"golden    {path.name:<20} -> {(golden_dir / path.name).relative_to(REPO)}")

    missing = set(scenarios) - {p.stem for p in scenarios_dir.glob("*.json")}
    assert not missing, f"缺少场景: {missing}"
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

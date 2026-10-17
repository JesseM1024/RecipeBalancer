"""从 notebook 原函数生成数值金标准（对拍基准）。

用法::

    python tools/snapshot_golden.py <notebook.ipynb>

产出 ``tests/golden/<scenario>.json``，供 ``tests/test_golden.py`` 逐项比对。
仅在设计变更后需要重新生成；生成后应人工核对关键场景的数值合理性。
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tests"))

from conftest import SCENARIOS  # noqa: E402


def _clean(mapping) -> dict:
    return {k: float(v) for k, v in sorted(mapping.items())}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("notebook", help="《配方树自动配平》notebook 路径")
    args = parser.parse_args()

    notebook = json.loads(Path(args.notebook).read_text(encoding="utf-8"))
    source = "".join(notebook["cells"][1]["source"])
    namespace: dict = {}
    exec(compile(source, "notebook_cell1", "exec"), namespace)
    solve = namespace["solve_production"]

    out_dir = ROOT / "tests" / "golden"
    out_dir.mkdir(parents=True, exist_ok=True)

    for name, scenario in SCENARIOS.items():
        with contextlib.redirect_stdout(io.StringIO()):
            sol = solve(scenario["recipes"], scenario["target"], **scenario["options"])
        data = {
            "success": bool(sol.success),
            "recipe_rates": [float(v) for v in sol.recipe_rates],
            "external_inputs": _clean(sol.external_inputs),
            "net_flow": _clean(sol.net_flow),
            "final_outputs": _clean(sol.final_outputs),
            "equipment_usage": _clean(sol.equipment_usage),
            "total_machine_count": int(sol.total_machine_count),
            "inferred_raw_materials": sorted(sol.inferred_raw_materials),
            "inferred_initial_inventory": _clean(sol.inferred_initial_inventory),
        }
        path = out_dir / f"{name}.json"
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"wrote {path.relative_to(ROOT)}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

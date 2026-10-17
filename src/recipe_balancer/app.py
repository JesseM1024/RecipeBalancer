"""程序入口（源码运行与打包后的 exe 共用）。

- 不带参数：打开图形界面（双击 exe 的默认行为）
- ``--selftest``：打包自检，见 :mod:`recipe_balancer.selftest`
- 其它参数：转交命令行 ``solve ...``（源码运行时可用；exe 为 windowed，
  没有 stdout，脚本化调用请用 ``python -m recipe_balancer.cli``）
"""

from __future__ import annotations

import sys
from typing import List, Optional

SELFTEST_FLAGS = {"--selftest", "-selftest", "selftest"}


def main(argv: Optional[List[str]] = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if args and args[0] in SELFTEST_FLAGS:
        from .selftest import run_selftest

        quiet = any(flag in ("--quiet", "-q") for flag in args[1:])
        return run_selftest(notify=not quiet)
    if args:
        from .cli import main as cli_main

        return cli_main(args)

    from .gui import main as gui_main

    return gui_main()


if __name__ == "__main__":
    raise SystemExit(main())

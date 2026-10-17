# 配方树自动配平（recipe-balancer）

MC 整合包配方树的**稳态物料衡算求解器**：把配方集合转成化学计量矩阵，用线性规划（scipy / HiGHS）求各配方速率，使机器总量最小、物流自洽。忠实迁移自 notebook《配方树自动配平》，架构与算法分析见 [ANALYSIS.md](ANALYSIS.md)。

## 能力

- **主 LP**：变量 = [配方速率, 外部输入]，约束「每物品净产出 ≥ 目标 − 库存」，目标 = 最小化机器并行总量（或配方活动总量），外部输入 big-M 惩罚
- **三个自动检测器**：原料（无人能产）、自增殖种子（1→2 型配方）、循环亏损（SCC 分解 + 每环一个子 LP）
- **完美循环启动投料**：把每个循环压缩成「最少投哪些料、各投多少」——外部输入不算循环成员，
  只做结构判定、不解析物品名（GT 元素名与其它整合包的幻想物质同样适用）
- **输出**：文本报告（配方方案 / 汇总 / 设备统计）、pyvis 交互流程图、JSON 解
- **图形界面**：tkinter 窗口，配方增删改、目标设定、结果查看、一键打开浏览器可视化；
  **明/暗双主题**、高 DPI 感知（150% 缩放下文字不糊）、可视化与导出路径可配置
- **CLI + Python API**

## 安装

```bash
# 建议使用虚拟环境；国内网络可用镜像
pip install -e . --no-build-isolation \
  -i https://mirrors.aliyun.com/pypi/simple/ \
  --trusted-host mirrors.aliyun.com

# 可选：可视化依赖
pip install pyvis
```

## 使用

### Python API（与 notebook 完全同构）

```python
from recipe_balancer import solve_production, print_solution, draw_production_graph_pyvis

recipe_list = [
    {"sourceList": {"圆石": 1}, "productList": {"沙砾": 1}, "time": 20/9, "equType": "粉碎"},
    {"sourceList": {"沙砾": 1}, "productList": {"钻石": 0.1, "铁锭": 0.2}, "time": 10, "equType": "筛子"},
]
target_products = {"铁锭": 1}

solution = solve_production(
    recipe_list=recipe_list,
    target_products=target_products,
    raw_materials={"圆石"},          # 无限外部输入
    objective="machine",             # 或 "recipes"
)
print_solution(solution, recipe_list)
draw_production_graph_pyvis(solution, recipe_list, target_products,
                            output_file="production_graph.html")
```

参数说明：

| 参数 | 默认 | 语义 |
|---|---|---|
| `raw_materials` | 空 | 用户声明的无限外部输入 |
| `initial_inventory` | 空 | 初始库存（当前为催化式语义，见 ANALYSIS.md P0-A） |
| `disabled_recipes` | 空 | 禁用配方的**下标**集合 |
| `allowed_external_cyc_inputs` | 空 | 循环亏损白名单；空 = 不限制 |
| `objective` | `"machine"` | 最小化机器并行总量 / 配方活动总量 |
| `auto_detect_raw` | `True` | 自动检测原料 |
| `auto_detect_seed` | `True` | 自动检测自增殖种子 |
| `auto_bootstrap` | `False` | 完美循环检测（返回最小启动投料集合 `{物品: 投料量}`） |

### CLI

```bash
recipe-balancer solve examples/simple_chain.json --draw graph.html --json solution.json
recipe-balancer solve examples/mek_fission.json --objective recipes
```

### 图形界面

tkinter 需要官方 Python 构建（WorkBuddy 托管环境无 `_tkinter`）。项目根目录自带
基于系统 Python 3.10 的 `.venv`（含 numpy/scipy/networkx/pyvis）：

```bat
cd /d "C:\Users\JesseTech\Desktop\JesseTech\code\py\配方树自动配平"
.venv\Scripts\python.exe -m recipe_balancer.gui
```

> **如果报 `No module named 'recipe_balancer'`**：说明该 venv 里还没装本项目。两种办法：
> ① `.venv\Scripts\pip install -e . --no-build-isolation`；
> ② 不安装、直接指定源码目录：先 `$env:PYTHONPATH="src"`，再运行上面的命令。
>
> 自检：`.venv\Scripts\python.exe -c "import recipe_balancer; print(recipe_balancer.__file__)"`。

界面布局（左侧编辑 / 右侧结果）：

- **顶部「目标与选项」**：目标产物 + 速率 + 单位（t / 秒 / 分 / 时）、
  目标列表（最多 3 行，超出滚动）、求解目标、循环启动库存检测、**夜间模式**开关；
- **外部原料 / 循环亏损表各占一行**；
- **筛选框**：按物品名 / 设备名过滤配方卡片；
- **配方卡片（可滚动）**：原料 / 产物各有一组「名称 + 数量」输入框，**回车即添加**；
  已有条目**直接在行内改名 / 改数量**（实时生效），✕ 删除；
  机器类型与耗时直接编辑，**每张卡片的耗时单位独立可选**；
- **底部**：新增配方 / 求解 / 导入 JSON / 导出 JSON / **设置…**；
- **右侧**：实时文本结果 + 「启动浏览器查看可视化」。

配色与高分屏：

- 两套配色（`PALETTES`：浅色 `#f3f5f8` / 深色 `#14171d`），切换时重建控件并重刷
  ttk `clam` 样式表；标题栏之外的控件、滚动条、下拉列表全部跟随主题；
- 进程启动即调用 `SetProcessDpiAwareness`，并按 `winfo_fpixels("1i")` 设置
  `tk scaling` —— Windows 150% 缩放下不再由系统做位图放大，**文字按物理像素渲染**；
- 窗口初始尺寸按 DPI 放大后仍**受屏幕限制**（`min(设计值, 屏幕×0.94/0.92)`），
  避免 150% 下 1280×880 逻辑尺寸变成 1920×1320 物理像素而超出屏幕。

设置（`设置…` 按钮，持久化到 `~/.recipe_balancer/config.json`）：

| 键 | 含义 | 留空时 |
|---|---|---|
| `theme` | `light` / `dark` | 浅色 |
| `html_output_dir` | 可视化 HTML 输出目录 | 系统临时目录 |
| `export_dir` | 「导出 JSON」默认目录 | 上次使用的位置 |

交互约定：

- 所有改动**即时写入内存模型**（无需保存按钮），模型合法时 600ms 后**自动求解**；
- **时间**：每个耗时/速率字段各自选择单位，录入后先**归一化为 tick**
  （`1 t = 0.05 s`），再换算成求解器使用的秒 / 每秒；
- **原料允许为空**（视为无输入）；**产物为空则配方无效**；新增配方不带占位物品。

快捷键：`F5` / `Ctrl+Enter` 求解，`Ctrl+G` 可视化，`Ctrl+O` 导入，`Ctrl+S` 导出。

> 重新创建 GUI 环境：
> `python -m venv .venv` → `.venv\Scripts\pip install numpy scipy networkx pyvis pytest` →
> `.venv\Scripts\pip install -e . --no-build-isolation`

场景 JSON 格式见 [`src/recipe_balancer/io.py`](src/recipe_balancer/io.py) 模块 docstring。

> **PowerShell**：带引号的绝对路径必须加调用符 `&`，否则报
> `表达式或语句中包含意外的标记 "-m"`：
>
> ```powershell
> & "C:\Users\JesseTech\.workbuddy\binaries\python\envs\default\Scripts\python.exe" `
>   -m recipe_balancer.cli solve examples/ebf_cycle.json
> ```
>
> cmd.exe / Git Bash 下不需要 `&`。

## 项目结构

```
src/recipe_balancer/
├── models.py      # RecipeSolution + 数值常量
├── matrix.py      # 物品收集 / 化学计量矩阵 / 向量
├── detection.py   # 原料 / 自增殖种子 / 循环亏损（SCC + 子 LP）
├── solver.py      # 主 LP 装配 + solve_production 编排
├── bootstrap.py   # 完美循环检测：最小反馈顶点集 -> 最小启动投料集合
├── report.py      # 文本报告
├── visualize.py   # pyvis 交互流程图（惰性导入）
├── config.py      # 用户设置读写（~/.recipe_balancer/config.json）
├── gui.py         # tkinter 图形界面（逻辑与控件分离，可测试）
├── io.py          # 场景 JSON 加载/校验、解导出
└── cli.py         # 命令行入口
```

## 测试

```bash
pytest            # 118 项（无 tkinter 的环境自动跳过 GUI 测试）
```

四层验证：

- **数值金标准**（`tests/test_golden.py` + `tests/golden/`）：9 个场景的**完整数值输出**
  （全部配方速率、外部输入、净物流、副产物、设备统计）与 notebook 原函数逐位对拍
  （实测最大绝对差 0）。金标准由 `tools/snapshot_golden.py <notebook.ipynb>` 生成。
- **数值不变量**（`tests/test_invariants.py`）：物料守恒恒等式 `net = A·x + ext − target`、
  设备统计可由速率重算、外部输入必须落在白名单内——与具体期望值无关，任何改动都必须成立。
- **场景与单元测试**：各场景关键点位断言 + 检测器 / IO / 报告 / 可视化 / CLI。
- **界面与配置测试**（`tests/test_gui.py` / `tests/test_config.py`）：主题切换重建卡片、
  目标 3 行封顶、内联编辑写回模型、设置里的可视化输出目录真正生效、`config.json` 往返。

场景数据逐字转录自 notebook（`tests/conftest.py`）。

## 已知语义（忠实保留，待转写后优化）

- **P0-A**：`initial_inventory` 同时加入目标向量 b 与库存向量 s，在 LP 中完全抵消——库存为「催化剂」语义，对结果零影响
- **P0-B**：`detect_deficit_items` 使用全量配方列表，禁用配方仍参与依赖图
- **P0-C**：外部输入 big-M = 1e6，大规模工厂场景下尺度敏感
- **P0-D**：亏损检测的 Σd 对不同量纲物品同权（暂不修复，见 ANALYSIS.md §12 U2）

完整清单与优化顺序见 [ANALYSIS.md](ANALYSIS.md) §6 / §12。

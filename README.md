# 配方树自动配平（recipe-balancer）

MC 整合包配方树的**稳态物料衡算求解器**：把配方集合转成化学计量矩阵，用线性规划（scipy / HiGHS）求各配方速率，使机器总量最小、物流自洽。忠实迁移自 notebook《配方树自动配平》，架构与算法分析见 [ANALYSIS.md](ANALYSIS.md)。

## 能力

- **主 LP**：变量 = [配方速率, 外部输入]，约束「每物品净产出 ≥ 目标 − 库存」，目标 = 最小化机器并行总量（或配方活动总量），外部输入 big-M 惩罚
- **三个自动检测器**：原料（无人能产）、自增殖种子（1→2 型配方）、循环亏损（SCC 分解 + 每环一个子 LP）
- **完美循环启动投料**：把每个循环压缩成「最少投哪些料、各投多少」——外部输入不算循环成员，
  只做结构判定、不解析物品名（GT 元素名与其它整合包的幻想物质同样适用）
- **输出**：文本报告（配方方案 / 汇总 / 设备统计）、pyvis 交互流程图、JSON 解
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
├── bootstrap.py   # 冷启动库存模拟（待重定义，见 ANALYSIS.md §12）
├── report.py      # 文本报告
├── visualize.py   # pyvis 交互流程图（惰性导入）
├── io.py          # 场景 JSON 加载/校验、解导出
└── cli.py         # 命令行入口
```

## 测试

```bash
pytest            # 92 项
```

三层验证：

- **数值金标准**（`tests/test_golden.py` + `tests/golden/`）：9 个场景的**完整数值输出**
  （全部配方速率、外部输入、净物流、副产物、设备统计）与 notebook 原函数逐位对拍
  （实测最大绝对差 0）。金标准由 `tools/snapshot_golden.py <notebook.ipynb>` 生成。
- **数值不变量**（`tests/test_invariants.py`）：物料守恒恒等式 `net = A·x + ext − target`、
  设备统计可由速率重算、外部输入必须落在白名单内——与具体期望值无关，任何改动都必须成立。
- **场景与单元测试**：各场景关键点位断言 + 检测器 / IO / 报告 / 可视化 / CLI。

场景数据逐字转录自 notebook（`tests/conftest.py`）。

## 已知语义（忠实保留，待转写后优化）

- **P0-A**：`initial_inventory` 同时加入目标向量 b 与库存向量 s，在 LP 中完全抵消——库存为「催化剂」语义，对结果零影响
- **P0-B**：`detect_deficit_items` 使用全量配方列表，禁用配方仍参与依赖图
- **P0-C**：外部输入 big-M = 1e6，大规模工厂场景下尺度敏感
- **P0-D**：亏损检测的 Σd 对不同量纲物品同权（暂不修复，见 ANALYSIS.md §12 U2）

完整清单与优化顺序见 [ANALYSIS.md](ANALYSIS.md) §6 / §12。

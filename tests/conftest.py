"""9 个测试场景，数据逐字转录自 notebook cell-4/6/8/10/12/14/16/18/20。

数值保留 Python 表达式（20/9、math.ceil(800/65) 等），
保证与 notebook 运行时的浮点值完全一致。
"""

from __future__ import annotations

import math

import pytest

SCENARIOS = {
    # cell-4: 简单链（造石 -> 筛 -> 装配）
    "simple": {
        "recipes": [
            {"sourceList": {"空": 1}, "time": 5, "equType": "造石机",
             "productList": {"圆石": 1}},
            {"sourceList": {"圆石": 1}, "time": 20 / 9, "equType": "粉碎",
             "productList": {"沙砾": 1}},
            {"sourceList": {"沙砾": 1}, "time": 10, "equType": "筛子",
             "productList": {"钻石": 1 * 0.1, "铁锭": 1 * 0.2, "金锭": 1 * 0.2, "红石": 1 * 0.3}},
            {"sourceList": {"铁锭": 4}, "time": 2, "equType": "分子装配",
             "productList": {"铁齿轮": 1}},
            {"sourceList": {"铁齿轮": 1, "铁锭": 8}, "time": 2, "equType": "分子装配",
             "productList": {"铁质机械外壳": 1}},
        ],
        "target": {"铁质机械外壳": 64},
        "options": {"raw_materials": {"圆石"}},
    },
    # cell-6: 自增殖配方（锻造模版 1 -> 2）
    "autocatalytic": {
        "recipes": [
            {"sourceList": {"锻造模版": 1, "钻石": 7, "地狱岩": 1}, "time": 2,
             "equType": "分子装配", "productList": {"锻造模版": 2}},
            {"sourceList": {"沙砾": 1}, "time": 10, "equType": "筛子",
             "productList": {"钻石": 0.1, "铁锭": 0.2, "金锭": 0.2, "红石": 0.3}},
            {"sourceList": {"圆石": 1}, "time": 20 / 9, "equType": "粉碎",
             "productList": {"沙砾": 1}},
            {"sourceList": {"空": 1}, "time": 5, "equType": "造石机",
             "productList": {"圆石": 1}},
            {"sourceList": {"圆石": 1}, "time": 20, "equType": "熔岩炉",
             "productList": {"熔岩": 1000}},
            {"sourceList": {"熔岩": 500}, "time": 10, "equType": "冷却器",
             "productList": {"地狱岩": 1}},
        ],
        "target": {"锻造模版": 1 / 20},
        "options": {},
    },
    # cell-8: 自增殖 + 多物品产出 + 封包产物
    "multi_product": {
        "recipes": [
            {"sourceList": {"锻造模版": 1, "钻石": 7, "地狱岩": 1}, "time": 2,
             "equType": "分子装配", "productList": {"锻造模版": 2}},
            {"sourceList": {"沙砾": 1}, "time": 10, "equType": "筛子",
             "productList": {"钻石": 1 * 0.1, "铁锭": 1 * 0.2, "金锭": 1 * 0.2, "红石": 1 * 0.3}},
            {"sourceList": {"圆石": 1}, "time": 20 / 9, "equType": "粉碎",
             "productList": {"沙砾": 1}},
            {"sourceList": {"空": 1}, "time": 5, "equType": "造石机",
             "productList": {"圆石": 1}},
            {"sourceList": {"圆石": 1}, "time": 20, "equType": "熔岩炉",
             "productList": {"熔岩": 1000}},
            {"sourceList": {"熔岩": 500}, "time": 10, "equType": "冷却器",
             "productList": {"地狱岩": 1}},
            {"sourceList": {"锻造模版": 1 / 20, "金锭": 1, "铁锭": 1}, "time": 1,
             "equType": "分子装配", "productList": {"封包产物": 1}},
        ],
        "target": {"锻造模版": 1 / 20, "金锭": 1, "铁锭": 2},
        "options": {},
    },
    # cell-10: EBF 中间产物循环（完美循环，无亏损）
    "ebf_cycle": {
        "recipes": [
            {"sourceList": {"TiCl4": 1e3, "Mg": 2}, "time": 5, "equType": "EBF",
             "productList": {"Ti": 1, "MgCl2": 6}},
            {"sourceList": {"C": 2, "TiO2": 1, "Cl2": 4e3}, "time": 5, "equType": "化反",
             "productList": {"TiCl4": 1e3, "CO": 2e3}},
            {"sourceList": {"CO": 1e3}, "time": 1.4, "equType": "电解",
             "productList": {"C": 1, "O2": 1e3}},
            {"sourceList": {"MgCl2": 3}, "time": 4.5 / 2, "equType": "电解",
             "productList": {"Cl2": 2e3, "Mg": 1}},
        ],
        "target": {"Ti": 0.2},
        "options": {},
    },
    # cell-12: 魔改电解（Mg 0.7）——循环物质亏损
    "deficit": {
        "recipes": [
            {"sourceList": {"TiCl4": 1e3, "Mg": 2}, "time": 5, "equType": "EBF",
             "productList": {"Ti": 1, "MgCl2": 6}},
            {"sourceList": {"C": 2, "TiO2": 1, "Cl2": 4e3}, "time": 5, "equType": "化反",
             "productList": {"TiCl4": 1e3, "CO": 1e3}},
            {"sourceList": {"CO": 1e3}, "time": 1.4, "equType": "电解",
             "productList": {"C": 1, "O2": 1e3}},
            {"sourceList": {"MgCl2": 3}, "time": 4.5 / 2, "equType": "电解",
             "productList": {"Cl2": 1e3, "Mg": 0.7}},
        ],
        "target": {"Ti": 1 / 5},
        "options": {"allowed_external_cyc_inputs": set()},
    },
    # cell-14: 超理配方，多路循环 + Cl2 白名单
    "multi_loop": {
        "recipes": [
            {"sourceList": {"TiCl4": 1e3, "Mg": 2}, "time": 5, "equType": "EBF",
             "productList": {"TiCl2": 1e3, "MgCl2": 6}},
            {"sourceList": {"TiCl2": 1e3, "Na": 2}, "time": 5, "equType": "EBF",
             "productList": {"Ti": 1, "NaCl": 6}},
            {"sourceList": {"C": 2, "TiO2": 1, "Cl2": 8e3}, "time": 5, "equType": "化反",
             "productList": {"TiCl4": 1e3, "CO": 2e3}},
            {"sourceList": {"CO": 1e3}, "time": 1.4, "equType": "电解",
             "productList": {"C": 1, "O2": 1e3}},
            {"sourceList": {"MgCl2": 3}, "time": 4.5 / 2, "equType": "电解",
             "productList": {"Cl2": 2e3, "Mg": 1}},
            {"sourceList": {"NaCl": 3}, "time": 4.5 / 2, "equType": "电解",
             "productList": {"Cl2": 1e3, "Na": 1}},
        ],
        "target": {"Ti": 0.3},
        "options": {"allowed_external_cyc_inputs": {"Cl2"}},
    },
    # cell-16: 双线配平（HDPE + SST 催化 1 -> 8）
    "dual_line": {
        "recipes": [
            {"sourceList": {"C2H4(L)": 50, "O2": 10, "SST": 1}, "time": 6,
             "equType": "化反", "productList": {"HDPE": 1}},
            {"sourceList": {"C2H4": 256}, "time": 1, "equType": "回旋液化",
             "productList": {"C2H4(L)": 256}},
            {"sourceList": {"SST": 1, "H2O": 200, "C2H4": 100}, "time": 40,
             "equType": "化反", "productList": {"SST": 8, "O2": 10}},
            {"sourceList": {"H2": 100, "H2O": 10, "BF": 2}, "time": 10,
             "equType": "化反", "productList": {"SST": 1, "C2H4": 100}},
            {"sourceList": {"H2O": 512}, "time": 1, "equType": "电解",
             "productList": {"O2": 256, "H2": 512}},
        ],
        "target": {"HDPE": 1 / 6 * 9.6},
        "options": {},
    },
    # cell-18: 新星 MEK 裂变（20 配方全链）
    "mek_fission": {
        "recipes": [
            {"sourceList": {"水": 1e4, "钋": 1e3, "氟石": 1}, "time": 10,
             "equType": "加压反应", "productList": {"钋球": 1, "用尽的核废料": 1e3}},
            {"sourceList": {"核废水": 2560}, "time": 1, "equType": "中子活化",
             "productList": {"钋": 256}},
            {"sourceList": {"裂变燃料": 1e5}, "time": 1, "equType": "液冷核聚变",
             "productList": {"核废水": 1e5}},
            {"sourceList": {"六氟化铀": 256}, "time": 1, "equType": "离心",
             "productList": {"裂变燃料": 256}},
            {"sourceList": {"氢氟酸": 256, "氧化铀": 256}, "time": 1, "equType": "化学灌注",
             "productList": {"六氟化铀": 256}},
            {"sourceList": {"铀黄饼": 1}, "time": 10, "equType": "化学氧化",
             "productList": {"氧化铀": 250}},
            {"sourceList": {"铀": 1 * 9}, "time": 20, "equType": "富集仓",
             "productList": {"铀黄饼": 2 * 9}},
            {"sourceList": {"液态氢氟酸": 256}, "time": 1, "equType": "回旋",
             "productList": {"氢氟酸": 256}},
            {"sourceList": {"氟石溶液": 333, "硫酸": 500}, "time": math.ceil(800 / 65),
             "equType": "NUC化反", "productList": {"液态氢氟酸": 1000, "硫酸钙溶液": 333}},
            {"sourceList": {"氟石": 1, "水": 1e4}, "time": math.ceil(600 / 65),
             "equType": "NUC溶解器", "productList": {"氟石溶液": 666}},
            {"sourceList": {"液态氧": 48000, "水": 32000, "硫": 32}, "time": 960,
             "equType": "化工复合", "productList": {"硫酸": 32000}},
            {"sourceList": {"三氧化硫": 256, "水蒸气": 1e3}, "time": 1, "equType": "化学灌注",
             "productList": {"硫酸MEK": 256}},
            {"sourceList": {"水": 256}, "time": 1, "equType": "回旋",
             "productList": {"水蒸气": 256}},
            {"sourceList": {"二氧化硫": 512, "氧气": 256}, "time": 1, "equType": "化学灌注",
             "productList": {"三氧化硫": 512}},
            {"sourceList": {"水": 512}, "time": 1, "equType": "电解",
             "productList": {"氧气": 256, "氢气": 512}},
            {"sourceList": {"液态二氧化硫": 256}, "time": 1, "equType": "回旋",
             "productList": {"二氧化硫": 256}},
            {"sourceList": {"熔融硫": 333, "液态氧": 500}, "time": 10 / 4,
             "equType": "化工复合", "productList": {"液态二氧化硫": 256}},
            {"sourceList": {"氧气": 256}, "time": 1, "equType": "回旋",
             "productList": {"液态氧": 256}},
            {"sourceList": {"硫": 1}, "time": 40 / 16, "equType": "高温熔化",
             "productList": {"熔融硫": 666}},
            {"sourceList": {"花岗岩": 1}, "time": math.ceil(400 / 65), "equType": "岩石粉碎",
             "productList": {"硫": 0.6}},
        ],
        "target": {"钋球": 1 / 20},
        "options": {},
    },
    # cell-20: NAST Hard UU 物质
    "uu": {
        "recipes": [
            {"sourceList": {"空晶体": 64, "反物质": 1}, "time": 50, "equType": "反质子核",
             "productList": {"UU物质": 1}},
            {"sourceList": {"废料盒": 64, "不稳定维度气体": 10000, "液态DT": 10000},
             "time": 1, "equType": "加压反应",
             "productList": {"空晶体": 1, "核废料": 1000}},
            {"sourceList": {"空": 1}, "time": 1, "equType": "加速气体收集",
             "productList": {"不稳定维度气体": 128}},
            {"sourceList": {"废料": 64 * 17, "锇气体": 200 * 17}, "time": 1, "equType": "压缩",
             "productList": {"废料盒": 1 * 17}},
            {"sourceList": {"富集锇": 5 * 15}, "time": 1, "equType": "氧化",
             "productList": {"锇气体": 1600 * 5 * 15}},
            {"sourceList": {"锇粉": 5 * 15}, "time": 1, "equType": "富集",
             "productList": {"富集锇": 5 * 15}},
            {"sourceList": {"石头": 5 * 15}, "time": 1, "equType": "回收",
             "productList": {"废料": 5 * 0.17 * 15}},
            {"sourceList": {"圆石": 1260}, "time": 1, "equType": "熔炼",
             "productList": {"石头": 1260}},
            {"sourceList": {"DT": 100000}, "time": 1, "equType": "回旋冷凝",
             "productList": {"液态DT": 100000}},
            {"sourceList": {"D": 100000, "T": 100000}, "time": 1, "equType": "灌注",
             "productList": {"DT": 200000}},
            {"sourceList": {"重水": 200000}, "time": 1, "equType": "电解",
             "productList": {"D": 200000}},
            {"sourceList": {"空": 1}, "time": 1, "equType": "高级电动泵",
             "productList": {"重水": 100}},
            {"sourceList": {"Li": 180000}, "time": 1, "equType": "中子活化",
             "productList": {"T": 180000}},
            {"sourceList": {"Li液态": 180000}, "time": 1, "equType": "回旋蒸发",
             "productList": {"Li": 180000}},
            {"sourceList": {"盐水": 21600}, "time": 1, "equType": "加速蒸馏",
             "productList": {"Li液态": 2160}},
            {"sourceList": {"水": 21600}, "time": 1, "equType": "加速蒸馏",
             "productList": {"盐水": 2160}},
        ],
        "target": {"UU物质": 1 / 500},
        "options": {},
    },
}


@pytest.fixture
def scenario(request):
    return SCENARIOS[request.param]

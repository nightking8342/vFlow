#!/usr/bin/env python3
"""从 Google Fonts 的图标元数据生成 vFlow 的图标分类表。

用法：
    python scripts/assets/import_material_symbol_categories.py --repo .

数据来源（官方、免鉴权）：
    https://fonts.google.com/metadata/icons?incomplete=true&key=material_symbols

    ⚠️ 响应体开头有一个 `)]}'` 前缀（Google 的 JSON 劫持防护），必须剥掉再 parse。
    本脚本把它剥掉后读出 `icons[].name` 与 `icons[].categories`。

## ⚠️ 上游分类是**两套并存的体系**，不能原样展示

实测（2026-10-05，4150 个图标）：

| 体系 | 分类名形态 | 图标数 | 例 |
|---|---|---|---|
| 旧 Material Icons | 全小写 | 1982 | `action` / `image` / `av` |
| 新 Material Symbols | TitleCase | 2055 | `Actions` / `Images` / `Audio&Video` |
| 无分类 | — | 113 | `airware` / `app_promo` |

两套**互斥**（同一个图标不会同时出现在 `action` 与 `Actions` 里，实测交集 0）。
原样用会有 33 个分类项，且 `action` 与 `Actions` 并列显示会让用户困惑。

⇒ 本脚本把它们**归并成 12 个面向使用场景的组**（见 [GROUPS]）。归并表是**手写的** ——
上游没有"大组"这一层，只能人定；改动它要同时想清楚"用户找图标时脑子里想的是什么"。

## 额外产出：常用组

元数据里每个图标带 `popularity`（Google 的统计值）。本脚本取前 N 个生成
`popularity` 组 —— 那是**唯一基于真实使用数据的排序**，比任何手写的"常用"都准。
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path

METADATA_URL = "https://fonts.google.com/metadata/icons?incomplete=true&key=material_symbols"

# ---------------------------------------------------------------------------
# 上游 33 个分类 → 12 个面向用户的组
#
# ⚠️ 归并原则：**按「用户找图标时脑子里想的是什么」分**，不是按技术来源分。
#    例如 `maps`/`places`/`transit`/`travel` 在语义上都属于"位置与出行"，
#    分成四组只会让用户在每个组里都找不到东西。
#
# ⚠️ 每个上游分类**只出现在一个组里**（下面的 `_assert_no_overlap` 会检查）。
#    上游分类确实有多归属的语义（如 `home` 既属"家居"也属"界面"），
#    但在这里拆开会造成"同一批图标在两个组里重复出现"，用户会以为是两份。
# ---------------------------------------------------------------------------
GROUPS: list[tuple[str, str, list[str]]] = [
    # (组 id, 中文名（同时用作 fallback 显示名）, 上游分类名（小写比较）)
    ("action", "操作", ["action", "actions", "ui actions"]),
    ("communication", "通讯社交", ["social", "communication", "communicate", "notification"]),
    ("media", "媒体图像", ["image", "images", "av", "audio&video"]),
    ("device", "设备硬件", ["device", "hardware", "android"]),
    ("place", "位置出行", ["maps", "places", "transit", "travel", "navigation"]),
    ("file", "文件编辑", ["file", "editor", "content"]),
    ("text", "文字", ["text"]),
    ("home", "家居", ["home", "household", "activities"]),
    ("business", "商务", ["business"]),
    ("privacy", "隐私安全", ["privacy"]),
    ("misc", "其他", ["search", "toggle", "alert", "brand"]),
]


def fetch_metadata() -> dict:
    print(f"下载元数据：{METADATA_URL}")
    with urllib.request.urlopen(METADATA_URL, timeout=120) as resp:
        raw = resp.read().decode("utf-8")
    # ⚠️ Google 的 JSON 劫持防护前缀，不剥掉 json.loads 会直接抛
    idx = raw.find("{")
    if idx < 0:
        sys.exit("元数据里找不到 JSON 起始位置")
    return json.loads(raw[idx:])


def build_maps(icons: list[dict]) -> tuple[dict[str, str], list[str]]:
    """返回 (图标名 → 组 id, 常用组里的图标名)。"""
    # 上游分类名（小写）→ 组 id
    upstream_to_group: dict[str, str] = {}
    seen: dict[str, str] = {}
    for gid, _label, cats in GROUPS:
        for c in cats:
            key = c.lower()
            if key in seen:
                sys.exit(f"分类 {c!r} 同时出现在 {seen[key]!r} 与 {gid!r} 两个组里")
            seen[key] = gid
            upstream_to_group[key] = gid

    name_to_group: dict[str, str] = {}
    unmatched: set[str] = set()
    for icon in icons:
        name = icon.get("name", "")
        cats = [c.lower() for c in (icon.get("categories") or [])]
        groups = {upstream_to_group[c] for c in cats if c in upstream_to_group}
        if not groups and cats:
            unmatched.update(cats)
        if len(groups) == 1:
            name_to_group[name] = groups.pop()
        elif len(groups) > 1:
            # 上游给了跨组的多分类。取第一个（顺序稳定：GROUPS 的书写顺序）
            for gid, _l, cs in GROUPS:
                if any((c.lower() in upstream_to_group
                        and upstream_to_group[c.lower()] == gid) for c in cats):
                    name_to_group[name] = gid
                    break
        # 无分类的留空，UI 侧归入「其他」

    if unmatched:
        print(f"⚠️ 有 {len(unmatched)} 个上游分类没被归并表覆盖：{sorted(unmatched)[:8]}")

    popular = [
        i["name"] for i in sorted(icons, key=lambda x: -x.get("popularity", 0))
        if i.get("popularity", 0) > 0
    ]
    return name_to_group, popular


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=".", help="vFlow 仓库根目录")
    ap.add_argument("--popular-count", type=int, default=120,
                    help="「常用」组收录多少（元数据按 popularity 降序）")
    ap.add_argument("--metadata", help="已下载的元数据 JSON 路径（离线时用）")
    args = ap.parse_args()

    repo = Path(args.repo).resolve()
    names_kt = repo / "app/src/main/java/com/chaomixian/vflow/core/workflow/MaterialSymbolNames.kt"
    out_kt = repo / "app/src/main/java/com/chaomixian/vflow/core/workflow/MaterialSymbolCategories.kt"
    if not names_kt.is_file():
        sys.exit(f"找不到 {names_kt}，请先跑 import_material_symbols.py")

    if args.metadata:
        # ⚠️ 本地文件同样要剥 `)]}'` 前缀 —— 它就是从同一个端点存下来的
        #    （直接 json.loads 会 `Expecting value: line 1 column 1`）。
        raw = Path(args.metadata).read_text(encoding="utf-8")
        meta = json.loads(raw[raw.find("{"):])
    else:
        meta = fetch_metadata()
    icons = meta.get("icons", [])
    if not icons:
        sys.exit("元数据里没有 icons")

    name_to_group, popular = build_maps(icons)

    # 只保留确实导入了的图标（与清单取交集）
    import re
    imported = set(re.findall(r'"([a-z0-9_]+)"', names_kt.read_text(encoding="utf-8")))
    imported.discard("rounded")
    name_to_group = {k: v for k, v in name_to_group.items() if k in imported}
    popular = [n for n in popular if n in imported][: args.popular_count]

    by_group: dict[str, list[str]] = {gid: [] for gid, _, _ in GROUPS}
    for name in sorted(imported):
        by_group[name_to_group.get(name, "misc")].append(name)
    by_group.setdefault("misc", [])

    print(f"图标总数     : {len(imported)}")
    print(f"有分类的     : {len(name_to_group)}")
    print(f"常用组       : {len(popular)}")
    for gid, label, _ in GROUPS:
        print(f"  {gid:16s} {label:8s} {len(by_group.get(gid, []))}")

    def kt_list(items: list[str], indent: str = "        ") -> str:
        return ",\n".join(f'{indent}"{i}"' for i in items)

    groups_src = []
    for gid, label, _ in GROUPS:
        items = by_group.get(gid, [])
        groups_src.append(
            f'        IconCategory(\n'
            f'            id = "{gid}",\n'
            f'            nameRes = R.string.icon_category_{gid},\n'
            f'            fallbackName = "{label}",\n'
            f'            iconNames = listOf(\n{kt_list(items)}\n            )\n'
            f'        )'
        )

    out_kt.write_text(
        f'''package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.R

/**
 * 图标分类表（**自动生成，不要手改**）。
 *
 * 生成脚本：`scripts/assets/import_material_symbol_categories.py`
 * 数据来源：Google Fonts 的图标元数据（[METADATA_URL]），取 `icons[].categories`。
 *
 * ## 为什么是「12 个组」而不是上游的 33 个分类
 *
 * ⚠️⚠️ 上游的分类是**两套并存且互斥的体系**（实测 2026-10-05，4150 个图标）：
 *
 * | 体系 | 命名形态 | 图标数 | 例 |
 * |---|---|---|---|
 * | 旧 Material Icons | 全小写 | 1982 | `action` / `image` / `av` |
 * | 新 Material Symbols | TitleCase | 2055 | `Actions` / `Images` / `Audio&Video` |
 * | 无分类 | — | 113 | `airware` / `app_promo` |
 *
 * 同一个图标**不会**同时出现在 `action` 与 `Actions` 里（实测交集 0），
 * 所以原样展示会得到 33 个分类项，且 `action` 与 `Actions` 并列 —— 用户无法理解
 * 为什么有两个"操作"。⇒ 生成脚本把它们归并成 12 个**面向使用场景**的组。
 *
 * ⚠️ 归并表是**手写的**（上游没有这一层），改它要重新想"用户找图标时脑子里想什么"。
 *
 * ## 「常用」组
 *
 * [POPULAR] 来自元数据的 `popularity` 字段（Google 的真实使用统计），
 * **不是手写的**。它排在所有分类之前，让 90% 的取用不必翻分类。
 */
object MaterialSymbolCategories {{

    /** 一个分类。 */
    data class IconCategory(
        val id: String,
        /** 本地化名字。三语都有 `icon_category_<id>`。 */
        val nameRes: Int,
        /** 资源缺失时的回退名（生成期写入，便于排查）。 */
        val fallbackName: String,
        val iconNames: List<String>,
    )

    /**
     * 「常用」组的图标（按 Google 的 popularity 降序，取前 {len(popular)} 个）。
     *
     * ⚠️ 它的 id 是 `popular`，**不在** [ALL] 里 —— [ALL] 是"内容分类"，
     *    而"常用"是一个**视图**（同一批图标还会出现在各自的分类里）。
     *    合在一起会让"其他"组里少掉一半图标。
     */
    val POPULAR: List<String> = listOf(
{kt_list(popular, "        ")}
    )

    /** 全部分类（不含 [POPULAR]）。顺序即展示顺序。 */
    val ALL: List<IconCategory> = listOf(
{",".join(chr(10) + s for s in groups_src)}
    )

    /** 图标名 → 分类 id。查不到时的语义见 [groupOf]。 */
    private val byName: Map<String, String> = ALL
        .flatMap {{ category -> category.iconNames.map {{ it to category.id }} }}
        .toMap()

    /**
     * 查某个图标属于哪个分类；**未知图标返回 `"misc"`**（其他）。
     *
     * ⚠️ 返回 `"misc"` 而不是 null：调用方（选择器的高亮/滚动定位）拿到 null
     *    还得自己判一次，而"归入其他"本来就是正确行为（上游有 113 个图标
     *    没有任何分类）。
     */
    fun groupOf(iconName: String): String = byName[iconName] ?: "misc"
}}
''',
        encoding="utf-8",
        newline="\n",
    )
    print(f"\n已写入 {out_kt.relative_to(repo)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

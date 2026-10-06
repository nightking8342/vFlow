#!/usr/bin/env python3
"""从 Material Symbols 上游生成 vFlow 的图标资源与名字清单。

用法：
    python scripts/assets/import_material_symbols.py --upstream /tmp/msi/mdi --repo .

上游准备（一次性，浅克隆 + 稀疏检出，约 200 MB 索引 + 30 MB 工作区）：
    git clone --filter=blob:none --sparse --depth 1 \\
        https://github.com/google/material-design-icons.git mdi
    cd mdi
    git sparse-checkout set --no-cone '/symbols/android/*/materialsymbolsrounded/*_24px.xml'

产出：
    1. `app/src/main/res/drawable/rounded_<name>_24.xml`        —— 线框风格
       `app/src/main/res/drawable/rounded_<name>_fill_24.xml`   —— 填充风格
    2. `app/src/main/java/com/chaomixian/vflow/core/workflow/MaterialSymbolNames.kt`
       —— 全量图标名清单（供选择器与 R8 keep 使用）

⚠️ 命名映射（已逐字节核实）：
    上游 `<name>_24px.xml`      → 本仓库 `rounded_<name>_24.xml`
    上游 `<name>_fill1_24px.xml` → 本仓库 `rounded_<name>_fill_24.xml`
    （`rounded_layers_fill_24` / `rounded_settings_fill_24` 与上游 fill1 的
      pathData MD5 完全一致，故这是既有约定，不是本次新造。）

⚠️ `android:tint="?attr/colorControlNormal"` 与
   `android:fillColor="@android:color/white"` 是上游自带形态，与仓库既有图标
   逐字一致，**不要"顺手"改掉** —— 改掉后图标在卡片上是纯白（不可见）。

⚠️ 换行：源与目标都是 CRLF（本机 `core.autocrlf=true`），逐字复制即可。
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import sys
from pathlib import Path

# 上游图标目录（`symbols/android/<name>/materialsymbolsrounded/`）
STYLE_DIR = "materialsymbolsrounded"

# fork 自绘的图标：上游没有对应条目，**不得**被本脚本触及。
# 它们由 fork 自己维护（见 FORK.md），名字与 Material Symbols 无冲突（已核实）。
FORK_OWNED = {
    "backup_export",
    "fold",
    "hexagon_nodes",
    "horizontal_align_bottom",
    "js",
    "log",
    "lua",
    "signal_cellular",
    "swap_sim",
    "xposed_js",
}

# 资源名合法字符（aapt2 只接受小写字母、数字、下划线）
VALID_RES = re.compile(r"^[a-z0-9_]+$")


def collect(upstream: Path) -> list[tuple[str, Path, Path]]:
    """返回 [(图标名, 线框源文件, 填充源文件)]，按名字排序。"""
    base = upstream / "symbols" / "android"
    if not base.is_dir():
        sys.exit(f"找不到上游目录：{base}\n请先按模块 docstring 里的步骤准备上游。")

    entries: list[tuple[str, Path, Path]] = []
    skipped: list[str] = []
    for name in sorted(os.listdir(base)):
        style = base / name / STYLE_DIR
        if not style.is_dir():
            continue
        line = style / f"{name}_24px.xml"
        fill = style / f"{name}_fill1_24px.xml"
        if not line.is_file() or not fill.is_file():
            skipped.append(name)
            continue
        if not VALID_RES.match(f"rounded_{name}_24"):
            skipped.append(f"{name}（非法资源名）")
            continue
        entries.append((name, line, fill))

    if skipped:
        print(f"⚠️ 跳过 {len(skipped)} 项：{skipped[:8]}{' …' if len(skipped) > 8 else ''}")
    return entries


def target_names(name: str) -> tuple[str, str]:
    return f"rounded_{name}_24", f"rounded_{name}_fill_24"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--upstream", required=True, help="material-design-icons 工作区路径")
    ap.add_argument("--repo", default=".", help="vFlow 仓库根目录")
    ap.add_argument("--dry-run", action="store_true", help="只统计，不写文件")
    args = ap.parse_args()

    upstream = Path(args.upstream).resolve()
    repo = Path(args.repo).resolve()
    drawable = repo / "app/src/main/res/drawable"
    names_kt = (
        repo
        / "app/src/main/java/com/chaomixian/vflow/core/workflow/MaterialSymbolNames.kt"
    )

    if not drawable.is_dir():
        sys.exit(f"找不到 drawable 目录：{drawable}")

    entries = collect(upstream)
    if not entries:
        sys.exit("上游没有收集到任何图标，请检查 sparse-checkout 是否执行过。")

    # 名字集 → 目标文件名集；顺便做碰撞检测（含与 fork 自绘图标的碰撞）
    name_to_files: dict[str, tuple[str, str]] = {}
    collisions: list[str] = []
    for name, _, _ in entries:
        if name in FORK_OWNED:
            collisions.append(f"{name} 与 fork 自绘图标重名")
            continue
        name_to_files[name] = target_names(name)

    all_targets: dict[str, str] = {}
    for name, (line_t, fill_t) in name_to_files.items():
        for t in (line_t, fill_t):
            if t in all_targets:
                collisions.append(f"文件名碰撞：{t} ← {all_targets[t]} / {name}")
            all_targets[t] = name
    if collisions:
        print("❌ 存在冲突，中止：")
        for c in collisions:
            print("   ", c)
        return 1

    written = 0
    overwritten = 0
    for name, line_src, fill_src in entries:
        if name not in name_to_files:
            continue
        line_t, fill_t = name_to_files[name]
        for src, dst_name in ((line_src, line_t), (fill_src, fill_t)):
            dst = drawable / f"{dst_name}.xml"
            if dst.exists():
                overwritten += 1
            if not args.dry_run:
                shutil.copyfile(src, dst)
            written += 1

    # 图标名清单（供选择器与 R8 keep）
    names = sorted(name_to_files)
    if not args.dry_run:
        body = ",\n".join(f'        "{n}"' for n in names)
        names_kt.write_text(
            f'''package com.chaomixian.vflow.core.workflow

/**
 * Material Symbols（Rounded 风格，24dp）的**全量图标名清单**。
 *
 * ⚠️ 本文件由 `scripts/assets/import_material_symbols.py` **自动生成**，不要手改。
 *    改图标库请重跑脚本（做法见该脚本的模块 docstring）。
 *
 * ⚠️⚠️ **每个名字都必须能在 `res/drawable` 里找到对应资源**：
 *    线框风格 = `rounded_<name>_24`，填充风格 = `rounded_<name>_fill_24`。
 *    有 `MaterialSymbolNamesTest` 逐条核对（这条测试防的是「清单与资源脱节」——
 *    脱节的表现是选择器里点一下就静默什么都不发生）。
 *
 * ⚠️⚠️ **为什么需要这份清单**：图标按**字符串名**经 `getIdentifier` 查找，
 *    R8 的 `shrinkResources` 看不见这种引用，会把没人用 `R.drawable.` 引用的
 *    图标当死资源剥掉（**已实际发生**：`rounded_download_24` 在快捷方式选择器里
 *    可选，但 release 包里不存在，选了等于没选）。`res/raw/keep.xml` 按本清单
 *    把它们钉住，见该文件的注释。
 *
 * 来源：google/material-design-icons（Apache-2.0），
 * 上游路径 `symbols/android/<名字>/materialsymbolsrounded/`。
 *
 * ⚠️ 上面那行**刻意不写通配**：`*` 紧跟 `/` 会在 Kotlin 的块注释里**提前闭合注释**，
 *    编译期报 `Syntax error: Unclosed comment`（本文件第一版就是这么挂的）。
 */
object MaterialSymbolNames {{
    /** 图标基础名（不含 `rounded_` 前缀与 `_24` / `_fill_24` 后缀）。 */
    val ALL: List<String> = listOf(
{body}
    )
}}
''',
            encoding="utf-8",
            newline="\n",
        )

    print(f"图标名总数     : {len(names)}")
    print(f"资源文件写入   : {written}（其中覆盖已有 {overwritten}）")
    print(f"名字清单       : {names_kt.relative_to(repo)}")
    if args.dry_run:
        print("（--dry-run，未实际写入）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

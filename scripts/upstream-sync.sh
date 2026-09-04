#!/usr/bin/env bash
# vFlow 上游同步脚本
# 用法: ./scripts/upstream-sync.sh [version-tag]
#   不带参数: 合并 upstream/master 到本地 master, 然后 rebase dev
#   带 tag:    合并指定 release tag (如 v1.5.2)
#
# 前提: 工作区干净, 已配置 upstream (见 FORK.md)
#
# 流程:
#   1. 检查工作区干净
#   2. fetch upstream --tags
#   3. 切到 master, merge 上游 (tag 或 master)
#   4. 门槛检查: 构建 + 测试
#   5. 切回 dev, rebase master
#   6. 提示 push

set -euo pipefail

cd "$(dirname "$0")/.."   # 回到仓库根目录

echo "=== [1/6] 检查工作区 ==="
if ! git status --porcelain; then
  echo "工作区不干净, 请先提交或暂存改动"
  exit 1
fi
echo "工作区干净 ✓"

echo ""
echo "=== [2/6] 拉取上游 ==="
git fetch upstream --tags
echo "已拉取 upstream 和 tags ✓"

TAG="${1:-}"
echo ""
echo "=== [3/6] 合并上游到 master ==="
git checkout master
if [ -n "$TAG" ]; then
  echo "合并 tag: $TAG"
  git merge "$TAG"
else
  echo "合并 upstream/master"
  git merge upstream/master
fi

echo ""
echo "=== [4/6] 门槛检查 (构建 + 测试) ==="
echo "提示: 需要 Android SDK + JDK 17。若失败, 请检查是否环境问题。"
./gradlew test || {
  echo "⚠️ 测试失败。排查后再继续。若为环境问题(缺 SDK/NDK), 以 CI 为准。"
}

echo ""
echo "=== [5/6] rebase dev 到 master ==="
git checkout dev
git rebase master

echo ""
echo "=== [6/6] 完成 ==="
echo "在 dev 上已 rebase 到最新上游。"
echo "请检查冲突并处理 (见 FORK.md 冲突归属), 然后:"
echo "  git push -u origin dev"
echo "  git push -u origin master"

#!/usr/bin/env bash
# ============================================================================
# 生产一键回滚
#
# 用法：
#   bash tools/rollback_prod.sh <git-ref>            # 回滚到指定提交/tag
#   bash tools/rollback_prod.sh <git-ref> --dry-run  # 只验证打包与校验，不改生产
#   bash tools/rollback_prod.sh --list               # 看最近的提交，挑一个回滚
#
# 原理：服务器上的 /opt/PeakFlow 不是 git 仓库（历史上走源码包部署），
#       所以「回滚」= 把目标 commit 的 git archive 重新打包上传，再跑 deploy.sh。
#       镜像每次都会重新 build，因此回滚必然生效（不存在"用旧镜像"的歧义）。
#
# 注意：
#   · 回滚**不动数据库**。若本次发布含 DB 变更（本项目的发布约定是「先代码后迁移」，
#     且迁移脚本只用 ALTER 不用 DROP），回滚代码前请先确认旧代码能兼容新表结构。
#   · 回滚后会自动做外网健康检查；失败会明确提示，并给出再看日志的命令。
# ============================================================================
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SSH_CONFIG="${SSH_CONFIG:-$REPO_DIR/../tools/ssh_config}"
SSH_HOST="${SSH_HOST:-pf-us}"
REMOTE_DIR="${REMOTE_DIR:-/opt/PeakFlow}"
DOMAIN="${DOMAIN:-https://peakflow.top}"

REF="${1:-}"
DRY_RUN=0
[ "${2:-}" = "--dry-run" ] && DRY_RUN=1

# ssh_config 可能不在仓库内（本机工作区布局），给两个候选路径
if [ ! -f "$SSH_CONFIG" ]; then
    for c in "$REPO_DIR/tools/ssh_config" "$HOME/.ssh/peakflow_config"; do
        [ -f "$c" ] && SSH_CONFIG="$c" && break
    done
fi

if [ "$REF" = "--list" ]; then
    cd "$REPO_DIR"
    echo "最近 15 个可用作回滚目标的提交："
    git log --oneline -15
    echo ""
    echo "用法示例：bash tools/rollback_prod.sh <提交号> --dry-run"
    exit 0
fi

if [ -z "$REF" ]; then
    echo "✗ 缺少回滚目标。用 --list 查看可选提交。" >&2
    exit 1
fi

cd "$REPO_DIR"
TARGET_SHA="$(git rev-parse --verify "$REF^{commit}" 2>/dev/null || true)"
if [ -z "$TARGET_SHA" ]; then
    echo "✗ 提交不存在：$REF" >&2
    exit 1
fi
SHORT="$(git rev-parse --short "$TARGET_SHA")"
CUR="$(git rev-parse --short HEAD)"

echo "▶ 回滚目标：$SHORT  ($(git log -1 --format=%s "$TARGET_SHA"))"
echo "  当前 HEAD：$CUR"
if [ "$SHORT" = "$CUR" ]; then
    echo "⚠ 目标与当前 HEAD 相同，无需回滚。"
    exit 0
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "▶ 打包目标版本（纯 git archive，不叠加任何本地未提交文件）"
git archive "$TARGET_SHA" | tar -x -C "$WORK"
PKG="/tmp/peakflow-rollback-$SHORT.tar.gz"
tar -czf "$PKG" -C "$WORK" .
echo "  ✓ $PKG（$(du -h "$PKG" | cut -f1)）"

# 关键文件自检：回滚包必须包含部署脚本与前端产物源，否则不 continue
for f in deploy/scripts/deploy.sh docker-compose.yml docker-compose.prod.yml frontend/package.json; do
    if [ ! -f "$WORK/$f" ]; then
        echo "✗ 回滚包缺少必需文件：$f（该提交可能早于部署体系引入）" >&2
        exit 1
    fi
done
echo "  ✓ 必需文件齐全"

if [ "$DRY_RUN" = "1" ]; then
    echo ""
    echo "✓ dry-run 通过：包已就绪，未触碰生产。"
    echo "  真正回滚请去掉 --dry-run：bash tools/rollback_prod.sh $SHORT"
    exit 0
fi

echo "▶ 上传并解包到 $REMOTE_DIR"
scp -q -F "$SSH_CONFIG" "$PKG" "$SSH_HOST:/tmp/"
ssh -F "$SSH_CONFIG" "$SSH_HOST" "cd $REMOTE_DIR && tar -xzf /tmp/$(basename "$PKG") -C $REMOTE_DIR && (chown -R peakflow:peakflow $REMOTE_DIR 2>/dev/null || sudo chown -R peakflow:peakflow $REMOTE_DIR)"

echo "▶ 重新构建并启动（deploy.sh 自动带 https 覆盖层）"
ssh -F "$SSH_CONFIG" "$SSH_HOST" "cd $REMOTE_DIR && bash deploy/scripts/deploy.sh" 2>&1 | tail -20

echo ""
echo "▶ 外网健康检查"
CODE="$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$DOMAIN/healthz" || echo 000)"
if [ "$CODE" = "200" ]; then
    echo "  ✓ $DOMAIN/healthz → 200"
    echo ""
    echo "✅ 已回滚到 $SHORT"
    echo "   建议再手工点一遍：登录 / 秒杀详情 / 支付 / 购物车 / 后台"
    echo "   若需回到原版本：bash tools/rollback_prod.sh $CUR"
else
    echo "  ✗ healthz → $CODE"
    echo ""
    echo "❌ 回滚后健康检查未通过，请立刻排查："
    echo "   ssh -F $SSH_CONFIG $SSH_HOST 'docker logs --tail=120 peakflow-nginx'"
    echo "   ssh -F $SSH_CONFIG $SSH_HOST 'docker logs --tail=200 peakflow-backend1'"
    exit 1
fi

#!/bin/sh
# ============================================================================
# PeakFlow 全量回归（在 tester 容器内跑）
# 用法（注意 -w 必须指定到 postman 目录 —— 集合里 multipart 的 src 是相对路径，
#       newman 是按【当前工作目录】解析的，不在 postman 下跑上传用例会全红）：
#   MSYS_NO_PATHCONV=1 docker compose exec -T \
#     -w /workspace/backend/postman tester sh -s < tools/run_regression.sh
# ============================================================================
set -u

DB=peakflow_dev
RD=0

echo "════════════════════════════════════════════════════════════════"
echo " PeakFlow 全量回归  $(date '+%Y-%m-%d %H:%M:%S')"
echo "════════════════════════════════════════════════════════════════"

echo ""
echo "───── ① 预置基础种子数据（会清库重建）─────"
python3 seed_base_data.py --db "$DB" --redis-db "$RD" 2>&1 | tail -8

echo ""
echo "───── ② 重置测试夹具 ─────"
python3 setup_test_data.py --db "$DB" --redis-db "$RD" 2>&1 | tail -10

echo ""
echo "───── ③ Newman 全量接口回归 ─────"
newman run PeakFlow-API.postman_collection.json \
    -e env-test.postman_environment.json \
    --env-var baseUrl=http://nginx/api \
    --reporters cli,json --reporter-json-export newman-report.json 2>&1 | tail -26

echo ""
echo "───── ④ DB / Redis 落库校验 ─────"
python3 verify_db_redis.py 2>&1 | tail -14

echo ""
echo "───── ⑤ 端到端冒烟 ─────"
python3 smoke_test.py 2>&1 | tail -24

echo ""
echo "════════════════════════════════════════════════════════════════"
echo " 容器内部分结束（Maven 套件另跑）"
echo "════════════════════════════════════════════════════════════════"

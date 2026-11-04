#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 生成 nginx 用的自签 TLS 证书（给 HTTPS 用）
#
# 用法：
#   ./gen-cert.sh                  # 用默认 IP 生成
#   ./gen-cert.sh 1.2.3.4          # 指定公网 IP
#   SERVER_IP=1.2.3.4 ./gen-cert.sh
#   ./gen-cert.sh 1.2.3.4 --force  # 已存在也强制重新生成
#
# 产物（同目录 certs/）：
#   peakflow.crt  证书
#   peakflow.key  私钥  ← 已被 .gitignore 忽略，绝不能提交
#
# 注意：自签证书浏览器默认不信任，访问 https 会提示「你的连接不是专用连接」。
#      点「高级 → 继续前往」可正常访问；想彻底无警告需要真实域名 + Let's Encrypt。
#      本机临时信任（Windows，当前用户）：
#        certutil -user -addstore -f Root peakflow.crt
#      撤销信任：
#        certutil -user -delstore Root <证书指纹>
# ---------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")"

IP="${1:-${SERVER_IP:-43.111.225.28}}"
FORCE="${2:-}"
CRT_DIR="certs"
CRT="$CRT_DIR/peakflow.crt"
KEY="$CRT_DIR/peakflow.key"

if [[ "$IP" == "--force" ]]; then
  IP="${SERVER_IP:-43.111.225.28}"
  FORCE="--force"
fi

if ! command -v openssl >/dev/null 2>&1; then
  echo "错误：未找到 openssl，请先安装。" >&2
  exit 1
fi

mkdir -p "$CRT_DIR"

if [[ -f "$CRT" && -f "$KEY" && "$FORCE" != "--force" ]]; then
  echo "证书已存在，跳过生成：$CRT"
  echo "如需重新生成：./gen-cert.sh $IP --force"
  openssl x509 -in "$CRT" -noout -subject -dates
  exit 0
fi

echo "正在为 IP=$IP 生成自签证书..."

openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
  -keyout "$KEY" -out "$CRT" \
  -subj "/C=CN/ST=JS/L=Nanjing/O=PeakFlow/CN=$IP" \
  -addext "subjectAltName=IP:$IP" 2>/dev/null

chmod 600 "$KEY" 2>/dev/null || true
chmod 644 "$CRT" 2>/dev/null || true

echo "生成完成："
openssl x509 -in "$CRT" -noout -subject -dates -ext subjectAltName
echo ""
echo "下一步：cd .. && cd .. && docker compose up -d nginx"

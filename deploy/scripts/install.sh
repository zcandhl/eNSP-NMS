#!/usr/bin/env bash
# eNSP NMS 学校交付安装（Linux + Docker）
set -euo pipefail
DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$DEPLOY_DIR"

if ! command -v docker >/dev/null 2>&1; then
  echo "未检测到 Docker，请先安装 Docker Engine / Compose。" >&2
  exit 1
fi

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "已生成 deploy/.env，请按学校修改授权与密码后重新执行。"
  echo "必改项：DB_PASSWORD、MYSQL_ROOT_PASSWORD、AUTH_JWT_SECRET、NMS_LICENSE_*"
  exit 2
fi

echo "==> 构建并启动 eNSP NMS ..."
docker compose --env-file .env up -d --build

echo ""
echo "安装完成。"
echo "浏览器访问: http://localhost  （若 WEB_PORT 已改，请用对应端口）"
echo "默认账号: admin / Admin@123  （首次登录须改密）"
echo "查看日志: docker compose logs -f backend"

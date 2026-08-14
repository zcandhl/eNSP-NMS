#!/usr/bin/env bash
set -euo pipefail
DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$DEPLOY_DIR"
mkdir -p backups
stamp="$(date +%Y%m%d-%H%M%S)"
file="backups/ensp_nms-${stamp}.sql"
echo "导出数据库到 ${file} ..."
docker compose --env-file .env exec -T mysql sh -c 'mysqldump -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" ensp_nms' > "$file"
echo "完成: ${file}"

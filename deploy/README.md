# 部署脚本（可选自托管）

使用 Docker Compose 一键启动本系统（MySQL + Redis + 后端 + Nginx 前端）。

```text
deploy/
├── docker-compose.yml
├── .env.example          # 复制为 .env 后修改
└── scripts/
    ├── install.ps1 / install.sh
    └── backup.ps1 / backup.sh
```

```bash
cp .env.example .env      # Windows: Copy-Item .env.example .env
# 至少修改 DB_PASSWORD、MYSQL_ROOT_PASSWORD、AUTH_JWT_SECRET
docker compose --env-file .env up -d --build
```

- 访问：`http://localhost`（可用 `WEB_PORT` 改端口）  
- 账号：`admin` / `Admin@123`（首次须改密）  
- 无 Docker：见 [native/](native/)  

开发调试请优先用仓库根目录 [README.md](../README.md) 的前后端分离启动方式。

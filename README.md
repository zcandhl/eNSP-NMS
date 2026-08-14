# eNSP 网络管理系统

面向华为 **eNSP** 实验环境的轻量级网络管理系统，交互参考企业级网管产品（如 eSight）。  
技术栈：Vue 3 + Spring Boot 3，B/S 架构。

| 文档 | 说明 |
|------|------|
| [软件说明](docs/软件说明.md) | 功能模块与实现基线 |
| [差距分析](docs/差距分析.md) | 与真实生产网管的差异与边界 |
| [LICENSE](LICENSE) | Apache License 2.0 |

## 功能概览

- 设备纳管：CRUD、分组、SNMP / ARP 发现，多类型（路由 / 交换 / 防火墙 / AC / AP / 虚拟 PC / 服务器）
- 监控与告警：SNMP 性能、Ping 兜底、Trap 接收；虚拟 PC 默认仅 Ping
- 拓扑：LLDP / CDP / ARP 尽力发现与可视化（eNSP 上 LLDP 可能不全）
- 配置：SSH 备份 / 下发、定时任务、模板
- 安全：JWT + RBAC、操作审计、WebSSH；默认口令首次登录强制改密
- 智能运维：告警收敛、健康分、基线异常、根因候选、巡检与运维助手（半闭环）

### 设备类型能力

| 类型 | 在线探测 | SNMP 性能 | 拓扑 | SSH 配置 |
|------|----------|-----------|------|----------|
| 路由器 / 交换机 | SNMP（可 Ping 兜底） | 支持 | 支持 | 有凭证则可用 |
| 防火墙 / AC / 服务器 | 同上 | 尽力 | 支持 | 有凭证则可用 |
| AP | SNMP（有独立 IP） | 基础 | 否 | 通常无 |
| **虚拟 PC** | **仅 Ping** | **否** | 终端节点 | **否** |

防火墙 / AC / AP 为通用纳管（上线、告警、上图），不做 WLAN 专业管理或策略可视化。

## 仓库结构

```text
├── backend/     # Spring Boot API
├── frontend/    # Vue 3
├── deploy/      # Docker Compose / 原生打包脚本（可选自托管）
├── docs/        # 产品与技术说明
├── LICENSE
└── README.md
```

## 开发环境启动

### 依赖

- JDK 17+
- Node.js 18+
- MySQL 8.0+
- Maven

### 默认账号

| 账号 | 密码 | 角色 |
|------|------|------|
| `admin` | `Admin@123` | 系统管理员 |
| `operator` | `Operator@123` | 运维操作员 |

首次使用默认口令登录后须立即改密。预置角色还可在「用户权限」中调整。

生产或长期运行请设置：`AUTH_JWT_SECRET`（≥32 字符）、`AUTH_CORS_ORIGINS`、数据库账号密码。

### 数据库

```sql
CREATE DATABASE ensp_nms DEFAULT CHARACTER SET utf8mb4;
```

可选执行 `backend/src/main/resources/sql/` 下脚本；开发环境 JPA `ddl-auto=update` 也可自动建表。

修改 `backend/src/main/resources/application-dev.yml` 中的数据源账号密码。

### 启动后端

```bash
cd backend
mvn spring-boot:run
```

API：`http://localhost:8080`（未登录访问 `/api/**` 返回 401）。

### 启动前端

```bash
cd frontend
npm install
npm run dev
```

界面：`http://localhost:3000`。

## 可选：Compose 自托管

适合本机一键拉起 MySQL + Redis + 前后端（非开发热更新）。

```powershell
cd deploy
Copy-Item .env.example .env
# 编辑 .env 中的密码与 JWT
docker compose --env-file .env up -d --build
```

默认浏览器访问 `http://localhost`。说明见 [deploy/README.md](deploy/README.md)。  
无 Docker 时可用 [deploy/native](deploy/native) 打包 JAR + 静态前端（需本机 JDK 与 MySQL）。

## eNSP 对接要点

1. 拓扑中添加 Cloud，绑定 VirtualBox Host-Only  
2. 设备管理 IP 与 Host-Only 同网段  
3. 开启 SNMP（示例）：

```text
snmp-agent
snmp-agent community read public
snmp-agent trap enable
snmp-agent target-host trap address udp-domain <管理机IP> params securityname public v2c
```

4. （可选）开启 SSH，供配置备份与 WebSSH  

更细的 LLDP / 私有 MIB 说明见 `docs/` 下相关文档。

## 许可证

[Apache License 2.0](LICENSE) · Copyright © 2026 zcandhl  

详见 `LICENSE` 与 `NOTICE`。

# eNSP-NMS · 园区网工程预实施验证系统

面向华为 **eNSP** 实验环境的轻量级网络管理与预实施验证平台，交互参考企业级网管产品（如 eSight）。

- **产品名称**：园区网工程预实施验证系统
- **版本**：1.0
- **技术栈**：Vue 3 + Spring Boot 3，B/S 架构

## 功能概览

### 设备纳管
- 设备 CRUD、分组管理、批量刷新 / 批量分组 / 批量凭证；端口清单与连通性测试
- CIDR 网段自动发现：SNMP 探测、ARP / 在线端点补充，候选勾选入库
- 设备类型：路由器、交换机、防火墙、AC、AP、虚拟 PC、服务器

### 性能监控
- SNMP 轮询 CPU / 内存 / 接口流量，提供历史趋势与端口视图
- 可配置 CPU / 内存阈值，越限生成性能告警

### 告警中心
- SNMP Trap 接收（v1 / v2c / v3），按 OID 归类
- 告警确认、清除、批量处理、统计与导出；连通性去抖，连续成功判恢复并自动关单
- 告警风暴 / 链路关联、事件与根因候选

### 拓扑可视化
- LLDP / CDP / ARP 尽力发现，节点位置持久化；手工加链 / 删链 / 链路状态维护
- 基于 AntV G6 的拓扑图与小地图，支持邻居关系查看

### 配置管理
- 单设备 / 批量配置备份、下发与回滚；配置模板与变更日志
- 合规基线检查；SSH / Telnet 兜底采集；定时备份任务

### AIOps 智能运维
- 健康分、异常检测（k-sigma）、告警收敛、根因候选、巡检助手与反馈闭环
- 策略阈值与 Webhook 通知；受控无人值守自动执行；运维大屏

### 工程验收（预实施验证）
- 验收项目 / 计划 / 批次管理；检查项 dry-run 预演
- 批次运行、停止、作废、单项确认；AI 结果摘要
- 验收报告一键生成并下载（Word 文档）

### 多项目与权限
- 业务数据按工程隔离，顶部栏随时切换当前项目
- JWT + RBAC：用户、角色、权限管理；操作审计日志；WebSSH 终端；SNMP 连通测试工具
- 预置角色：系统管理员、运维操作员、监控只读、告警值班员、配置管理员
- 默认口令首次登录强制改密

### 设备类型能力

| 类型 | 在线探测 | SNMP 性能 | 拓扑 | SSH 配置 |
|------|----------|-----------|------|----------|
| 路由器 / 交换机 | SNMP（可 Ping 兜底） | 支持 | 支持 | 有凭证则可用 |
| 防火墙 / AC / 服务器 | 同上 | 尽力 | 支持 | 有凭证则可用 |
| AP | SNMP（有独立 IP） | 基础 | 否 | 通常无 |
| **虚拟 PC** | **仅 Ping** | **否** | 终端节点 | **否** |

防火墙 / AC / AP 为通用纳管（上线、告警、上图），不做 WLAN 专业管理或策略可视化。

## 技术栈

### 后端
- Java 17 · Spring Boot 3.2.4 · Spring Data JPA · Spring Security
- MySQL 8 · Redis（缓存 / 会话预留接入，Compose 一并拉起）
- SNMP4J 3.8（SNMP 轮询 + Trap 接收）· JSch（SSH）· WebSocket（WebSSH）
- Apache POI（Word 报告）· jjwt（JWT）· Maven 构建

### 前端
- Vue 3.4 · Vue Router 4 · Pinia 2 · Element Plus 2.5 · Axios
- AntV G6 4.8（拓扑）· ECharts 5.4（图表）· xterm 5（终端）
- Vite 5 · Sass

## 仓库结构

```text
├── backend/     # Spring Boot API（验收、AIOps、配置、拓扑、性能、告警、权限、审计等模块）
├── frontend/    # Vue 3 前端（设备 / 性能 / 告警 / 拓扑 / 配置 / 验收 / AIOps / 权限 / 审计）
├── deploy/      # Docker Compose 与原生打包脚本、安装 / 备份脚本
├── LICENSE
└── README.md
```

## 快速开始

### 依赖

- JDK 17+
- Maven 3.9+
- Node.js 18+
- MySQL 8.0+

### 数据库

```sql
CREATE DATABASE ensp_nms DEFAULT CHARACTER SET utf8mb4;
```

开发环境 JPA `ddl-auto=update` 自动建表；数据源账号密码在 `backend/src/main/resources/application-dev.yml` 中修改。

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

界面：`http://localhost:3000`（开发代理 `/api`、`/ws` → 8080）。

### 默认账号

| 账号 | 密码 | 角色 |
|------|------|------|
| `admin` | `Admin@123` | 系统管理员 |

首次使用默认口令登录后须立即改密；其他账号与角色在「用户权限」中维护。生产环境请覆盖 `AUTH_JWT_SECRET`（≥32 字符）、`AUTH_CORS_ORIGINS` 与数据库账号密码。

## 设备发现与扫描

在「设备管理 → 自动发现」中按网段扫描可纳管设备，扫出后勾选入库：

- **网段写法**：支持 CIDR 掩码，如 `10.20.10.0/24`、`10.20.0.0/16`；只填纯 IP（如 `10.20.10.0`）默认按 `/24` 处理。
- **扫描上限**：单次最多探测 1024 台主机，可由配置 `nms.discovery.max-hosts`（环境变量 `NMS_DISCOVERY_MAX_HOSTS`）调整；网段过大时拦截并提示，需缩小掩码或拆分成多个网段。
- **附加探测 IP**：管理地址不在扫描网段内的设备，可用配置 `nms.discovery.extra-ips`（环境变量 `NMS_DISCOVERY_EXTRA_IPS`，逗号分隔）补充探测。
- **探测方式**：交换机 / 路由器经 SNMP 探测，不可达时 Ping 兜底；虚拟 PC 仅 Ping。

## 部署

### Docker Compose

一键拉起 MySQL 8 + Redis 7 + 后端 + Nginx 前端：

```powershell
cd deploy
Copy-Item .env.example .env
# 编辑 DB_PASSWORD、MYSQL_ROOT_PASSWORD、AUTH_JWT_SECRET
docker compose --env-file .env up -d --build
```

- 访问：`http://localhost`（可用 `WEB_PORT` 改端口）
- SNMP Trap：设备指向管理机 `162/UDP`
- 说明见 [deploy/README.md](deploy/README.md)

### 原生包（无 Docker）

本机安装 JDK 17+ 与 MySQL 8 后，用 JAR 托管 API 与前端静态资源：

```powershell
powershell -ExecutionPolicy Bypass -File deploy\native\build-release.ps1
cd deploy\native
Copy-Item env.example.ps1 env.ps1   # 修改数据库密码与 JWT
.\start.ps1
```

产出 `*.jar` 与 `web/`，浏览器访问 `http://localhost:8080`。详见 [deploy/native/README.md](deploy/native/README.md)。

## 主要配置 / 环境变量

| 分组 | 环境变量 | 说明 |
|------|----------|------|
| 数据库 | `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`（Compose） | MySQL 连接信息 |
| JWT | `AUTH_JWT_SECRET`、`AUTH_CORS_ORIGINS` | 令牌密钥与浏览器跨域来源 |
| SNMP Trap | `SNMP_TRAP_PORT`、`SNMP_V3_USER` / `AUTH_PROTO` / `AUTH_PASS` / `PRIV_PROTO` / `PRIV_PASS` | Trap 端口与 v3 USM 用户 |
| 发现扫描 | `NMS_DISCOVERY_EXTRA_IPS`、`NMS_DISCOVERY_MAX_HOSTS` | 附加探测 IP、单次扫描上限 |
| SSH | `NMS_SSH_USER`、`NMS_SSH_PASS` | 设备未登记账号时的只读查询凭据 |
| 性能阈值 | `NMS_CPU_WARN` / `NMS_CPU_DANGER`、`NMS_MEM_WARN` / `NMS_MEM_DANGER` | CPU / 内存告警阈值 |
| LLM | `NMS_LLM_ENABLED`、`NMS_LLM_PROVIDER`、`NMS_LLM_BASE_URL`、`NMS_LLM_MODEL`、`NMS_LLM_API_KEY` | 运维助手 / AI 摘要接入 |
| AIOps | `NMS_AIOPS_*` | 关联窗口、异常检测、无人值守等策略参数 |

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

## 许可证

[Apache License 2.0](LICENSE) · Copyright © 2026 zcandhl

详见 `LICENSE` 与 `NOTICE`。

# 无 Docker 原生运行包

本机安装 **JDK 17+** 与 **MySQL 8** 后，用 JAR 托管 API 与前端静态资源（默认端口 8080）。

## 在开发机打包

需 Maven、Node.js：

```powershell
powershell -ExecutionPolicy Bypass -File deploy\native\build-release.ps1
```

产出：`deploy/native/*.jar` 与 `deploy/native/web/`。

## 运行

```powershell
cd deploy\native
Copy-Item env.example.ps1 env.ps1   # 修改数据库密码与 JWT
.\start.ps1
```

浏览器：`http://localhost:8080`  
账号：`admin` / `Admin@123`（首次须改密）

`*.jar`、`web/`、`env.ps1` 已在 `.gitignore` 中，不会进入版本库。

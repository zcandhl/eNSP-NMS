# 复制为 env.ps1 后按学校修改，再执行 start.ps1
# Copy-Item env.example.ps1 env.ps1

$env:SPRING_PROFILES_ACTIVE = "native"
$env:SERVER_PORT = "8080"

# MySQL（教师机本机服务）
$env:DB_URL = "jdbc:mysql://127.0.0.1:3306/ensp_nms?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "ChangeMe_Db_2026"

# JWT（≥32 字符）
$env:AUTH_JWT_SECRET = "ensp-nms-native-change-me-secret-32b"
$env:AUTH_CORS_ORIGINS = "http://localhost:8080,http://127.0.0.1:8080"

# 授权展示
$env:NMS_LICENSE_CUSTOMER = "某某职业技术学院"
$env:NMS_LICENSE_SKU = "教学标准版"
$env:NMS_LICENSE_EXPIRES = "2027-08-31"
$env:NMS_LICENSE_INSTANCE = "SCH-2026-001"

# 前端目录（相对运行目录，默认 ./web）
$env:NMS_WEB_STATIC_DIR = "./web"

# SNMP Trap（教师机本机监听 162；若权限不足可改 1162 并让设备改端口）
$env:SNMP_TRAP_PORT = "162"

<template>
  <router-view v-if="isLoginPage" />
  <el-container v-else class="app-shell">
    <el-aside :width="asideCollapsed ? '64px' : '220px'" class="app-aside" :class="{ collapsed: asideCollapsed }">
      <div class="brand" @click="asideCollapsed = !asideCollapsed" title="折叠/展开">
        <div class="brand-mark">N</div>
        <div v-show="!asideCollapsed" class="brand-text">
          <div class="brand-name">eNSP NMS</div>
          <div class="brand-tag">Network Manager</div>
        </div>
      </div>
      <el-menu
        :default-active="activeMenu"
        :collapse="asideCollapsed"
        router
        class="side-menu"
        background-color="transparent"
        :text-color="menuTextColor"
        :active-text-color="menuActiveColor"
      >
        <el-menu-item index="/">
          <el-icon><Monitor /></el-icon>
          <template #title>概览</template>
        </el-menu-item>
        <el-menu-item v-if="auth.hasPermission('devices:read')" index="/devices">
          <el-icon><Box /></el-icon>
          <template #title>设备管理</template>
        </el-menu-item>
        <el-menu-item v-if="auth.hasPermission('performance:read')" index="/performance">
          <el-icon><DataAnalysis /></el-icon>
          <template #title>性能监控</template>
        </el-menu-item>
        <el-menu-item v-if="auth.hasPermission('alarms:read')" index="/alarms">
          <el-icon><Warning /></el-icon>
          <template #title>告警管理</template>
        </el-menu-item>
        <el-menu-item v-if="auth.hasAnyPermission('aiops:read', 'alarms:read')" index="/aiops">
          <el-icon><Opportunity /></el-icon>
          <template #title>
            <span>智能运维</span>
            <span v-if="!asideCollapsed" class="nav-badge">AI</span>
          </template>
        </el-menu-item>
        <el-menu-item v-if="auth.hasPermission('configs:read')" index="/configs">
          <el-icon><DocumentCopy /></el-icon>
          <template #title>配置管理</template>
        </el-menu-item>
        <el-menu-item v-if="auth.hasPermission('topology:read')" index="/topology">
          <el-icon><Connection /></el-icon>
          <template #title>拓扑视图</template>
        </el-menu-item>
        <el-menu-item v-if="auth.hasAnyPermission('users:manage', 'roles:manage')" index="/users">
          <el-icon><UserFilled /></el-icon>
          <template #title>用户权限</template>
        </el-menu-item>
        <el-menu-item v-if="auth.hasAnyPermission('audit:read', 'configs:read')" index="/audit">
          <el-icon><Document /></el-icon>
          <template #title>日志中心</template>
        </el-menu-item>
      </el-menu>
      <div v-show="!asideCollapsed" class="aside-foot">
        <div class="aside-edition">{{ license?.sku || '教学标准版' }}</div>
        <div class="aside-customer" :title="license?.customer || ''">{{ licenseCustomerShort }}</div>
        <div class="aside-ver">v{{ license?.version || '1.0.0' }}</div>
      </div>
    </el-aside>

    <el-container class="app-main-wrap">
      <el-header class="app-header" height="56px">
        <div class="header-left">
          <div class="crumb">
            <span class="crumb-root">网络管理系统</span>
            <span class="crumb-sep">/</span>
            <span class="crumb-cur">{{ currentTitle }}</span>
          </div>
        </div>
        <div class="header-right">
          <HeaderDeviceSearch />
          <HeaderAlarmBell />
          <ThemePicker />
          <el-button
            v-if="auth.hasAnyPermission('aiops:read', 'alarms:read')"
            class="header-btn"
            title="运维助手 (Ctrl+K)"
            @click="openAssistant"
          >
            <el-icon><Opportunity /></el-icon>
            运维助手
          </el-button>
          <el-button
            v-if="auth.hasPermission('webssh:connect')"
            class="header-btn"
            @click="openTerminal"
          >
            <el-icon><Monitor /></el-icon>
            Web 终端
          </el-button>
          <div class="user-chip">
            <div class="user-avatar">{{ avatarLetter }}</div>
            <div class="user-meta">
              <div class="user-name">{{ auth.displayName }}</div>
              <div class="user-role">{{ roleLabel || '用户' }}</div>
            </div>
            <el-button link class="logout-btn" @click="onLogout">退出</el-button>
          </div>
        </div>
      </el-header>
      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
  <FloatingWebTerminal v-if="!isLoginPage && auth.hasPermission('webssh:connect')" />
  <FloatingOpsAssistant
    v-if="!isLoginPage && auth.hasAnyPermission('aiops:read', 'alarms:read')"
  />
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import {
  Monitor, Box, DataAnalysis, Warning, DocumentCopy, UserFilled,
  Connection, Document, Opportunity
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import FloatingOpsAssistant from '@/components/ops-assistant/FloatingOpsAssistant.vue'
import FloatingWebTerminal from '@/components/FloatingWebTerminal.vue'
import ThemePicker from '@/components/ThemePicker.vue'
import HeaderAlarmBell from '@/components/HeaderAlarmBell.vue'
import HeaderDeviceSearch from '@/components/HeaderDeviceSearch.vue'
import { openWebTerminal } from '@/composables/webTerminalBus'
import { openOpsAssistant } from '@/composables/opsAssistantBus'
import { useLicenseInfo } from '@/composables/useLicenseInfo'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const theme = useThemeStore()
const { shell, accent } = storeToRefs(theme)
const { info: license, load: loadLicense } = useLicenseInfo()
const asideCollapsed = ref(false)

const licenseCustomerShort = computed(() => {
  const c = String(license.value?.customer || '').trim()
  if (!c) return '授权未配置'
  return c.length > 14 ? `${c.slice(0, 14)}…` : c
})

const menuTextColor = computed(() => shell.value.sidebarText)
const menuActiveColor = computed(() =>
  shell.value.darkMenu ? '#ffffff' : accent.value.primary
)

const TITLE_MAP = {
  '/': '运维概览',
  '/devices': '设备管理',
  '/performance': '性能监控',
  '/alarms': '告警管理',
  '/aiops': '智能运维',
  '/configs': '配置管理',
  '/topology': '拓扑视图',
  '/users': '用户权限',
  '/audit': '日志中心',
  '/login': '登录'
}

const activeMenu = computed(() => {
  const p = route.path
  if (p.startsWith('/aiops')) return '/aiops'
  return p
})

const isLoginPage = computed(() => route.path === '/login')

const currentTitle = computed(() => {
  const p = route.path
  if (TITLE_MAP[p]) return TITLE_MAP[p]
  for (const [key, title] of Object.entries(TITLE_MAP)) {
    if (key !== '/' && p.startsWith(key)) return title
  }
  return route.meta?.title || '工作台'
})

const ROLE_LABELS = {
  ADMIN: '系统管理员',
  OPERATOR: '运维操作员',
  VIEWER: '监控只读',
  ALARM_DUTY: '告警值班员',
  CONFIG_ADMIN: '配置管理员'
}

const roleLabel = computed(() => {
  const code = auth.roles[0]
  return ROLE_LABELS[code] || code || ''
})

const avatarLetter = computed(() => {
  const n = String(auth.displayName || 'U').trim()
  return n.charAt(0).toUpperCase()
})

const onLogout = async () => {
  await auth.logout()
  await router.replace('/login')
}

const openTerminal = () => {
  openWebTerminal()
}

const openAssistant = () => {
  openOpsAssistant({ source: 'header' })
}

onMounted(() => {
  loadLicense()
  if (auth.isAuthenticated) {
    auth.fetchMe().catch(() => {})
  }
})
</script>

<style scoped>
.app-shell {
  height: 100vh;
  background: var(--nms-bg);
}

.app-aside {
  background: var(--nms-sidebar-bg);
  border-right: 1px solid var(--nms-sidebar-border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: width 0.2s ease;
}

.brand {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 14px;
  cursor: pointer;
  border-bottom: 1px solid var(--nms-brand-border);
  flex-shrink: 0;
}

.brand-mark {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(145deg, var(--el-color-primary-light-3), var(--nms-primary));
  color: #fff;
  font-weight: 700;
  font-size: 15px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px color-mix(in srgb, var(--nms-primary) 35%, transparent);
  flex-shrink: 0;
}

.brand-name {
  color: var(--nms-brand-name);
  font-size: 14px;
  font-weight: 650;
  letter-spacing: 0.02em;
  line-height: 1.2;
}

.brand-tag {
  font-size: 10px;
  color: var(--nms-brand-tag);
  letter-spacing: 0.04em;
  text-transform: uppercase;
  margin-top: 1px;
}

.side-menu {
  border-right: none !important;
  flex: 1;
  padding: 10px 8px;
  overflow-y: auto;
}

.side-menu:not(.el-menu--collapse) {
  width: 100%;
}

:deep(.side-menu .el-menu-item) {
  height: 42px;
  line-height: 42px;
  margin: 2px 0;
  border-radius: 8px;
  color: var(--nms-sidebar-text) !important;
}

:deep(.side-menu .el-menu-item:hover) {
  background: var(--nms-sidebar-hover) !important;
}

:deep(.side-menu .el-menu-item.is-active) {
  background: var(--nms-sidebar-active) !important;
  color: var(--nms-menu-active-color) !important;
  font-weight: 600;
  box-shadow: inset 3px 0 0 var(--nms-primary);
}

:deep(.side-menu .el-menu-item .el-icon) {
  font-size: 18px;
}

.nav-badge {
  margin-left: 8px;
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--nms-primary);
  color: #fff;
  line-height: 14px;
  vertical-align: middle;
}

.aside-foot {
  padding: 12px 16px 16px;
  font-size: 11px;
  color: var(--nms-aside-foot);
  letter-spacing: 0.06em;
  border-top: 1px solid var(--nms-brand-border);
}
.aside-edition {
  font-weight: 600;
  text-transform: none;
  letter-spacing: 0.02em;
}
.aside-customer {
  margin-top: 4px;
  opacity: 0.85;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-transform: none;
  letter-spacing: 0;
}
.aside-ver {
  margin-top: 4px;
  opacity: 0.75;
  letter-spacing: 0.02em;
  text-transform: none;
  font-size: 10px;
}

.app-main-wrap {
  min-width: 0;
}

.app-header {
  background: rgba(255, 255, 255, 0.94);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid var(--nms-border-soft);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  box-shadow: 0 1px 0 rgba(15, 35, 60, 0.03);
}

.header-left,
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.crumb {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.crumb-root {
  color: var(--nms-text-muted);
}

.crumb-sep {
  color: #c0c8d4;
}

.crumb-cur {
  color: var(--nms-text);
  font-weight: 600;
}

.header-btn {
  border-color: var(--nms-border);
  color: var(--nms-text-secondary);
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 4px 4px 6px;
  border-radius: 999px;
  border: 1px solid var(--nms-border-soft);
  background: #f7f9fc;
}

.user-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: linear-gradient(145deg, var(--el-color-primary-light-3), var(--nms-primary));
  color: #fff;
  font-size: 13px;
  font-weight: 650;
  display: flex;
  align-items: center;
  justify-content: center;
}

.user-meta {
  line-height: 1.2;
  padding-right: 4px;
}

.user-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--nms-text);
}

.user-role {
  font-size: 11px;
  color: var(--nms-text-muted);
}

.logout-btn {
  margin-right: 6px;
  color: var(--nms-text-secondary) !important;
}

.app-main {
  height: calc(100vh - 56px);
  overflow: auto;
  box-sizing: border-box;
}
</style>

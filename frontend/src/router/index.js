import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    name: 'Dashboard',
    component: () => import('@/views/Dashboard.vue')
  },
  {
    path: '/devices',
    name: 'Devices',
    component: () => import('@/views/device/DeviceList.vue'),
    meta: { permissions: ['devices:read'] }
  },
  {
    path: '/performance',
    name: 'Performance',
    component: () => import('@/views/performance/PerformanceView.vue'),
    meta: { permissions: ['performance:read'] }
  },
  {
    path: '/alarms',
    name: 'Alarms',
    component: () => import('@/views/alarm/AlarmView.vue'),
    meta: { permissions: ['alarms:read'] }
  },
  {
    path: '/aiops',
    component: () => import('@/views/aiops/AiopsHub.vue'),
    meta: { permissions: ['aiops:read', 'alarms:read'] },
    children: [
      { path: '', redirect: '/aiops/overview' },
      {
        path: 'overview',
        name: 'AiopsOverview',
        component: () => import('@/views/aiops/AiopsOverview.vue'),
        meta: { permissions: ['aiops:read', 'alarms:read'] }
      },
      {
        path: 'workbench',
        name: 'AiopsWorkbench',
        component: () => import('@/views/aiops/AiopsCenter.vue'),
        meta: { permissions: ['aiops:read', 'alarms:read'] }
      },
      {
        path: 'automation',
        name: 'AiopsAutomation',
        component: () => import('@/views/aiops/AiopsAutomation.vue'),
        meta: { permissions: ['aiops:read', 'alarms:read'] }
      },
      {
        path: 'policy',
        name: 'AiopsPolicy',
        component: () => import('@/views/aiops/AiopsPolicy.vue'),
        meta: { permissions: ['aiops:read', 'alarms:read'] }
      }
    ]
  },
  {
    path: '/aiops/screen',
    name: 'AiopsScreen',
    component: () => import('@/views/aiops/AiopsSmartScreen.vue'),
    meta: { permissions: ['aiops:read', 'alarms:read'] }
  },
  {
    path: '/configs',
    name: 'Configs',
    component: () => import('@/views/config/ConfigManage.vue'),
    meta: { permissions: ['configs:read'] }
  },
  {
    path: '/users',
    name: 'Users',
    component: () => import('@/views/user/UserManage.vue'),
    meta: { permissions: ['users:manage', 'roles:manage'] }
  },
  {
    path: '/audit',
    name: 'AuditCenter',
    component: () => import('@/views/audit/AuditCenter.vue'),
    meta: { permissions: ['audit:read', 'configs:read'] }
  },
  {
    path: '/audit-logs',
    redirect: (to) => ({ path: '/audit', query: { ...to.query, tab: to.query.tab || 'ops' } })
  },
  {
    path: '/topology',
    name: 'Topology',
    component: () => import('@/views/topology/TopologyView.vue'),
    meta: { permissions: ['topology:read'] }
  },
  {
    path: '/alerts',
    redirect: '/alarms'
  },
  {
    path: '/snmp-test',
    name: 'SnmpTest',
    component: () => import('@/views/SnmpTest.vue'),
    meta: { permissions: ['system:test'] }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    if (auth.isAuthenticated && to.path === '/login' && !auth.user?.mustChangePassword) {
      return '/'
    }
    return true
  }
  if (!auth.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (auth.user?.mustChangePassword) {
    return { path: '/login' }
  }
  const required = to.meta.permissions
  if (Array.isArray(required) && required.length > 0) {
    if (!auth.hasAnyPermission(...required)) {
      return '/'
    }
  }
  return true
})

export default router

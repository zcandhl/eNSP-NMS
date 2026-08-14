import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

const STORAGE_KEY = 'ensp-nms-theme'

/** 主题色预设（商用网管常见配色） */
export const ACCENT_PRESETS = [
  {
    id: 'ocean',
    name: '海光蓝',
    primary: '#2b7de9',
    hover: '#1f6ad0',
    soft: '#eaf2fd',
    light3: '#6da4f0',
    light5: '#95bdf5',
    light7: '#bed6f9',
    light8: '#d2e3fb',
    light9: '#eaf2fd'
  },
  {
    id: 'azure',
    name: '晴空蓝',
    primary: '#1890ff',
    hover: '#0f78db',
    soft: '#e6f4ff',
    light3: '#69b1ff',
    light5: '#91caff',
    light7: '#bae0ff',
    light8: '#d6ebff',
    light9: '#e6f4ff'
  },
  {
    id: 'teal',
    name: '青碧',
    primary: '#0d9488',
    hover: '#0f766e',
    soft: '#e6f7f5',
    light3: '#2dd4bf',
    light5: '#5eead4',
    light7: '#99f6e4',
    light8: '#ccfbf1',
    light9: '#e6f7f5'
  },
  {
    id: 'emerald',
    name: '翠绿',
    primary: '#059669',
    hover: '#047857',
    soft: '#ecfdf5',
    light3: '#34d399',
    light5: '#6ee7b7',
    light7: '#a7f3d0',
    light8: '#d1fae5',
    light9: '#ecfdf5'
  },
  {
    id: 'indigo',
    name: '靛蓝',
    primary: '#4f46e5',
    hover: '#4338ca',
    soft: '#eef2ff',
    light3: '#818cf8',
    light5: '#a5b4fc',
    light7: '#c7d2fe',
    light8: '#dde3fe',
    light9: '#eef2ff'
  },
  {
    id: 'violet',
    name: '青莲',
    primary: '#7c3aed',
    hover: '#6d28d9',
    soft: '#f5f3ff',
    light3: '#a78bfa',
    light5: '#c4b5fd',
    light7: '#ddd6fe',
    light8: '#ede9fe',
    light9: '#f5f3ff'
  },
  {
    id: 'slate',
    name: '岩灰',
    primary: '#475569',
    hover: '#334155',
    soft: '#f1f5f9',
    light3: '#64748b',
    light5: '#94a3b8',
    light7: '#cbd5e1',
    light8: '#e2e8f0',
    light9: '#f1f5f9'
  },
  {
    id: 'amber',
    name: '琥珀',
    primary: '#d97706',
    hover: '#b45309',
    soft: '#fffbeb',
    light3: '#fbbf24',
    light5: '#fcd34d',
    light7: '#fde68a',
    light8: '#fef3c7',
    light9: '#fffbeb'
  }
]

/** 外观：侧栏 / 整体明暗 */
export const SHELL_PRESETS = [
  {
    id: 'light',
    name: '明亮',
    desc: '浅色侧栏（推荐）',
    sidebarBg: 'linear-gradient(180deg, #ffffff 0%, #f7f9fc 100%)',
    sidebarBorder: '#e6ebf2',
    sidebarText: '#5c6b7f',
    sidebarActiveText: '#1a2433',
    sidebarHover: 'rgba(26, 111, 181, 0.06)',
    sidebarActive: 'rgba(26, 111, 181, 0.12)',
    brandName: '#1a2433',
    brandTag: '#8a96a8',
    brandBorder: '#e6ebf2',
    asideFoot: '#8a96a8',
    menuActiveColor: 'var(--nms-primary)',
    darkMenu: false
  },
  {
    id: 'soft',
    name: '柔灰',
    desc: '浅灰侧栏',
    sidebarBg: 'linear-gradient(180deg, #f4f6f9 0%, #eef1f6 100%)',
    sidebarBorder: '#dde3ec',
    sidebarText: '#5c6b7f',
    sidebarActiveText: '#1a2433',
    sidebarHover: 'rgba(0, 0, 0, 0.04)',
    sidebarActive: 'rgba(26, 111, 181, 0.14)',
    brandName: '#1a2433',
    brandTag: '#8a96a8',
    brandBorder: '#dde3ec',
    asideFoot: '#8a96a8',
    menuActiveColor: 'var(--nms-primary)',
    darkMenu: false
  },
  {
    id: 'classic',
    name: '经典',
    desc: '深蓝侧栏',
    sidebarBg: 'linear-gradient(180deg, #0d243a 0%, #0b1f33 48%, #091828 100%)',
    sidebarBorder: 'rgba(255,255,255,0.06)',
    sidebarText: '#9db0c7',
    sidebarActiveText: '#ffffff',
    sidebarHover: 'rgba(255, 255, 255, 0.06)',
    sidebarActive: 'rgba(26, 111, 181, 0.28)',
    brandName: '#ffffff',
    brandTag: '#7f94ad',
    brandBorder: 'rgba(255,255,255,0.06)',
    asideFoot: '#5d738c',
    menuActiveColor: '#ffffff',
    darkMenu: true
  }
]

function hexToRgb(hex) {
  const h = hex.replace('#', '')
  const n = parseInt(h.length === 3 ? h.split('').map((c) => c + c).join('') : h, 16)
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 }
}

function withAlpha(hex, alpha) {
  const { r, g, b } = hexToRgb(hex)
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

function loadSaved() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    return JSON.parse(raw)
  } catch {
    return null
  }
}

export const useThemeStore = defineStore('theme', () => {
  const saved = loadSaved()
  const accentId = ref(saved?.accentId || 'ocean')
  const shellId = ref(saved?.shellId || 'light')
  const revision = ref(0)

  const accent = computed(
    () => ACCENT_PRESETS.find((p) => p.id === accentId.value) || ACCENT_PRESETS[0]
  )
  const shell = computed(
    () => SHELL_PRESETS.find((p) => p.id === shellId.value) || SHELL_PRESETS[0]
  )

  function persist() {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ accentId: accentId.value, shellId: shellId.value })
    )
  }

  function applyToDom() {
    const root = document.documentElement
    const a = accent.value
    const s = shell.value
    const { r, g, b } = hexToRgb(a.primary)

    root.setAttribute('data-accent', a.id)
    root.setAttribute('data-shell', s.id)

    const vars = {
      '--nms-primary': a.primary,
      '--nms-primary-hover': a.hover,
      '--nms-primary-soft': a.soft,
      '--nms-sidebar-bg': s.sidebarBg,
      '--nms-sidebar-border': s.sidebarBorder,
      '--nms-sidebar-text': s.sidebarText,
      '--nms-sidebar-active-text': s.sidebarActiveText,
      '--nms-sidebar-hover': s.darkMenu ? s.sidebarHover : withAlpha(a.primary, 0.08),
      '--nms-sidebar-active': s.darkMenu ? withAlpha(a.primary, 0.28) : withAlpha(a.primary, 0.12),
      '--nms-brand-name': s.brandName,
      '--nms-brand-tag': s.brandTag,
      '--nms-brand-border': s.brandBorder,
      '--nms-aside-foot': s.asideFoot,
      '--nms-menu-active-color': s.darkMenu ? '#ffffff' : a.primary,
      '--el-color-primary': a.primary,
      '--el-color-primary-light-3': a.light3,
      '--el-color-primary-light-5': a.light5,
      '--el-color-primary-light-7': a.light7,
      '--el-color-primary-light-8': a.light8,
      '--el-color-primary-light-9': a.light9,
      '--el-color-primary-dark-2': a.hover,
      '--el-color-primary-rgb': `${r}, ${g}, ${b}`
    }

    Object.entries(vars).forEach(([k, v]) => root.style.setProperty(k, v))
    revision.value += 1
    if (typeof window !== 'undefined') {
      window.dispatchEvent(
        new CustomEvent('nms-theme-change', {
          detail: { accentId: accentId.value, shellId: shellId.value, primary: a.primary }
        })
      )
    }
  }

  function setAccent(id) {
    if (!ACCENT_PRESETS.some((p) => p.id === id)) return
    accentId.value = id
    persist()
    applyToDom()
  }

  function setShell(id) {
    if (!SHELL_PRESETS.some((p) => p.id === id)) return
    shellId.value = id
    persist()
    applyToDom()
  }

  function init() {
    applyToDom()
  }

  return {
    accentId,
    shellId,
    revision,
    accent,
    shell,
    setAccent,
    setShell,
    init,
    applyToDom
  }
})

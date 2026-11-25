/**
 * @file    useThemeColors.js
 * @module  组合式逻辑
 * @desc    主题色计算与 CSS 变量注入。
 *
 * @author  ZC
 * @date    2026-08-15 * @version 1.0.0
 * @requires pinia, vue, @/stores/theme
 */

import { watch, onMounted, onUnmounted } from 'vue'
import { storeToRefs } from 'pinia'
import { useThemeStore } from '@/stores/theme'

export function readThemePrimary() {
  if (typeof document === 'undefined') return '#2b7de9'
  return (
    getComputedStyle(document.documentElement).getPropertyValue('--nms-primary').trim() ||
    '#2b7de9'
  )
}

export function readThemePrimaryRgb() {
  if (typeof document === 'undefined') return '43, 125, 233'
  const v = getComputedStyle(document.documentElement).getPropertyValue('--el-color-primary-rgb').trim()
  if (v) return v
  const hex = readThemePrimary().replace('#', '')
  const n = parseInt(hex.length === 3 ? hex.split('').map((c) => c + c).join('') : hex, 16)
  return `${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}`
}

export function primaryAlpha(alpha) {
  return `rgba(${readThemePrimaryRgb()}, ${alpha})`
}

export function useThemeSync(callback) {
  const theme = useThemeStore()
  const { accentId, shellId, revision } = storeToRefs(theme)

  const run = () => {
    try {
      callback?.()
    } catch (e) {
      console.warn('theme sync callback failed', e)
    }
  }

  watch([accentId, shellId, revision], () => run())

  const onWin = () => run()
  onMounted(() => window.addEventListener('nms-theme-change', onWin))
  onUnmounted(() => window.removeEventListener('nms-theme-change', onWin))
}

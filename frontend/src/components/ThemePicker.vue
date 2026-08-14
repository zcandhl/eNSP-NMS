<template>
  <el-popover
    :placement="placement"
    :width="320"
    trigger="click"
    popper-class="nms-theme-popper"
  >
    <template #reference>
      <el-button class="theme-trigger" :class="{ compact }" :title="'外观与主题色'">
        <span class="swatch" :style="{ background: accent.primary }" />
        <span v-if="!compact" class="trigger-label">主题</span>
        <el-icon v-if="!compact"><ArrowDown /></el-icon>
      </el-button>
    </template>

    <div class="theme-panel">
      <div class="section">
        <div class="section-title">外观</div>
        <div class="shell-grid">
          <button
            v-for="s in SHELL_PRESETS"
            :key="s.id"
            type="button"
            class="shell-item"
            :class="{ active: shellId === s.id }"
            @click="theme.setShell(s.id)"
          >
            <span class="shell-preview" :class="s.id" />
            <span class="shell-meta">
              <b>{{ s.name }}</b>
              <small>{{ s.desc }}</small>
            </span>
          </button>
        </div>
      </div>

      <div class="section">
        <div class="section-title">主题色</div>
        <div class="accent-grid">
          <button
            v-for="a in ACCENT_PRESETS"
            :key="a.id"
            type="button"
            class="accent-item"
            :class="{ active: accentId === a.id }"
            :title="a.name"
            @click="theme.setAccent(a.id)"
          >
            <span class="accent-dot" :style="{ background: a.primary }" />
            <span class="accent-name">{{ a.name }}</span>
          </button>
        </div>
      </div>
    </div>
  </el-popover>
</template>

<script setup>
import { storeToRefs } from 'pinia'
import { ArrowDown } from '@element-plus/icons-vue'
import { useThemeStore, ACCENT_PRESETS, SHELL_PRESETS } from '@/stores/theme'

defineProps({
  compact: { type: Boolean, default: false },
  placement: { type: String, default: 'bottom-end' }
})

const theme = useThemeStore()
const { accentId, shellId, accent } = storeToRefs(theme)
</script>

<style scoped>
.theme-trigger {
  border-color: var(--nms-border);
  color: var(--nms-text-secondary);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.theme-trigger.compact {
  padding: 8px;
}
.swatch {
  width: 14px;
  height: 14px;
  border-radius: 4px;
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.12);
  flex-shrink: 0;
}
.trigger-label {
  font-size: 13px;
}

.theme-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.section-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--nms-text-secondary);
  margin-bottom: 10px;
  letter-spacing: 0.02em;
}
.shell-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.shell-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--nms-border-soft);
  border-radius: 8px;
  background: #fff;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: inherit;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.shell-item:hover {
  border-color: #c5d0e0;
}
.shell-item.active {
  border-color: var(--nms-primary);
  box-shadow: 0 0 0 1px var(--nms-primary-soft);
  background: var(--nms-primary-soft);
}
.shell-preview {
  width: 36px;
  height: 28px;
  border-radius: 5px;
  border: 1px solid #d5dde8;
  background: #fff;
  position: relative;
  flex-shrink: 0;
  overflow: hidden;
}
.shell-preview::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 10px;
}
.shell-preview.light::before {
  background: #fff;
  border-right: 1px solid #e6ebf2;
}
.shell-preview.soft::before {
  background: #eef1f6;
  border-right: 1px solid #dde3ec;
}
.shell-preview.classic::before {
  background: #0b1f33;
}
.shell-preview::after {
  content: '';
  position: absolute;
  inset: 4px 4px 4px 14px;
  border-radius: 2px;
  background: #f0f4f8;
}
.shell-meta {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.shell-meta b {
  font-size: 13px;
  font-weight: 600;
  color: var(--nms-text);
}
.shell-meta small {
  font-size: 11px;
  color: var(--nms-text-muted);
}

.accent-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}
.accent-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--nms-border-soft);
  border-radius: 8px;
  background: #fff;
  cursor: pointer;
  font: inherit;
  color: inherit;
  transition: border-color 0.15s, background 0.15s;
}
.accent-item:hover {
  border-color: #c5d0e0;
}
.accent-item.active {
  border-color: var(--nms-primary);
  background: var(--nms-primary-soft);
}
.accent-dot {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.1);
  flex-shrink: 0;
}
.accent-name {
  font-size: 12px;
  color: var(--nms-text);
}
</style>

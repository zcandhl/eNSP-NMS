<template>
  <aside
    class="ops-inline"
    :class="{
      'is-open': open,
      'is-collapsed': !open,
      'is-fill': fillParent
    }"
  >
    <button
      v-if="!open"
      type="button"
      class="ops-inline-rail"
      title="展开运维辅助（诊断 / 工具动作）"
      @click="expand"
    >
      <el-icon :size="16"><Tools /></el-icon>
      <span class="ops-inline-rail-text">运维辅助</span>
    </button>
    <div v-else class="ops-inline-body">
      <OpsAssistantPanel embedded @collapse="collapse" />
    </div>
  </aside>
</template>

<script setup>
import { Tools } from '@element-plus/icons-vue'
import OpsAssistantPanel from '@/components/ops-assistant/OpsAssistantPanel.vue'
import { useOpsInlinePanel } from '@/composables/useOpsInlinePanel'

const props = defineProps({
  storageKey: { type: String, required: true },
  defaultOpen: { type: Boolean, default: false },
  /** 工作台等已锁视口高度：填满父列，不用 sticky */
  fillParent: { type: Boolean, default: false }
})

const { open, expand, collapse } = useOpsInlinePanel(props.storageKey, {
  defaultOpen: props.defaultOpen
})

defineExpose({ open, expand, collapse })
</script>

<style scoped>
.ops-inline {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow-anchor: none;
}
/* 业务页（告警/性能/配置）：贴视口，不撑高整页 */
.ops-inline:not(.is-fill) {
  align-self: start;
  position: sticky;
  top: 12px;
  max-height: calc(100vh - 72px);
}
.ops-inline.is-fill {
  align-self: stretch;
  height: 100%;
  max-height: 100%;
}
.ops-inline.is-collapsed:not(.is-fill) {
  width: 40px;
  flex: 0 0 40px;
  height: min(420px, calc(100vh - 72px));
}
.ops-inline.is-collapsed.is-fill {
  width: 40px;
  flex: 0 0 40px;
}
.ops-inline.is-open:not(.is-fill) {
  flex: 0 0 auto;
  width: min(360px, 38vw);
  min-width: 300px;
  height: calc(100vh - 72px);
  max-height: calc(100vh - 72px);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  overflow: hidden;
  background: var(--el-bg-color);
  box-shadow: 0 1px 2px rgba(15, 35, 60, 0.04);
}
.ops-inline.is-open.is-fill {
  flex: 1 1 auto;
  width: min(360px, 38vw);
  min-width: 300px;
  height: 100%;
  max-height: 100%;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  overflow: hidden;
  background: var(--el-bg-color);
}

.ops-inline-rail {
  width: 40px;
  flex: 1;
  min-height: 0;
  height: 100%;
  border: 1px dashed var(--el-color-primary-light-5);
  border-radius: 10px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  cursor: pointer;
  padding: 14px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  transition: background 0.15s, border-color 0.15s;
}
.ops-inline-rail:hover {
  background: var(--el-color-primary-light-8);
  border-style: solid;
}
.ops-inline-rail-text {
  writing-mode: vertical-rl;
  letter-spacing: 0.18em;
  font-size: 13px;
  font-weight: 600;
}

.ops-inline-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}
.ops-inline-body :deep(.oap) {
  flex: 1;
  height: 100%;
  min-height: 0;
  border: none;
  border-radius: 0;
}

@media (max-width: 1100px) {
  .ops-inline:not(.is-fill) {
    position: static;
    top: auto;
    max-height: none;
  }
  .ops-inline.is-collapsed {
    width: 100%;
    flex: 0 0 auto;
    height: auto;
  }
  .ops-inline-rail {
    width: 100%;
    min-height: 44px;
    height: auto;
    flex-direction: row;
    justify-content: center;
    padding: 8px 12px;
    gap: 8px;
  }
  .ops-inline-rail-text {
    writing-mode: horizontal-tb;
    letter-spacing: 0.04em;
  }
  .ops-inline.is-open:not(.is-fill) {
    width: 100%;
    height: min(520px, 70vh);
    max-height: min(520px, 70vh);
  }
}
</style>

/**
 * @file    HeaderProjectSwitcher.vue
 * @module  通用组件
 * @desc    顶栏验收项目切换组件。
 *
 * @author  ZC
 * @date    2026-11-22 * @version 1.0.0
 * @requires @element-plus/icons-vue, element-plus, pinia, vue, vue-router, @/stores/project
 */

<template>
  <el-dropdown trigger="click" placement="bottom-end" @command="onCommand">
    <button type="button" class="ps-btn" :class="{ empty: !current }" title="当前工程：设备、告警、性能、拓扑、验收均按此工程过滤">
      <el-icon class="ps-icon"><OfficeBuilding /></el-icon>
      <span class="ps-name">{{ current?.name || '未选择工程' }}</span>
      <span v-if="current" class="ps-meta">{{ current.deviceCount || 0 }} 台</span>
      <el-icon class="ps-arrow"><ArrowDown /></el-icon>
    </button>

    <template #dropdown>
      <el-dropdown-menu class="ps-menu">
        <div class="ps-menu-head">
          <span>工程上下文</span>
          <em>切换后全局跟随</em>
        </div>
        <el-dropdown-item
          v-for="p in projects"
          :key="p.id"
          :command="'switch:' + p.id"
          class="ps-item"
          :class="{ active: p.id === projectId }"
        >
          <div class="pi-body">
            <div class="pi-name">
              <span class="pi-dot" :class="{ on: p.id === projectId }"></span>
              <b :title="p.name">{{ p.name }}</b>
            </div>
            <div class="pi-meta">{{ p.building || '未填写建筑' }}</div>
          </div>
          <span class="pi-count">{{ p.deviceCount || 0 }} 台</span>
          <el-icon v-if="p.id === projectId" class="pi-check"><Check /></el-icon>
        </el-dropdown-item>
        <div v-if="!projects.length" class="ps-none">还没有工程，先建一个</div>
        <el-dropdown-item divided command="manage" class="ps-action">
          <el-icon><Setting /></el-icon>
          工程管理
        </el-dropdown-item>
        <el-dropdown-item command="create" class="ps-action">
          <el-icon><Plus /></el-icon>
          新建工程
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup>
import { onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { OfficeBuilding, ArrowDown, Check, Setting, Plus } from '@element-plus/icons-vue'
import { useProjectStore } from '@/stores/project'

// 顶栏工程上下文：不仅"选一个工程"，还带工程规模摘要与工程管理入口。
// 切换后各模块监听store.projectId自行刷新。
const router = useRouter()
const store = useProjectStore()
const { projects, projectId, current } = storeToRefs(store)

onMounted(() => {
  if (!store.loaded) store.load()
})

function onCommand(cmd) {
  if (typeof cmd === 'string' && cmd.startsWith('switch:')) {
    const id = Number(cmd.slice(7))
    if (id === projectId.value) return
    store.switchTo(id)
    const p = projects.value.find((x) => x.id === id)
    ElMessage.success(`已切换到「${p?.name || id}」`)
    return
  }
  if (cmd === 'manage') {
    router.push('/projects')
    return
  }
  if (cmd === 'create') {
    router.push({ path: '/projects', query: { create: '1' } })
  }
}
</script>

<style scoped>
.ps-btn {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 32px;
  max-width: 260px;
  padding: 0 10px 0 9px;
  border: 1px solid var(--nms-border);
  border-radius: 16px;
  background: #f7f9fc;
  cursor: pointer;
  font-family: inherit;
  font-size: 13px;
  color: var(--nms-text);
  transition: border-color 0.15s, background 0.15s;
}

.ps-btn:hover {
  border-color: var(--el-color-primary-light-5);
  background: #fff;
}

.ps-btn.empty {
  color: var(--nms-text-muted);
}

.ps-icon {
  flex: 0 0 auto;
  font-size: 15px;
  color: var(--nms-primary);
}

.ps-btn.empty .ps-icon {
  color: var(--nms-text-muted);
}

.ps-name {
  min-width: 0;
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}

.ps-meta {
  flex: 0 0 auto;
  font-size: 11px;
  line-height: 1;
  padding: 2px 6px;
  border-radius: 9px;
  background: var(--nms-primary-soft, #eaf2fd);
  color: var(--nms-primary);
  font-variant-numeric: tabular-nums;
}

.ps-arrow {
  flex: 0 0 auto;
  font-size: 12px;
  color: var(--nms-text-muted);
}

.ps-menu {
  min-width: 268px;
  padding-top: 0;
}

.ps-menu-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 10px 14px 8px;
  border-bottom: 1px solid var(--nms-border-soft);

  span {
    font-size: 12px;
    font-weight: 600;
    color: var(--nms-text-secondary);
  }

  em {
    font-style: normal;
    font-size: 11px;
    color: var(--nms-text-muted);
  }
}

.ps-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  height: auto;
  line-height: 1.4;
}

.ps-item.active {
  background: var(--nms-primary-soft, #eaf2fd);
}

.pi-body {
  flex: 1 1 auto;
  min-width: 0;
}

.pi-name {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;

  b {
    font-size: 13px;
    font-weight: 500;
    color: var(--nms-text);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.pi-dot {
  flex: 0 0 auto;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #ccd4df;
}

.pi-dot.on {
  background: var(--nms-primary);
}

.pi-meta {
  margin-top: 2px;
  font-size: 11px;
  color: var(--nms-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pi-count {
  flex: 0 0 auto;
  font-size: 12px;
  color: var(--nms-text-secondary);
  font-variant-numeric: tabular-nums;
}

.pi-check {
  flex: 0 0 auto;
  color: var(--nms-primary);
  font-size: 14px;
}

.ps-none {
  padding: 14px;
  font-size: 12px;
  color: var(--nms-text-muted);
  text-align: center;
}

.ps-action {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  color: var(--nms-text-secondary);
}
</style>

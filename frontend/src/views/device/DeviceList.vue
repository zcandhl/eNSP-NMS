<template>
  <div class="nms-page device-list">
    <div class="nms-page-header">
      <div class="nms-page-title-block">
        <h1 class="nms-page-title">设备管理</h1>
        <p class="nms-page-subtitle">资产台账 · 状态探测 · 运维入口。点击设备名打开详情。</p>
      </div>
      <div class="nms-page-actions">
        <el-button v-permission="'devices:write'" @click="showGroupManageDialog">设备分组</el-button>
        <el-button v-permission="'devices:discover'" @click="openDiscoverDialog">
          <el-icon><Search /></el-icon>
          发现设备
        </el-button>
        <el-button v-permission="'devices:write'" type="primary" @click="openAddDialog">
          <el-icon><Plus /></el-icon>
          添加设备
        </el-button>
      </div>
    </div>

    <div class="nms-kpi-row">
      <button type="button" class="nms-kpi" :class="{ active: !filters.status && filters.groupId == null }" @click="clearQuickFilters">
        <div class="nms-kpi-icon"><el-icon><Box /></el-icon></div>
        <div class="nms-kpi-meta">
          <div class="nms-kpi-label">全部设备</div>
          <div class="nms-kpi-value">{{ stats.total }}</div>
        </div>
      </button>
      <button type="button" class="nms-kpi" :class="{ active: filters.status === 'online' }" @click="setStatusFilter('online')">
        <div class="nms-kpi-icon online"><el-icon><CircleCheck /></el-icon></div>
        <div class="nms-kpi-meta">
          <div class="nms-kpi-label">在线</div>
          <div class="nms-kpi-value online">{{ stats.online }}</div>
        </div>
      </button>
      <button type="button" class="nms-kpi" :class="{ active: filters.status === 'offline' }" @click="setStatusFilter('offline')">
        <div class="nms-kpi-icon offline"><el-icon><WarningFilled /></el-icon></div>
        <div class="nms-kpi-meta">
          <div class="nms-kpi-label">离线</div>
          <div class="nms-kpi-value offline">{{ stats.offline }}</div>
        </div>
      </button>
      <button type="button" class="nms-kpi" :class="{ active: filters.groupId === -1 }" @click="setUngroupedFilter">
        <div class="nms-kpi-icon muted"><el-icon><FolderOpened /></el-icon></div>
        <div class="nms-kpi-meta">
          <div class="nms-kpi-label">未分组 · SSH {{ stats.withSsh }}</div>
          <div class="nms-kpi-value">{{ stats.ungrouped }}</div>
        </div>
      </button>
    </div>

    <div class="nms-panel">
      <div class="nms-panel-toolbar">
        <el-input
          v-model="filters.keyword"
          clearable
          placeholder="搜索名称 / IP / 型号 / 位置 / 序列号"
          style="width: 280px"
          @keyup.enter="onSearch"
          @clear="onSearch"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select
          v-model="filters.deviceType"
          clearable
          placeholder="类型"
          style="width: 130px"
          @change="onSearch"
        >
          <el-option label="全部类型" value="" />
          <el-option
            v-for="opt in DEVICE_TYPE_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <el-select
          v-model="filters.monitorMode"
          clearable
          placeholder="监控方式"
          style="width: 150px"
          @change="onSearch"
        >
          <el-option label="全部方式" value="" />
          <el-option
            v-for="opt in MONITOR_MODE_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
        <el-select
          v-model="filters.groupId"
          clearable
          placeholder="设备分组"
          style="width: 150px"
          @change="onSearch"
        >
          <el-option label="未分组" :value="-1" />
          <el-option
            v-for="g in deviceGroups"
            :key="g.id"
            :label="`${g.name}（${g.deviceCount || 0}）`"
            :value="g.id"
          />
        </el-select>
        <div class="toolbar-spacer" />
        <span v-if="autoRefreshHint" class="auto-refresh-hint">{{ autoRefreshHint }}</span>
        <el-button @click="reloadList" :loading="loading && !silentRefreshing">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
        <el-dropdown trigger="click" @command="onExportCommand">
          <el-button :loading="exporting">
            导出 <el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="all">按筛选导出全部</el-dropdown-item>
              <el-dropdown-item command="page">导出当前页</el-dropdown-item>
              <el-dropdown-item command="selected" :disabled="!selectedRows.length">导出已选（{{ selectedRows.length }}）</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>

      <div v-if="selectedRows.length" class="nms-batch-bar">
        <span>已选 <b>{{ selectedRows.length }}</b> 台</span>
        <el-button v-permission="'devices:write'" size="small" :loading="batchLoading" @click="batchRefresh">批量刷新状态</el-button>
        <el-button v-permission="'devices:write'" size="small" @click="openBatchGroup">调整分组</el-button>
        <el-button v-permission="'devices:write'" size="small" @click="openBatchCred">批量凭证</el-button>
        <el-button v-permission="'devices:write'" size="small" type="danger" @click="batchDelete">批量删除</el-button>
        <el-button size="small" link @click="clearSelection">取消选择</el-button>
      </div>

      <el-table
        ref="tableRef"
        :data="devices"
        style="width: 100%"
        v-loading="loading"
        row-key="id"
        :header-cell-style="{ background: '#f3f6fa' }"
        @selection-change="onSelectionChange"
        @row-dblclick="openDetail"
        @sort-change="onSortChange"
      >
        <el-table-column type="selection" width="42" />
        <el-table-column label="设备" min-width="220">
          <template #default="{ row }">
            <div class="nms-device-cell">
              <div class="nms-device-avatar" :class="(row.deviceType || 'other').toLowerCase()">
                <el-icon><component :is="deviceTypeIcon(row.deviceType)" /></el-icon>
              </div>
              <div class="nms-device-body">
                <button type="button" class="nms-device-name" @click="openDetail(row)">{{ row.name }}</button>
                <div class="dev-type-line">
                  {{ deviceTypeLabel(row.deviceType) }}
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="ipAddress" label="管理 IP" width="140" sortable="custom">
          <template #default="{ row }">
            <span class="nms-ip">{{ row.ipAddress || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="location" label="位置" min-width="100" show-overflow-tooltip />
        <el-table-column prop="model" label="型号" min-width="100" show-overflow-tooltip />
        <el-table-column prop="vendor" label="厂商" width="90" show-overflow-tooltip />
        <el-table-column label="分组" width="100" show-overflow-tooltip>
          <template #default="{ row }">
            {{ groupNameOf(row.groupId) }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110" prop="status" sortable="custom">
          <template #default="{ row }">
            <el-tooltip :content="statusTooltip(row)" placement="top">
              <span class="nms-status" :class="row.status === 'online' ? 'is-online' : 'is-offline'">
                <span class="nms-status-dot" />
                {{ row.status === 'online' ? '在线' : '离线' }}
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="最后在线" width="158" prop="lastSeen" sortable="custom">
          <template #default="{ row }">
            <span class="last-seen">{{ row.lastSeen ? new Date(row.lastSeen).toLocaleString() : '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="148" fixed="right">
          <template #default="{ row }">
            <div class="nms-ops">
              <el-tooltip content="刷新状态" placement="top">
                <el-button v-permission="'devices:write'" text circle @click="refreshDevice(row)">
                  <el-icon><Refresh /></el-icon>
                </el-button>
              </el-tooltip>
              <el-tooltip content="编辑" placement="top">
                <el-button v-permission="'devices:write'" text circle @click="editDevice(row)">
                  <el-icon><Edit /></el-icon>
                </el-button>
              </el-tooltip>
              <el-dropdown trigger="click" @command="(cmd) => onOpsCommand(cmd, row)">
                <el-button text circle>
                  <el-icon><MoreFilled /></el-icon>
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="detail">设备详情</el-dropdown-item>
                    <el-dropdown-item
                      v-if="auth.hasAnyPermission('aiops:read', 'alarms:read')"
                      command="diagnose"
                    >诊断此设备</el-dropdown-item>
                    <el-dropdown-item
                      v-if="auth.hasPermission('webssh:connect') && canUseWebSsh(row)"
                      command="terminal"
                    >Web 终端</el-dropdown-item>
                    <el-dropdown-item v-if="auth.hasPermission('topology:read')" command="topology">定位拓扑</el-dropdown-item>
                    <el-dropdown-item v-if="auth.hasPermission('alarms:read')" command="alarms">设备告警</el-dropdown-item>
                    <el-dropdown-item
                      v-if="auth.hasPermission('performance:read') && canCollectPerformance(row)"
                      command="performance"
                    >性能监控</el-dropdown-item>
                    <el-dropdown-item
                      v-if="auth.hasPermission('configs:read') && canConfigBackup(row)"
                      command="configs"
                    >配置管理</el-dropdown-item>
                    <el-dropdown-item
                      v-if="auth.hasPermission('devices:write')"
                      divided
                      command="delete"
                      class="danger-item"
                    >删除设备</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div class="nms-panel-footer">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :total="pagination.total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadDevices"
          @size-change="onPageSizeChange"
        />
      </div>
    </div>
  </div>

  <el-dialog
    v-model="showAddDialog"
    :title="editMode ? '编辑设备' : '添加设备'"
    width="600px"
    destroy-on-close
    @closed="resetForm"
  >
    <el-form :model="deviceForm" label-width="100px">
      <el-form-item label="设备名称">
        <el-input v-model="deviceForm.name" />
      </el-form-item>
      <el-form-item label="IP地址">
        <el-input v-model="deviceForm.ipAddress" />
      </el-form-item>
      <el-form-item label="型号">
        <el-input v-model="deviceForm.model" />
      </el-form-item>
      <el-form-item label="厂商">
        <el-input v-model="deviceForm.vendor" />
      </el-form-item>
      <el-form-item label="序列号">
        <el-input v-model="deviceForm.serialNumber" placeholder="可选" />
      </el-form-item>
      <el-form-item label="位置">
        <el-input v-model="deviceForm.location" placeholder="机房 / 机柜 / 位置" />
      </el-form-item>
      <el-form-item label="联系人">
        <el-input v-model="deviceForm.contact" placeholder="运维联系人" />
      </el-form-item>
      <el-form-item label="设备类型">
        <el-select v-model="deviceForm.deviceType" style="width: 100%" @change="onDeviceTypeChange">
          <el-option
            v-for="opt in DEVICE_TYPE_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="监控方式">
        <el-select v-model="deviceForm.monitorMode" style="width: 100%" :disabled="deviceForm.deviceType === 'pc'">
          <el-option
            v-for="opt in MONITOR_MODE_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
      </el-form-item>
      <el-alert
        v-if="deviceForm.deviceType === 'pc' || deviceForm.monitorMode === 'icmp'"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
        title="虚拟 PC / Ping 监控：使用 ICMP 探测在线状态，不采集 SNMP 性能，默认不启用 SSH 配置备份"
      />
      <template v-if="deviceForm.deviceType !== 'pc' && deviceForm.monitorMode !== 'icmp'">
        <el-divider content-position="left">SNMP配置</el-divider>
        <el-form-item label="版本">
          <el-select v-model="deviceForm.snmpVersion" style="width: 100%">
            <el-option label="v1" value="v1" />
            <el-option label="v2c" value="v2c" />
          </el-select>
        </el-form-item>
        <el-form-item label="Community">
          <el-input v-model="deviceForm.snmpCommunity" />
        </el-form-item>
        <el-form-item label="端口">
          <el-input-number v-model="deviceForm.snmpPort" :min="1" :max="65535" />
        </el-form-item>
        <el-divider content-position="left">SSH配置（可选）</el-divider>
        <el-form-item label="用户名">
          <el-input
            v-model="deviceForm.sshUsername"
            autocomplete="off"
            name="nms-ssh-username"
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            :key="'ssh-pwd-' + (deviceForm.id || 'new') + '-' + formNonce"
            v-model="deviceForm.sshPassword"
            type="password"
            show-password
            clearable
            autocomplete="new-password"
            name="nms-ssh-password"
            :placeholder="editMode ? '输入新密码；留空则保持原密码' : '请输入 SSH 密码'"
          />
        </el-form-item>
        <el-form-item label="端口">
          <el-input-number v-model="deviceForm.sshPort" :min="1" :max="65535" />
        </el-form-item>
        <el-form-item v-if="editMode && deviceForm.id" label="连通性">
          <el-button
            :loading="testingConn"
            @click="testConnectivityFromForm"
          >连通性诊断</el-button>
          <span class="ssh-hint">ICMP / SNMP / SSH（诊断用，不改在线状态）</span>
        </el-form-item>
      </template>
      <el-form-item label="设备分组">
        <el-select v-model="deviceForm.groupId" clearable placeholder="未分组" style="width: 100%">
          <el-option
            v-for="g in deviceGroups"
            :key="g.id"
            :label="g.name"
            :value="g.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="deviceForm.description" type="textarea" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="showAddDialog = false">取消</el-button>
      <el-button type="primary" @click="saveDevice" :loading="saving">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="showDiscoverDialog"
    title="发现设备"
    width="780px"
    :close-on-click-modal="!discovering"
    @closed="onDiscoverClosed"
  >
    <el-form :model="discoverForm" label-width="110px" :disabled="discovering">
      <el-form-item label="网段">
        <el-input v-model="discoverForm.network" placeholder="例如: 192.168.56.0" />
      </el-form-item>
      <el-form-item label="Community">
        <el-input v-model="discoverForm.community" placeholder="默认 public" />
      </el-form-item>
      <el-form-item label="SNMP 端口">
        <el-input-number v-model="discoverForm.snmpPort" :min="1" :max="65535" />
      </el-form-item>
      <el-form-item label="超时(秒)">
        <el-input-number v-model="discoverForm.timeout" :min="5" :max="120" />
      </el-form-item>
    </el-form>

    <div v-if="discoverJob" class="discover-progress">
      <el-progress
        :percentage="discoverProgress"
        :status="discoverProgressStatus"
      />
      <div class="discover-meta">
        {{ discoverJob.message || '准备中…' }}
        <span v-if="discoverJob.total">
          （{{ discoverJob.scanned || 0 }}/{{ discoverJob.total }}，发现 {{ discoverJob.found || 0 }}）
        </span>
      </div>
    </div>

    <el-table
      v-if="discoverCandidates.length"
      ref="discoverTableRef"
      :data="discoverCandidates"
      row-key="ipAddress"
      max-height="320"
      style="margin-top: 12px"
      @selection-change="onDiscoverSelectionChange"
    >
      <el-table-column type="selection" width="48" :selectable="row => !row.alreadyExists" />
      <el-table-column prop="ipAddress" label="IP" width="130" />
      <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          {{ deviceTypeLabel(row.deviceType || (row.discoverSource === 'arp_endpoint' ? 'pc' : 'other')) }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.alreadyExists ? 'info' : 'success'" size="small">
            {{ row.alreadyExists ? '已存在' : '新设备' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="learnedFromDevice" label="来源" width="120" show-overflow-tooltip />
      <el-table-column prop="snmpCommunity" label="Community" width="100" />
    </el-table>

    <template #footer>
      <el-button
        v-permission="'devices:discover'"
        :loading="endpointDiscovering"
        :disabled="discovering"
        @click="discoverEndpoints"
      >ARP 发现终端</el-button>
      <el-button @click="showDiscoverDialog = false" :disabled="discovering">关闭</el-button>
      <el-button
        v-if="!discoverDone"
        type="primary"
        @click="startDiscovery"
        :loading="discovering"
      >
        开始扫描
      </el-button>
      <el-button
        v-else
        type="success"
        :disabled="!selectedCandidates.length"
        :loading="importing"
        @click="importSelected"
      >
        入库选中（{{ selectedCandidates.length }}）
      </el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="groupDialogVisible" title="设备分组" width="720px">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 12px;"
      title="分组归属设备主数据，配置备份/批量下发可按组筛选设备。"
    />
    <div class="toolbar" style="margin-bottom: 12px;">
      <el-input v-model="groupForm.name" placeholder="分组名称" style="width: 180px; margin-right: 8px;" />
      <el-input v-model="groupForm.description" placeholder="描述（可选）" style="width: 220px; margin-right: 8px;" />
      <el-button type="primary" :loading="groupSaving" @click="createDeviceGroup">新建分组</el-button>
    </div>
    <el-table :data="deviceGroups" v-loading="groupLoading" style="width: 100%;">
      <el-table-column prop="name" label="名称" width="160" />
      <el-table-column prop="description" label="描述" show-overflow-tooltip />
      <el-table-column prop="deviceCount" label="设备数" width="90" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link @click="openGroupMembers(row)">成员</el-button>
          <el-button size="small" link type="danger" @click="deleteDeviceGroup(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button @click="groupDialogVisible = false">关闭</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="groupMembersVisible" :title="`分组成员 - ${editingGroup?.name || ''}`" width="560px">
    <el-select
      v-model="groupMemberIds"
      multiple
      filterable
      collapse-tags
      collapse-tags-tooltip
      placeholder="选择设备划入该分组"
      style="width: 100%;"
    >
      <el-option
        v-for="device in allDevicesForGroup"
        :key="device.id"
        :label="deviceGroupOptionLabel(device)"
        :value="device.id"
      />
    </el-select>
    <template #footer>
      <el-button @click="groupMembersVisible = false">取消</el-button>
      <el-button type="primary" :loading="groupSaving" @click="saveGroupMembers">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="batchGroupVisible" title="批量调整分组" width="420px">
    <el-select v-model="batchGroupId" clearable placeholder="未分组" style="width: 100%">
      <el-option
        v-for="g in deviceGroups"
        :key="g.id"
        :label="g.name"
        :value="g.id"
      />
    </el-select>
    <template #footer>
      <el-button @click="batchGroupVisible = false">取消</el-button>
      <el-button type="primary" :loading="batchLoading" @click="submitBatchGroup">确定</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="batchCredVisible" title="批量更新凭证" width="480px">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 12px"
      title="仅填写需要覆盖的字段；密码留空表示不修改"
    />
    <el-form label-width="100px">
      <el-form-item label="SNMP Community">
        <el-input v-model="batchCred.snmpCommunity" placeholder="留空不改" />
      </el-form-item>
      <el-form-item label="SNMP 端口">
        <el-input-number v-model="batchCred.snmpPort" :min="1" :max="65535" controls-position="right" />
      </el-form-item>
      <el-form-item label="SSH 用户名">
        <el-input v-model="batchCred.sshUsername" placeholder="留空不改" />
      </el-form-item>
      <el-form-item label="SSH 密码">
        <el-input v-model="batchCred.sshPassword" type="password" show-password placeholder="留空不改" />
      </el-form-item>
      <el-form-item label="SSH 端口">
        <el-input-number v-model="batchCred.sshPort" :min="1" :max="65535" controls-position="right" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="batchCredVisible = false">取消</el-button>
      <el-button type="primary" :loading="batchLoading" @click="submitBatchCred">应用到选中设备</el-button>
    </template>
  </el-dialog>

  <DeviceDetailDrawer
    ref="detailDrawerRef"
    v-model:visible="detailVisible"
    :device="detailDevice"
    @refresh="refreshDevice"
    @edit="editDevice"
    @terminal="(d) => onOpsCommand('terminal', d)"
  />
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Search, Plus, Refresh, Edit, MoreFilled, Box, CircleCheck, WarningFilled, FolderOpened, Connection, Monitor, Cpu, SetUp } from '@element-plus/icons-vue'
import { deviceApi, deviceGroupApi } from '@/api/device'
import { useDeviceStore } from '@/stores/device'
import { useAuthStore } from '@/stores/auth'
import { ElMessage, ElMessageBox } from 'element-plus'
import { openWebTerminal } from '@/composables/webTerminalBus'
import { askOpsAssistant } from '@/composables/askOpsAssistant'
import { createVisibilityPoller } from '@/composables/useVisibilityPoller'
import DeviceDetailDrawer from '@/components/DeviceDetailDrawer.vue'
import {
  DEVICE_TYPE_OPTIONS,
  MONITOR_MODE_OPTIONS,
  deviceTypeLabel,
  resolveCapabilities,
  canUseWebSsh,
  canCollectPerformance,
  canConfigBackup
} from '@/utils/deviceCapabilities'

const deviceStore = useDeviceStore()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const testingConn = ref(false)
const exporting = ref(false)
const tableRef = ref(null)
const sortState = ref({ prop: 'id', order: 'ascending' })
const selectedRows = ref([])
const batchLoading = ref(false)
const batchGroupVisible = ref(false)
const batchCredVisible = ref(false)
const batchGroupId = ref(null)
const batchCred = ref({
  snmpCommunity: '',
  snmpPort: 161,
  sshUsername: '',
  sshPassword: '',
  sshPort: 22
})
const detailVisible = ref(false)
const detailDevice = ref(null)
const detailDrawerRef = ref(null)
const stats = ref({
  total: 0,
  online: 0,
  offline: 0,
  ungrouped: 0,
  withSsh: 0
})
const emptyForm = () => ({
  id: null,
  name: '',
  ipAddress: '',
  model: '',
  vendor: '',
  deviceType: 'other',
  monitorMode: 'auto',
  snmpVersion: 'v2c',
  snmpCommunity: 'public',
  snmpPort: 161,
  sshUsername: '',
  sshPassword: '',
  sshPort: 22,
  groupId: null,
  location: '',
  contact: '',
  serialNumber: '',
  description: ''
})

const devices = ref([])
const deviceGroups = ref([])
const allDevicesForGroup = ref([])
const loading = ref(false)
const saving = ref(false)
const groupLoading = ref(false)
const groupSaving = ref(false)
const groupDialogVisible = ref(false)
const groupMembersVisible = ref(false)
const editingGroup = ref(null)
const groupMemberIds = ref([])
const groupForm = ref({ name: '', description: '' })
const discovering = ref(false)
const endpointDiscovering = ref(false)
const importing = ref(false)
const showAddDialog = ref(false)
const showDiscoverDialog = ref(false)
const editMode = ref(false)
const formNonce = ref(0)
const deviceForm = ref(emptyForm())
const filters = ref({
  keyword: '',
  status: '',
  groupId: null,
  deviceType: '',
  monitorMode: ''
})
const pagination = ref({
  page: 1,
  size: 20,
  total: 0
})

const discoverForm = ref({
  network: '192.168.56.0',
  timeout: 30,
  community: 'public',
  snmpPort: 161
})
const discoverJob = ref(null)
const discoverCandidates = ref([])
const selectedCandidates = ref([])
const discoverDone = ref(false)
const discoverTableRef = ref(null)
let discoverPollTimer = null

const discoverProgress = computed(() => {
  const job = discoverJob.value
  if (!job || !job.total) return 0
  return Math.min(100, Math.round(((job.scanned || 0) / job.total) * 100))
})

const discoverProgressStatus = computed(() => {
  const status = discoverJob.value?.status
  if (status === 'FAILED') return 'exception'
  if (status === 'COMPLETED') return 'success'
  return undefined
})

const silentRefreshing = ref(false)
const lastSyncedAt = ref(0)
const hintTick = ref(0)
let hintTimer = null

const autoRefreshHint = computed(() => {
  void hintTick.value
  if (!lastSyncedAt.value) return '自动同步 30s'
  const sec = Math.max(0, Math.floor((Date.now() - lastSyncedAt.value) / 1000))
  if (sec < 8) return '状态已同步'
  if (sec < 60) return `${sec}s 前同步`
  return `${Math.floor(sec / 60)}m 前同步`
})

const loadDevices = async (silent = false) => {
  if (silent) {
    if (silentRefreshing.value || loading.value) return
    silentRefreshing.value = true
  } else {
    loading.value = true
  }
  try {
    const sortProp = sortState.value.prop || 'id'
    const sortDir = sortState.value.order === 'descending' ? 'desc' : 'asc'
    const res = await deviceApi.queryDevices({
      keyword: filters.value.keyword || undefined,
      status: filters.value.status || undefined,
      groupId: filters.value.groupId != null ? filters.value.groupId : undefined,
      deviceType: filters.value.deviceType || undefined,
      monitorMode: filters.value.monitorMode || undefined,
      page: Math.max(pagination.value.page - 1, 0),
      size: pagination.value.size,
      sort: `${sortProp},${sortDir}`
    })
    const content = Array.isArray(res?.content) ? res.content : []
    devices.value = content.map(d => ({ ...d }))
    pagination.value.total = Number(res?.totalElements ?? content.length)
    lastSyncedAt.value = Date.now()
  } catch (error) {
    if (!silent) ElMessage.error('加载设备列表失败')
  } finally {
    if (silent) silentRefreshing.value = false
    else loading.value = false
  }
}

const onSortChange = ({ prop, order }) => {
  if (!order) {
    sortState.value = { prop: 'id', order: 'ascending' }
  } else {
    sortState.value = { prop: prop || 'id', order }
  }
  pagination.value.page = 1
  loadDevices()
}

const buildFilterParams = () => ({
  keyword: filters.value.keyword || undefined,
  status: filters.value.status || undefined,
  groupId: filters.value.groupId != null ? filters.value.groupId : undefined,
  deviceType: filters.value.deviceType || undefined,
  monitorMode: filters.value.monitorMode || undefined
})

const loadStats = async () => {
  try {
    const res = await deviceApi.getDeviceStats()
    stats.value = {
      total: Number(res?.total || 0),
      online: Number(res?.online || 0),
      offline: Number(res?.offline || 0),
      ungrouped: Number(res?.ungrouped || 0),
      withSsh: Number(res?.withSsh || 0)
    }
  } catch {
    /* ignore */
  }
}

const refreshListSilent = async () => {
  await Promise.all([loadDevices(true), loadStats()])
}

const listPoller = createVisibilityPoller(refreshListSilent, 30000)

const setStatusFilter = (status) => {
  filters.value.status = status
  filters.value.groupId = null
  onSearch()
}

const setUngroupedFilter = () => {
  filters.value.groupId = -1
  filters.value.status = ''
  onSearch()
}

const clearQuickFilters = () => {
  filters.value.status = ''
  filters.value.groupId = null
  onSearch()
}

const reloadList = () => {
  loadDevices()
  loadStats()
}

const onExportCommand = (cmd) => {
  if (cmd === 'all') {
    exportCsv()
    return
  }
  if (cmd === 'page') {
    exportPageCsv()
    return
  }
  if (cmd === 'selected') {
    exportSelectedCsv()
  }
}

const onSelectionChange = (rows) => {
  selectedRows.value = rows || []
}

const clearSelection = () => {
  selectedRows.value = []
  tableRef.value?.clearSelection?.()
}

const selectedIds = () => selectedRows.value.map((r) => r.id).filter((id) => id != null)

const openDetail = (row) => {
  detailDevice.value = row
  detailVisible.value = true
}

const csvEscape = (v) => {
  const s = v == null ? '' : String(v)
  if (/[",\n]/.test(s)) return `"${s.replace(/"/g, '""')}"`
  return s
}

const rowsToCsv = (rows) => {
  const header = ['ID', '名称', 'IP', '类型', '状态', '型号', '厂商', '位置', '联系人', '序列号', '分组', '监控方式', '最后在线']
  const lines = [header.join(',')]
  rows.forEach((r) => {
    lines.push([
      r.id,
      r.name,
      r.ipAddress,
      deviceTypeLabel(r.deviceType),
      r.status,
      r.model,
      r.vendor,
      r.location,
      r.contact,
      r.serialNumber,
      groupNameOf(r.groupId),
      r.monitorMode,
      r.lastSeen || ''
    ].map(csvEscape).join(','))
  })
  return lines.join('\n')
}

const downloadCsv = (content, filename) => {
  const blob = new Blob(['\ufeff' + content], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

const exportPageCsv = () => {
  if (!devices.value.length) {
    ElMessage.warning('当前页无数据可导出')
    return
  }
  downloadCsv(rowsToCsv(devices.value), `devices-page-${Date.now()}.csv`)
  ElMessage.success('已导出当前页')
}

const exportCsv = async () => {
  exporting.value = true
  try {
    const blob = await deviceApi.exportDevices(buildFilterParams())
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `devices-export-${Date.now()}.csv`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('已按当前筛选导出（最多 5000 台）')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

const exportSelectedCsv = () => {
  if (!selectedRows.value.length) {
    ElMessage.warning('请先选择设备')
    return
  }
  downloadCsv(rowsToCsv(selectedRows.value), `devices-selected-${Date.now()}.csv`)
  ElMessage.success(`已导出 ${selectedRows.value.length} 台`)
}

const batchRefresh = async () => {
  const ids = selectedIds()
  if (!ids.length) return
  batchLoading.value = true
  try {
    const res = await deviceApi.batchRefreshDevices(ids)
    ElMessage.success(`批量刷新完成：成功 ${res?.success ?? 0}，失败 ${res?.failed ?? 0}`)
    deviceStore.invalidate()
    await Promise.all([loadDevices(), loadStats()])
    clearSelection()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '批量刷新失败')
  } finally {
    batchLoading.value = false
  }
}

const batchDelete = async () => {
  const ids = selectedIds()
  if (!ids.length) return
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${ids.length} 台设备？将级联清理拓扑/配置/性能等相关数据。`,
      '批量删除',
      { type: 'warning' }
    )
    batchLoading.value = true
    const res = await deviceApi.batchDeleteDevices(ids)
    ElMessage.success(`已删除 ${res?.success ?? 0} 台`)
    deviceStore.invalidate()
    await Promise.all([loadDevices(), loadStats(), loadDeviceGroups()])
    clearSelection()
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(e?.response?.data?.message || '批量删除失败')
  } finally {
    batchLoading.value = false
  }
}

const openBatchGroup = () => {
  batchGroupId.value = null
  batchGroupVisible.value = true
}

const submitBatchGroup = async () => {
  const ids = selectedIds()
  batchLoading.value = true
  try {
    const res = await deviceApi.batchUpdateGroup(ids, batchGroupId.value)
    ElMessage.success(`已更新分组 ${res?.updated ?? 0} 台`)
    batchGroupVisible.value = false
    deviceStore.invalidate()
    await Promise.all([loadDevices(), loadStats(), loadDeviceGroups()])
    clearSelection()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '批量分组失败')
  } finally {
    batchLoading.value = false
  }
}

const openBatchCred = () => {
  batchCred.value = {
    snmpCommunity: '',
    snmpPort: 161,
    sshUsername: '',
    sshPassword: '',
    sshPort: 22
  }
  batchCredVisible.value = true
}

const submitBatchCred = async () => {
  const ids = selectedIds()
  const c = batchCred.value
  const payload = {}
  if (c.snmpCommunity?.trim()) payload.snmpCommunity = c.snmpCommunity.trim()
  if (c.snmpPort) payload.snmpPort = c.snmpPort
  if (c.sshUsername?.trim()) payload.sshUsername = c.sshUsername.trim()
  if (c.sshPassword?.trim()) payload.sshPassword = c.sshPassword.trim()
  if (c.sshPort) payload.sshPort = c.sshPort
  if (!Object.keys(payload).length) {
    ElMessage.warning('请至少填写一项要覆盖的凭证')
    return
  }
  batchLoading.value = true
  try {
    const res = await deviceApi.batchUpdateCredentials(ids, payload)
    ElMessage.success(`已更新凭证 ${res?.updated ?? 0} 台`)
    batchCredVisible.value = false
    deviceStore.invalidate()
    await loadDevices()
    clearSelection()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '批量更新凭证失败')
  } finally {
    batchLoading.value = false
  }
}

const loadDeviceGroups = async () => {
  groupLoading.value = true
  try {
    const res = await deviceGroupApi.list()
    deviceGroups.value = Array.isArray(res) ? res : []
  } catch {
    deviceGroups.value = []
  } finally {
    groupLoading.value = false
  }
}

const groupNameOf = (groupId) => {
  if (groupId == null) return '未分组'
  const g = deviceGroups.value.find(x => x.id === groupId)
  return g?.name || `分组#${groupId}`
}

const probeLabel = (method) => {
  const m = String(method || '').toLowerCase()
  if (m === 'snmp') return 'SNMP'
  if (m === 'icmp' || m === 'ping') return 'Ping'
  return method || ''
}

const monitorModeShort = (device) => {
  const mode = (device?.monitorMode || 'auto').toLowerCase()
  if (mode === 'icmp') return '仅Ping'
  if (mode === 'snmp') return '仅SNMP'
  return '自动'
}

const deviceTypeIcon = (type) => {
  const t = String(type || 'other').toLowerCase()
  if (t === 'router') return Connection
  if (t === 'switch') return SetUp
  if (t === 'firewall') return WarningFilled
  if (t === 'server' || t === 'pc') return Monitor
  if (t === 'ac' || t === 'ap') return Cpu
  return Box
}

const capabilityTags = (device) => {
  const caps = resolveCapabilities(device || {})
  const tags = [{ label: monitorModeShort(device), type: 'info' }]
  if (caps.performance) tags.push({ label: '性能', type: 'success' })
  if (caps.webssh) tags.push({ label: 'SSH', type: 'warning' })
  if (!caps.snmp && caps.icmp) tags.push({ label: 'ICMP', type: '' })
  return tags.slice(0, 4)
}

const statusTooltip = (row) => {
  const parts = [
    `状态：${row.status === 'online' ? '在线' : '离线'}`,
    row.lastProbeMethod ? `最近探测：${probeLabel(row.lastProbeMethod)}` : null,
    `监控：${monitorModeShort(row)}`,
    capabilityTags(row).length ? `能力：${capabilityTags(row).map((t) => t.label).join('、')}` : null
  ]
  return parts.filter(Boolean).join(' · ')
}

const onOpsCommand = (cmd, row) => {
  if (!row) return
  if (cmd === 'detail') {
    openDetail(row)
    return
  }
  if (cmd === 'diagnose') {
    askOpsAssistant({
      deviceId: row.id,
      title: row.name || `设备 #${row.id}`,
      deviceName: row.name || row.ipAddress || '',
      source: 'device',
      primaryToolLabel: '设备摘要',
      recommendedTools: [
        { name: 'get_device_summary', label: '设备摘要', needConfirm: false, args: { deviceId: row.id } },
        { name: 'list_active_alarms_for_device', label: '活动告警', needConfirm: false, args: { deviceId: row.id } },
        { name: 'get_topo_neighbors', label: '拓扑邻居', needConfirm: false, args: { deviceId: row.id } }
      ],
      autoAsk: true,
      autoAskQuestion: `诊断设备状态=${row.status || '未知'}`
    })
    return
  }
  if (cmd === 'terminal') {
    openWebTerminal({
      deviceId: row.id,
      deviceName: row.name || row.ipAddress || `设备#${row.id}`
    })
    return
  }
  if (cmd === 'topology') {
    router.push({ name: 'Topology', query: { deviceId: String(row.id), tab: 'monitor' } })
    return
  }
  if (cmd === 'alarms') {
    router.push({ path: '/alarms', query: { deviceId: String(row.id) } })
    return
  }
  if (cmd === 'performance') {
    router.push({ name: 'Performance', query: { deviceId: String(row.id) } })
    return
  }
  if (cmd === 'configs') {
    router.push({ path: '/configs', query: { deviceId: String(row.id) } })
    return
  }
  if (cmd === 'delete') {
    deleteDevice(row)
  }
}

const deviceGroupOptionLabel = (device) => {
  const base = `${device.name} (${device.ipAddress})`
  if (!device.groupId || (editingGroup.value && device.groupId === editingGroup.value.id)) {
    return base
  }
  return `${base} · 已在：${groupNameOf(device.groupId)}`
}

const showGroupManageDialog = async () => {
  groupForm.value = { name: '', description: '' }
  groupDialogVisible.value = true
  await loadDeviceGroups()
}

const createDeviceGroup = async () => {
  if (!groupForm.value.name?.trim()) {
    ElMessage.warning('请输入分组名称')
    return
  }
  groupSaving.value = true
  try {
    await deviceGroupApi.create({
      name: groupForm.value.name.trim(),
      description: groupForm.value.description || ''
    })
    ElMessage.success('分组已创建')
    groupForm.value = { name: '', description: '' }
    await loadDeviceGroups()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '创建失败')
  } finally {
    groupSaving.value = false
  }
}

const openGroupMembers = async (group) => {
  editingGroup.value = group
  groupMembersVisible.value = true
  try {
    // 分页拉取可选设备，避免全量 getDevices 触发后端告警阈值
    const res = await deviceApi.queryDevices({ page: 0, size: 200, sort: 'id,asc' })
    const content = Array.isArray(res?.content) ? res.content : []
    const list = await deviceGroupApi.devices(group.id)
    const members = Array.isArray(list) ? list : []
    const byId = new Map()
    content.forEach((d) => byId.set(d.id, d))
    members.forEach((d) => byId.set(d.id, d))
    allDevicesForGroup.value = [...byId.values()]
    groupMemberIds.value = members.map((d) => d.id)
  } catch {
    allDevicesForGroup.value = []
    groupMemberIds.value = []
  }
}

const saveGroupMembers = async () => {
  if (!editingGroup.value) return
  groupSaving.value = true
  try {
    const preview = await deviceGroupApi.previewMembers(editingGroup.value.id, groupMemberIds.value)
    const moving = preview?.movingFromOtherGroups || []
    const removing = preview?.removing || []
    if (moving.length || removing.length) {
      const lines = []
      if (moving.length) {
        lines.push(`以下 ${moving.length} 台将从其他分组迁入：`)
        lines.push(...moving.slice(0, 8).map(m => `· ${m.deviceName}（原：${m.fromGroupName}）`))
      }
      if (removing.length) {
        lines.push(`将移出 ${removing.length} 台。若曾按该组建过备份计划，请到配置管理「定时备份」执行同步以清理。`)
      }
      await ElMessageBox.confirm(lines.join('\n'), '确认变更分组成员', {
        type: 'warning',
        confirmButtonText: '确认变更',
        cancelButtonText: '取消'
      })
    }
    await deviceGroupApi.setMembers(editingGroup.value.id, groupMemberIds.value)
    ElMessage.success('成员已更新')
    groupMembersVisible.value = false
    await loadDeviceGroups()
    loadDevices()
    deviceStore.invalidate()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error.response?.data?.message || '保存失败')
  } finally {
    groupSaving.value = false
  }
}

const deleteDeviceGroup = (group) => {
  ElMessageBox.confirm(`确定删除分组「${group.name}」吗？设备不会被删除。`, '提示', { type: 'warning' })
    .then(async () => {
      try {
        await deviceGroupApi.remove(group.id)
        ElMessage.success('已删除')
        await loadDeviceGroups()
        loadDevices()
      } catch {
        ElMessage.error('删除失败')
      }
    }).catch(() => {})
}

const onSearch = () => {
  pagination.value.page = 1
  loadDevices()
}

const onPageSizeChange = () => {
  pagination.value.page = 1
  loadDevices()
}

const refreshDevice = async (device) => {
  try {
    const d = await deviceApi.refreshDevice(device.id)
    const st = d?.status || device.status
    const probe = d?.probe || {}
    const method = probeLabel(d?.lastProbeMethod || probe.probeMethod)
    const ok = Number(probe.consecutiveSuccess || 0)
    const needOk = Number(probe.onlineAfterSuccesses || 0)
    const fail = Number(probe.consecutiveFail || 0)
    const needFail = Number(probe.offlineAfterFailures || 0)
    let extra = ''
    if (probe.suspectedRecovery) {
      extra = `（疑似恢复 ${ok}/${needOk || 3}，连续确认后关闭离线告警）`
    } else if (st === 'online' && probe.recoveryConfirmed) {
      extra = '（已达连续成功阈值，离线告警应已自动关闭）'
    } else if (st !== 'online' && needFail > 0) {
      extra = `（连续失败 ${fail}/${needFail}）`
    } else if (st === 'online') {
      extra = '（连续确认后才会关闭离线告警）'
    } else {
      extra = '（连续失败确认后会产生/更新离线告警）'
    }
    ElMessage.success(`「${device.name}」探测${st === 'online' ? '在线' : '离线'}${method ? ' · ' + method : ''}${extra}`)
    deviceStore.invalidate()
    await Promise.all([loadDevices(), loadStats()])
    if (detailVisible.value && detailDevice.value?.id === device.id) {
      detailDevice.value = { ...detailDevice.value, ...d, probe: undefined }
    }
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || '刷新失败')
  }
}

const testConnectivityFromForm = async () => {
  if (!deviceForm.value.id) {
    ElMessage.warning('请先保存设备')
    return
  }
  testingConn.value = true
  try {
    const res = await deviceApi.testConnectivity(deviceForm.value.id)
    const icmp = res?.icmp?.ok === true ? 'ICMP✓' : (res?.icmp?.skipped ? 'ICMP-' : 'ICMP✗')
    const snmp = res?.snmp?.ok === true ? 'SNMP✓' : (res?.snmp?.skipped ? 'SNMP-' : 'SNMP✗')
    const ssh = res?.ssh?.ok === true ? 'SSH✓' : (res?.ssh?.skipped ? 'SSH-' : 'SSH✗')
    ElMessage.success(`${res?.summary || '测试完成'}：${icmp} ${snmp} ${ssh}`)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '连通性诊断失败')
  } finally {
    testingConn.value = false
  }
}

/** 编辑时只拷贝可编辑字段，绝不绑定表格行引用，避免同名设备互相串改 */
const editDevice = (device) => {
  if (!device || device.id == null) {
    ElMessage.error('设备数据无效')
    return
  }
  editMode.value = true
  formNonce.value += 1
  deviceForm.value = {
    id: device.id,
    name: device.name || '',
    ipAddress: device.ipAddress || '',
    model: device.model || '',
    vendor: device.vendor || '',
    deviceType: device.deviceType || 'other',
    monitorMode: device.monitorMode || (device.deviceType === 'pc' ? 'icmp' : 'auto'),
    snmpVersion: (device.snmpVersion === 'v3' ? 'v2c' : (device.snmpVersion || 'v2c')),
    snmpCommunity: device.snmpCommunity || 'public',
    snmpPort: device.snmpPort || 161,
    sshUsername: device.sshUsername || '',
    sshPassword: '',
    sshPort: device.sshPort || 22,
    groupId: device.groupId ?? null,
    location: device.location || '',
    contact: device.contact || '',
    serialNumber: device.serialNumber || '',
    description: device.description || ''
  }
  showAddDialog.value = true
}

const onDeviceTypeChange = (type) => {
  if (type === 'pc') {
    deviceForm.value.monitorMode = 'icmp'
  } else if (deviceForm.value.monitorMode === 'icmp') {
    deviceForm.value.monitorMode = 'auto'
  }
}

const resetForm = () => {
  editMode.value = false
  deviceForm.value = emptyForm()
}

const openAddDialog = () => {
  editMode.value = false
  formNonce.value += 1
  deviceForm.value = emptyForm()
  showAddDialog.value = true
}

const deleteDevice = async (device) => {
  try {
    await ElMessageBox.confirm(
      `确定删除设备 ${device.name}（${device.ipAddress}）？将同时清理拓扑节点、配置备份、性能数据等相关记录。`,
      '提示',
      { type: 'warning' }
    )
    await deviceApi.deleteDevice(device.id)
    ElMessage.success('删除成功')
    deviceStore.invalidate()
    await Promise.all([loadDevices(), loadStats(), loadDeviceGroups()])
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(error?.response?.data?.message || '删除失败')
    }
  }
}

const saveDevice = async () => {
  if (!deviceForm.value.name?.trim()) {
    ElMessage.warning('请填写设备名称')
    return
  }
  if (!deviceForm.value.ipAddress?.trim()) {
    ElMessage.warning('请填写 IP 地址')
    return
  }

  saving.value = true
  try {
    if (editMode.value) {
      if (deviceForm.value.id == null) {
        ElMessage.error('缺少设备 ID，无法保存')
        return
      }
      const payload = {
        name: deviceForm.value.name.trim(),
        ipAddress: deviceForm.value.ipAddress.trim(),
        model: deviceForm.value.model,
        vendor: deviceForm.value.vendor,
        deviceType: deviceForm.value.deviceType,
        monitorMode: deviceForm.value.monitorMode,
        snmpVersion: deviceForm.value.snmpVersion,
        snmpCommunity: deviceForm.value.snmpCommunity,
        snmpPort: deviceForm.value.snmpPort,
        sshUsername: deviceForm.value.sshUsername,
        sshPort: deviceForm.value.sshPort,
        groupId: deviceForm.value.groupId ?? null,
        location: deviceForm.value.location || '',
        contact: deviceForm.value.contact || '',
        serialNumber: deviceForm.value.serialNumber || '',
        description: deviceForm.value.description
      }
      if (deviceForm.value.sshPassword && String(deviceForm.value.sshPassword).trim()) {
        payload.sshPassword = String(deviceForm.value.sshPassword).trim()
      }
      await deviceApi.updateDevice(deviceForm.value.id, payload)
    } else {
      await deviceApi.createDevice({
        ...deviceForm.value,
        name: deviceForm.value.name.trim(),
        ipAddress: deviceForm.value.ipAddress.trim(),
        id: undefined
      })
    }
    ElMessage.success('保存成功')
    resetForm()
    showAddDialog.value = false
    deviceStore.invalidate()
    await Promise.all([loadDevices(), loadStats(), loadDeviceGroups()])
  } catch (error) {
    const msg = error?.response?.data?.message || error?.message || '保存失败'
    ElMessage.error(typeof msg === 'string' ? msg : '保存失败')
  } finally {
    saving.value = false
  }
}

const openDiscoverDialog = () => {
  stopDiscoverPoll()
  discovering.value = false
  endpointDiscovering.value = false
  discoverJob.value = null
  discoverCandidates.value = []
  selectedCandidates.value = []
  discoverDone.value = false
  showDiscoverDialog.value = true
}

const discoverEndpoints = async () => {
  stopDiscoverPoll()
  discovering.value = false
  endpointDiscovering.value = true
  discoverCandidates.value = []
  selectedCandidates.value = []
  discoverDone.value = false
  try {
    const res = await deviceApi.discoverEndpoints()
    const list = Array.isArray(res?.candidates) ? res.candidates : []
    discoverCandidates.value = list
    discoverDone.value = true
    discoverJob.value = {
      status: 'COMPLETED',
      message: `ARP 终端发现完成：共 ${list.length} 条（新 ${res?.newCount ?? 0}）`,
      total: list.length,
      scanned: list.length,
      found: list.length
    }
    await selectNewCandidates()
    ElMessage.success(discoverJob.value.message)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || 'ARP 终端发现失败')
  } finally {
    endpointDiscovering.value = false
  }
}

const onDiscoverClosed = () => {
  stopDiscoverPoll()
  discovering.value = false
}

const stopDiscoverPoll = () => {
  if (discoverPollTimer) {
    clearInterval(discoverPollTimer)
    discoverPollTimer = null
  }
}

const onDiscoverSelectionChange = (rows) => {
  selectedCandidates.value = rows.filter(r => !r.alreadyExists)
}

const selectNewCandidates = async () => {
  await nextTick()
  const table = discoverTableRef.value
  if (!table) return
  discoverCandidates.value.forEach(row => {
    if (!row.alreadyExists) {
      table.toggleRowSelection(row, true)
    }
  })
}

const pollDiscoverJob = async (jobId) => {
  try {
    const job = await deviceApi.getDiscoverJob(jobId)
    discoverJob.value = job
    discoverCandidates.value = Array.isArray(job?.candidates) ? job.candidates : []
    if (job?.status === 'COMPLETED' || job?.status === 'FAILED') {
      stopDiscoverPoll()
      discovering.value = false
      discoverDone.value = job.status === 'COMPLETED'
      if (job.status === 'COMPLETED') {
        await selectNewCandidates()
        ElMessage.success(job.message || '扫描完成')
      } else {
        ElMessage.error(job.message || '扫描失败')
      }
    }
  } catch (e) {
    stopDiscoverPoll()
    discovering.value = false
    ElMessage.error('获取发现进度失败')
  }
}

const startDiscovery = async () => {
  if (!discoverForm.value.network?.trim()) {
    ElMessage.warning('请填写网段')
    return
  }
  stopDiscoverPoll()
  endpointDiscovering.value = false
  discovering.value = true
  discoverDone.value = false
  discoverCandidates.value = []
  selectedCandidates.value = []
  discoverJob.value = null
  try {
    const job = await deviceApi.startDiscoverScan({
      network: discoverForm.value.network.trim(),
      timeout: discoverForm.value.timeout,
      community: discoverForm.value.community || 'public',
      snmpPort: discoverForm.value.snmpPort || 161
    })
    discoverJob.value = job
    discoverPollTimer = setInterval(() => pollDiscoverJob(job.jobId), 800)
    await pollDiscoverJob(job.jobId)
  } catch (error) {
    discovering.value = false
    ElMessage.error(error?.response?.data?.message || '启动发现失败')
  }
}

const importSelected = async () => {
  if (!selectedCandidates.value.length) {
    ElMessage.warning('请勾选要入库的新设备')
    return
  }
  importing.value = true
  try {
    const res = await deviceApi.importDiscovered(selectedCandidates.value)
    const count = res?.imported ?? (Array.isArray(res?.devices) ? res.devices.length : 0)
    ElMessage.success(`已入库 ${count} 台设备`)
    showDiscoverDialog.value = false
    deviceStore.invalidate()
    await loadDevices()
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || '入库失败')
  } finally {
    importing.value = false
  }
}

const applyRouteQuery = () => {
  const kw = route.query.keyword || route.query.q
  if (kw) {
    filters.value.keyword = String(kw)
  }
  if (route.query.discover === '1' || route.query.discover === 'true') {
    openDiscoverDialog()
  }
}

const openDeviceFromRoute = () => {
  const qid = route.query.deviceId
  if (qid == null || qid === '') return
  const id = String(qid)
  const row = devices.value.find((d) => String(d.id) === id)
  if (row) openDetail(row)
}

onMounted(async () => {
  applyRouteQuery()
  loadDeviceGroups()
  await loadDevices()
  loadStats()
  openDeviceFromRoute()
  listPoller.start()
  hintTimer = setInterval(() => { hintTick.value += 1 }, 1000)
})

watch(
  () => [route.query.keyword, route.query.q, route.query.discover, route.query.deviceId],
  async () => {
    applyRouteQuery()
    if (route.query.keyword || route.query.q) {
      pagination.value.page = 1
      await loadDevices()
    }
    openDeviceFromRoute()
  }
)

onUnmounted(() => {
  stopDiscoverPoll()
  listPoller.stop()
  if (hintTimer) {
    clearInterval(hintTimer)
    hintTimer = null
  }
})
</script>

<style scoped>
.toolbar-spacer {
  flex: 1;
  min-width: 8px;
}

.auto-refresh-hint {
  font-size: 12px;
  color: var(--nms-text-secondary, #5c6b7f);
  font-variant-numeric: tabular-nums;
  margin-right: 4px;
  white-space: nowrap;
}

.dev-type-line {
  font-size: 11px;
  color: var(--nms-text-muted);
  margin-top: 2px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.last-seen {
  font-size: 12px;
  color: var(--nms-text-secondary);
  font-variant-numeric: tabular-nums;
}

.ssh-hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--nms-text-secondary);
}

.discover-progress {
  margin-top: 8px;
}

.discover-meta {
  margin-top: 8px;
  color: var(--nms-text-secondary);
  font-size: 13px;
}

:deep(.danger-item) {
  color: var(--el-color-danger);
}
</style>

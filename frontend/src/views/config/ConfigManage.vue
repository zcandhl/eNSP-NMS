<template>
  <div class="nms-page config-manage-container">
    <div class="nms-page-header">
      <div class="nms-page-title-block">
        <h1 class="nms-page-title">配置管理</h1>
        <p class="nms-page-subtitle">备份健康 · 合规基线 · 分波下发 · 任务可追溯 · 变更可审计</p>
      </div>
      <div class="nms-page-actions">
        <el-button
          v-if="auth.hasAnyPermission('aiops:read', 'alarms:read', 'configs:read')"
          type="primary"
          plain
          size="small"
          @click="openOpsAssist"
        >运维辅助</el-button>
        <el-button size="small" @click="goConfigAudit">配置变更记录</el-button>
      </div>
    </div>

    <div class="nms-panel">
      <div class="nms-panel-body config-page-body with-ops">
      <div class="config-main">
      <el-tabs v-model="activeTab" class="config-tabs">
        <el-tab-pane label="配置备份" name="backup">
          <div class="tab-content backup-layout">
            <div class="health-bar" v-loading="healthLoading">
              <div class="health-card" :class="{ active: healthFilter === '' }" @click="setHealthFilter('')">
                <div class="health-num">{{ healthOverview.deviceTotal || 0 }}</div>
                <div class="health-label">设备总数</div>
              </div>
              <div class="health-card is-danger" :class="{ active: healthFilter === 'never' }" @click="setHealthFilter('never')">
                <div class="health-num">{{ healthOverview.neverBackedUp || 0 }}</div>
                <div class="health-label">从未备份</div>
              </div>
              <div class="health-card is-warning" :class="{ active: healthFilter === 'stale' }" @click="setHealthFilter('stale')">
                <div class="health-num">{{ healthOverview.staleOverDays || 0 }}</div>
                <div class="health-label">超期≥{{ healthOverview.staleDays || 7 }}天</div>
              </div>
              <div class="health-card is-danger" :class="{ active: healthFilter === 'failed' }" @click="setHealthFilter('failed')">
                <div class="health-num">{{ healthOverview.scheduleFailed || 0 }}</div>
                <div class="health-label">计划失败</div>
              </div>
              <div class="health-card is-ok" :class="{ active: healthFilter === 'ok' }" @click="setHealthFilter('ok')">
                <div class="health-num">{{ healthOkCount }}</div>
                <div class="health-label">备份正常</div>
              </div>
            </div>

            <div class="backup-split">
              <div class="device-panel">
                <el-input
                  v-model="deviceListKeyword"
                  placeholder="搜索设备 / IP"
                  clearable
                  size="small"
                  class="device-search"
                >
                  <template #prefix><el-icon><Search /></el-icon></template>
                </el-input>
                <div class="device-list" v-loading="healthLoading">
                  <div
                    v-for="row in filteredHealthDevices"
                    :key="row.deviceId"
                    class="device-item"
                    :class="{ active: selectedDevice === row.deviceId }"
                    @click="selectDeviceFromHealth(row)"
                  >
                    <div class="device-item-main">
                      <span class="device-name">{{ row.deviceName }}</span>
                      <el-tag size="small" :type="healthTagType(row.health)" effect="plain">{{ row.healthLabel }}</el-tag>
                    </div>
                    <div class="device-item-sub">{{ row.ipAddress }}</div>
                    <div class="device-item-meta">
                      <span>{{ row.lastBackupAt ? formatDate(row.lastBackupAt) : '无备份' }}</span>
                      <span>{{ row.backupCount || 0 }} 份</span>
                    </div>
                  </div>
                  <el-empty v-if="!healthLoading && filteredHealthDevices.length === 0" description="无匹配设备" :image-size="48" />
                </div>
              </div>

              <div class="version-panel">
                <div class="toolbar toolbar-spread">
                  <div class="selected-device-title" v-if="selectedHealthRow">
                    <strong>{{ selectedHealthRow.deviceName }}</strong>
                    <span class="muted">{{ selectedHealthRow.ipAddress }}</span>
                  </div>
                  <span v-else class="muted">从左侧选择设备</span>
                  <div class="toolbar-actions">
                    <el-button
                      v-permission="'configs:write'"
                      type="primary"
                      size="small"
                      :disabled="!selectedDevice"
                      :loading="backupLoading"
                      @click="showBackupDialog"
                    >
                      <el-icon><Download /></el-icon>
                      备份
                    </el-button>
                    <el-button
                      v-permission="'configs:write'"
                      type="primary"
                      plain
                      size="small"
                      :loading="batchBackupLoading"
                      @click="showBatchBackupDialog"
                    >
                      批量备份
                    </el-button>
                    <el-button
                      v-permission="'configs:write'"
                      type="success"
                      size="small"
                      :disabled="!selectedDevice"
                      @click="showScheduleDialog"
                    >
                      <el-icon><Timer /></el-icon>
                      定时
                    </el-button>
                    <el-button
                      v-if="auth.hasAnyPermission('webssh:connect', 'configs:write')"
                      size="small"
                      :disabled="!selectedDevice"
                      :loading="testingConnection"
                      @click="testConnection"
                    >
                      <el-icon><Connection /></el-icon>
                      测试
                    </el-button>
                    <el-button
                      v-permission="'configs:read'"
                      size="small"
                      type="warning"
                      :disabled="!selectedDevice || selectedConfigs.length === 0"
                      @click="showCompareDialog"
                    >
                      <el-icon><Files /></el-icon>
                      对比
                    </el-button>
                    <el-button
                      v-if="auth.hasAnyPermission('aiops:read', 'alarms:read')"
                      size="small"
                      type="primary"
                      plain
                      :disabled="!selectedDevice"
                      @click="askConfigRisk"
                    >
                      对比配置风险
                    </el-button>
                    <el-button
                      v-if="auth.hasAnyPermission('aiops:read', 'alarms:read')"
                      size="small"
                      plain
                      :disabled="!selectedDevice"
                      @click="askConfigSuggest"
                    >
                      生成配置建议
                    </el-button>
                  </div>
                </div>

                <div v-if="selectedDevice && selectedConfigs.length" class="config-batch-bar">
                  <span>已选 {{ selectedConfigs.length }} 个备份</span>
                  <el-button
                    v-permission="'configs:read'"
                    size="small"
                    type="primary"
                    :loading="batchExportLoading"
                    @click="batchExportConfigs"
                  >
                    <el-icon><Download /></el-icon>
                    批量导出
                  </el-button>
                  <el-button
                    v-permission="'configs:write'"
                    size="small"
                    type="danger"
                    :loading="batchDeleteLoading"
                    @click="batchDeleteConfigs"
                  >
                    批量删除
                  </el-button>
                  <el-button size="small" link @click="clearConfigSelection">取消选择</el-button>
                </div>

                <el-table
                  ref="configTableRef"
                  v-loading="loading"
                  :data="configs"
                  style="width: 100%; margin-top: 12px;"
                  @selection-change="handleSelectionChange"
                >
                  <el-table-column type="selection" width="48" />
                  <el-table-column prop="configVersion" label="版本号" min-width="140" />
                  <el-table-column prop="configType" label="类型" width="100">
                    <template #default="{ row }">
                      <el-tag :type="row.configType === 'running' ? 'primary' : 'info'" size="small">
                        {{ row.configType === 'running' ? '运行' : '启动' }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="createdAt" label="备份时间" width="170">
                    <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
                  </el-table-column>
                  <el-table-column prop="createdBy" label="操作人" width="100" />
                  <el-table-column prop="description" label="描述" show-overflow-tooltip />
                  <el-table-column label="操作" width="240" fixed="right">
                    <template #default="{ row }">
                      <el-button size="small" link @click="viewConfig(row)">查看</el-button>
                      <el-button size="small" link type="primary" @click="exportConfig(row)">导出</el-button>
                      <el-button v-permission="'configs:write'" size="small" link type="success" @click="restoreConfig(row)">恢复</el-button>
                      <el-button v-permission="'configs:write'" size="small" link type="danger" @click="handleDelete(row)">删除</el-button>
                    </template>
                  </el-table-column>
                </el-table>

                <div v-if="selectedDevice" class="config-pager">
                  <el-pagination
                    v-model:current-page="configPage"
                    v-model:page-size="configPageSize"
                    :total="configTotal"
                    :page-sizes="[10, 20, 50]"
                    layout="total, sizes, prev, pager, next"
                    background
                    @current-change="loadConfigs"
                    @size-change="onConfigPageSizeChange"
                  />
                </div>

                <el-empty v-if="!loading && !selectedDevice" description="请从左侧选择设备查看备份版本" :image-size="64" />
                <el-empty v-else-if="!loading && configs.length === 0" description="该设备暂无配置备份" :image-size="64" />

                <div v-if="selectedDevice" class="device-insight">
                  <div class="compliance-card" v-loading="complianceLoading">
                    <div class="compliance-head">
                      <strong>合规基线</strong>
                      <el-button size="small" link type="primary" @click="loadCompliance">刷新</el-button>
                    </div>
                    <template v-if="compliance">
                      <div class="compliance-score" :class="'lv-' + (compliance.level || 'unknown')">
                        {{ compliance.score ?? '-' }}
                        <span class="compliance-level">{{ complianceLevelLabel(compliance.level) }}</span>
                      </div>
                      <div class="compliance-meta">{{ compliance.message }} · 来源 {{ compliance.contentSource || '-' }}</div>
                      <div class="compliance-rules">
                        <el-tag
                          v-for="r in (compliance.rules || [])"
                          :key="r.code"
                          size="small"
                          :type="r.passed ? 'success' : 'danger'"
                          class="rule-tag"
                        >
                          {{ r.passed ? '✓' : '✗' }} {{ r.name }}
                        </el-tag>
                      </div>
                    </template>
                    <div v-else class="muted">选择设备后自动评估合规规则</div>
                  </div>
                  <div class="change-timeline">
                    <div class="compliance-head">
                      <strong>近期变更</strong>
                      <el-button
                        size="small"
                        link
                        type="primary"
                        @click="goDeviceAudit(selectedDevice)"
                      >
                        在日志中查看
                      </el-button>
                    </div>
                    <div v-loading="recentChangesLoading" class="change-list">
                      <div v-for="c in recentChanges" :key="c.id" class="change-item">
                        <div class="change-main">
                          <el-tag size="small" :type="getStatusTag(c.status)">{{ formatChangeType(c.changeType) }}</el-tag>
                          <span class="change-reason">{{ c.reason || c.result || '-' }}</span>
                        </div>
                        <div class="change-meta">
                          <span>{{ c.operator || '-' }}</span>
                          <span>{{ formatDate(c.createdAt) }}</span>
                          <span v-if="c.beforeVersion || c.afterVersion">
                            {{ c.beforeVersion || '?' }} → {{ c.afterVersion || '?' }}
                          </span>
                        </div>
                      </div>
                      <el-empty v-if="!recentChangesLoading && !recentChanges.length" description="暂无变更" :image-size="40" />
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane v-if="auth.hasPermission('configs:write')" label="定时备份" name="schedule">
          <div class="tab-content">
            <el-alert
              type="info"
              :closable="false"
              show-icon
              class="schedule-alert"
              title="按「设备分组」创建策略时，会按当时成员展开为多条设备计划；成员变更后请点「同步分组计划」。分组成员请在设备管理中维护。"
            />
            <div class="toolbar">
              <div class="toolbar-actions">
                <el-button type="primary" @click="showScheduleCreateDialog">
                  <el-icon><Plus /></el-icon>
                  新建策略
                </el-button>
                <el-button @click="showSyncGroupDialog">同步分组计划</el-button>
                <el-button @click="goDeviceGroups">打开设备管理</el-button>
              </div>
            </div>
            <el-table v-loading="scheduleLoading" :data="schedules" style="width: 100%; margin-top: 14px;">
              <el-table-column prop="deviceName" label="设备" min-width="160" show-overflow-tooltip />
              <el-table-column prop="scheduleType" label="周期" width="120">
                <template #default="{ row }">
                  <el-tag size="small">{{ formatScheduleCycle(row) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="scheduleTime" label="时间" width="90" />
              <el-table-column prop="configType" label="配置类型" width="110">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.configType === 'running' ? 'primary' : 'info'">
                    {{ row.configType === 'running' ? '运行配置' : '启动配置' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="isActive" label="状态" width="90">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.isActive ? 'success' : 'info'">
                    {{ row.isActive ? '启用' : '停用' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="lastRun" label="上次执行" width="170">
                <template #default="{ row }">
                  {{ row.lastRun ? formatDate(row.lastRun) : '-' }}
                </template>
              </el-table-column>
              <el-table-column prop="lastStatus" label="上次状态" width="100">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.lastStatus === 'success' ? 'success' : 'danger'" v-if="row.lastStatus">
                    {{ row.lastStatus === 'success' ? '成功' : '失败' }}
                  </el-tag>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="200" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" link type="success" @click="executeSchedule(row)">执行</el-button>
                  <el-button size="small" link @click="openEditSchedule(row)">编辑</el-button>
                  <el-button size="small" link type="danger" @click="deleteSchedule(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!scheduleLoading && !schedules.length" description="暂无定时备份策略" />
          </div>
        </el-tab-pane>

        <el-tab-pane v-if="auth.hasPermission('configs:write')" label="批量下发" name="batch">
          <div class="tab-content">
            <el-steps :active="batchStep" finish-status="success" simple style="margin-bottom: 24px;">
              <el-step title="选择设备" />
              <el-step title="配置内容" />
              <el-step title="预览确认" />
            </el-steps>

            <div v-if="batchStep === 0" class="batch-step">
              <div class="batch-device-filter">
                <el-input v-model="deviceSearch" placeholder="搜索设备名称或IP" style="width: 220px;" clearable>
                  <template #prefix>
                    <el-icon><Search /></el-icon>
                  </template>
                </el-input>
                <el-select v-model="batchGroupFilter" clearable placeholder="设备分组" style="width: 160px;">
                  <el-option
                    v-for="g in deviceGroups"
                    :key="g.id"
                    :label="`${g.name}（${g.deviceCount || 0}）`"
                    :value="g.id"
                  />
                </el-select>
                <el-checkbox v-model="batchOnlineOnly">仅显示在线</el-checkbox>
                <el-button size="small" @click="selectOnlineDevices">勾选当前列表在线</el-button>
              </div>
              <el-table 
                ref="batchDeviceTable"
                :data="filteredDevices" 
                style="width: 100%"
                @selection-change="handleBatchSelectionChange"
              >
                <el-table-column type="selection" width="55" />
                <el-table-column prop="name" label="设备名称" width="180" />
                <el-table-column prop="ipAddress" label="IP地址" width="150" />
                <el-table-column prop="model" label="设备型号" width="150" />
                <el-table-column prop="status" label="状态">
                  <template #default="{ row }">
                    <el-tag :type="row.status === 'online' ? 'success' : 'info'" size="small">
                      {{ row.status === 'online' ? '在线' : '离线' }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>
              <div class="batch-step-footer">
                <span class="selected-count">
                  已选择 {{ batchSelectedDevices.length }} 台
                  <template v-if="batchOfflineSelectedCount">（含离线 {{ batchOfflineSelectedCount }}）</template>
                </span>
                <el-button type="primary" :disabled="batchSelectedDevices.length === 0" @click="batchStep = 1">
                  下一步 <el-icon><ArrowRight /></el-icon>
                </el-button>
              </div>
            </div>

            <div v-if="batchStep === 1" class="batch-step">
              <div class="config-type-grid compact">
                <div
                  v-for="type in configTypes"
                  :key="type.value"
                  class="config-type-card"
                  :class="{ active: batchForm.configType === type.value, recommended: type.recommended }"
                  @click="selectConfigType(type)"
                >
                  <el-icon :size="22"><component :is="type.icon" /></el-icon>
                  <span class="config-type-name">{{ type.label }}</span>
                  <el-tag v-if="type.recommended" size="small" type="success" effect="plain">推荐</el-tag>
                  <el-tag v-else-if="type.sameParams" size="small" type="warning" effect="plain">同参</el-tag>
                  <span class="config-type-desc">{{ type.description }}</span>
                </div>
              </div>

              <el-alert
                v-if="batchSameParamRisk"
                type="warning"
                :closable="false"
                show-icon
                style="margin-bottom: 12px;"
                title="多台设备将使用相同参数"
                :description="batchSameParamHint"
              />

              <div class="batch-config-layout">
                <el-form :model="batchForm" label-width="110px" class="batch-config-form">
                  <template v-if="batchForm.configType === 'custom'">
                    <el-form-item label="从模板填入">
                      <el-select
                        v-model="batchTemplateId"
                        clearable
                        filterable
                        placeholder="选择已有模板（可选）"
                        style="width: 100%;"
                        @change="applyBatchTemplate"
                      >
                        <el-option
                          v-for="t in templates"
                          :key="t.id"
                          :label="t.name"
                          :value="t.id"
                        />
                      </el-select>
                    </el-form-item>
                    <el-form-item label="配置命令" required>
                      <el-input
                        v-model="batchForm.params.customContent"
                        type="textarea"
                        :rows="10"
                        placeholder="每行一条命令。可使用 ${name} ${ipAddress} ${model} ${index}；若已含 system-view/return，将按原文下发。"
                      />
                    </el-form-item>
                    <el-form-item label="变量替换">
                      <el-switch v-model="batchEnableVariables" active-text="启用 ${name}/${ipAddress} 等" />
                    </el-form-item>
                  </template>

                  <template v-else-if="batchForm.configType === 'interface'">
                    <el-form-item label="接口类型" required>
                      <el-select v-model="batchForm.params.interfaceType" placeholder="选择接口类型" style="width: 100%;">
                        <el-option value="GigabitEthernet" label="GigabitEthernet" />
                        <el-option value="XGigabitEthernet" label="XGigabitEthernet" />
                        <el-option value="Ethernet" label="Ethernet" />
                        <el-option value="Serial" label="Serial" />
                        <el-option value="LoopBack" label="LoopBack" />
                        <el-option value="Vlanif" label="Vlanif" />
                      </el-select>
                    </el-form-item>
                    <el-form-item label="接口编号" required>
                      <el-input v-model="batchForm.params.interfaceIndex" placeholder="如: 0/0/1 或 1" />
                    </el-form-item>
                    <el-form-item label="IP地址">
                      <el-input
                        v-model="batchForm.params.ipAddress"
                        placeholder="同参填固定IP；按设备填 ${ipAddress}"
                      />
                    </el-form-item>
                    <el-form-item label="子网掩码">
                      <el-input v-model="batchForm.params.mask" placeholder="如: 255.255.255.0" />
                    </el-form-item>
                    <el-form-item label="描述">
                      <el-input v-model="batchForm.params.description" type="textarea" :rows="2" placeholder="接口描述，可用 ${name}" />
                    </el-form-item>
                    <el-form-item label="变量替换">
                      <el-switch v-model="batchEnableVariables" active-text="展开 ${…} 变量" />
                    </el-form-item>
                  </template>

                  <template v-else-if="batchForm.configType === 'vlan'">
                    <el-form-item label="VLAN ID" required>
                      <el-input
                        v-model="batchForm.params.vlanIds"
                        placeholder="支持多个：10,20,30 或 10-15 或 10,20-22"
                      />
                      <div class="form-hint">
                        将创建 {{ vlanIdList.length }} 个 VLAN
                        <template v-if="vlanIdList.length">：{{ vlanIdList.slice(0, 12).join(', ') }}{{ vlanIdList.length > 12 ? '…' : '' }}</template>
                      </div>
                    </el-form-item>
                    <el-form-item v-if="vlanIdList.length === 1" label="VLAN名称">
                      <el-input v-model="batchForm.params.vlanName" placeholder="可选（仅单 VLAN 时写入）" />
                    </el-form-item>
                    <el-form-item v-if="vlanIdList.length === 1 && batchForm.params.vlanName" label="名称命令">
                      <el-radio-group v-model="batchForm.params.vlanNameCmd">
                        <el-radio-button label="name">name</el-radio-button>
                        <el-radio-button label="description">description</el-radio-button>
                      </el-radio-group>
                    </el-form-item>
                    <el-form-item label="绑定端口">
                      <el-input
                        v-model="batchForm.params.ports"
                        type="textarea"
                        :rows="3"
                        placeholder="可选。支持 GE0/0/1；多端口换行或逗号分隔"
                      />
                    </el-form-item>
                    <el-form-item v-if="vlanPortList.length" label="端口模式">
                      <el-select v-model="batchForm.params.portMode" style="width: 100%;">
                        <el-option
                          value="access"
                          label="Access → port default vlan"
                          :disabled="vlanIdList.length > 1"
                        />
                        <el-option value="trunk" label="Trunk → allow-pass 所列 VLAN" />
                      </el-select>
                      <div v-if="vlanIdList.length > 1" class="form-hint">多 VLAN 时只能用 Trunk 放行</div>
                    </el-form-item>
                    <el-form-item v-if="vlanPortList.length && batchForm.params.portMode === 'trunk'" label="Trunk PVID">
                      <el-switch
                        v-model="batchForm.params.trunkSetPvid"
                        :active-text="`设置 pvid 为第一个 VLAN（${vlanIdList[0] || '-'}）`"
                      />
                    </el-form-item>
                    <el-form-item label="配置 Vlanif">
                      <el-switch
                        v-model="batchForm.params.enableVlanif"
                        :disabled="vlanIdList.length !== 1"
                        active-text="创建三层 Vlanif 并配 IP（仅单 VLAN）"
                      />
                    </el-form-item>
                    <template v-if="batchForm.params.enableVlanif && vlanIdList.length === 1">
                      <el-form-item label="Vlanif IP" required>
                        <el-input
                          v-model="batchForm.params.vlanifIp"
                          placeholder="固定 IP，或 ${ipAddress}"
                        />
                      </el-form-item>
                      <el-form-item label="子网掩码" required>
                        <el-input v-model="batchForm.params.vlanifMask" placeholder="255.255.255.0" />
                      </el-form-item>
                      <el-form-item label="变量替换">
                        <el-switch v-model="batchEnableVariables" active-text="展开 ${…}" />
                      </el-form-item>
                    </template>
                  </template>

                  <template v-else-if="batchForm.configType === 'static-route'">
                    <el-form-item label="目的网络" required>
                      <el-input v-model="batchForm.params.destNetwork" placeholder="192.168.10.0 或 192.168.10.0/24" />
                    </el-form-item>
                    <el-form-item label="目的掩码">
                      <el-input
                        v-model="batchForm.params.destMask"
                        placeholder="目的为 CIDR 时可留空；否则如 255.255.255.0"
                      />
                    </el-form-item>
                    <el-form-item label="下一跳" required>
                      <el-input
                        v-model="batchForm.params.nextHop"
                        placeholder="下一跳 IP，或出接口如 GigabitEthernet0/0/0"
                      />
                    </el-form-item>
                    <el-form-item label="优先级">
                      <el-input-number
                        v-model="batchForm.params.preference"
                        :min="1"
                        :max="255"
                        :value-on-clear="null"
                        controls-position="right"
                        clearable
                        placeholder="留空则不下发 preference"
                        style="width: 100%;"
                      />
                    </el-form-item>
                    <el-form-item label="变量替换">
                      <el-switch v-model="batchEnableVariables" active-text="展开 ${…}" />
                    </el-form-item>
                  </template>

                  <template v-else-if="batchForm.configType === 'ospf'">
                    <el-form-item label="进程ID" required>
                      <el-input-number v-model="batchForm.params.processId" :min="1" :max="65535" style="width: 100%;" />
                    </el-form-item>
                    <el-form-item label="指定 RID">
                      <el-switch v-model="batchForm.params.enableRouterId" active-text="写入 router-id（进程已存在时可能失败）" />
                    </el-form-item>
                    <el-form-item v-if="batchForm.params.enableRouterId" label="Router-ID">
                      <el-input
                        v-model="batchForm.params.routerId"
                        placeholder="默认 ${ipAddress}"
                      />
                    </el-form-item>
                    <el-form-item label="区域ID" required>
                      <el-input v-model="batchForm.params.areaId" placeholder="0 或 0.0.0.0" />
                    </el-form-item>
                    <el-form-item label="宣告网络" required>
                      <el-input
                        v-model="batchForm.params.ospfNetwork"
                        placeholder="仅单个网段，如 192.168.1.0"
                      />
                    </el-form-item>
                    <el-form-item label="掩码/通配符" required>
                      <el-input
                        v-model="batchForm.params.ospfMask"
                        placeholder="0.0.0.255、255.255.255.0 或 /24"
                      />
                      <div class="form-hint">OSPF 仅支持单个网段；子网掩码与 /前缀会自动转通配符</div>
                    </el-form-item>
                    <el-form-item label="变量替换">
                      <el-switch v-model="batchEnableVariables" active-text="展开 Router-ID 等 ${…}" />
                    </el-form-item>
                  </template>
                </el-form>

                <div class="batch-live-preview">
                  <div class="batch-live-preview-title">
                    命令预览
                    <span v-if="batchLivePreviewDevice" class="batch-live-preview-device">
                      （示例：{{ batchLivePreviewDevice.name }}）
                    </span>
                  </div>
                  <pre class="preview-commands">{{ batchLivePreviewText }}</pre>
                </div>
              </div>

              <div class="batch-step-footer">
                <el-button @click="batchStep = 0"><el-icon><ArrowLeft /></el-icon> 上一步</el-button>
                <el-button type="primary" @click="goBatchPreview">
                  下一步 <el-icon><ArrowRight /></el-icon>
                </el-button>
              </div>
            </div>

            <div v-if="batchStep === 2" class="batch-step">
              <el-alert
                :title="batchConfirmTitle"
                :type="batchOfflineSelectedCount ? 'warning' : 'info'"
                :description="batchConfirmDesc"
                show-icon
                style="margin-bottom: 16px;"
              />
              <el-form label-width="100px" style="max-width: 640px; margin-bottom: 16px;">
                <el-form-item label="变更原因">
                  <el-input
                    v-model="batchReason"
                    type="textarea"
                    :rows="2"
                    maxlength="200"
                    show-word-limit
                    placeholder="写入变更记录，便于审计（可选，默认：批量配置下发）"
                  />
                </el-form-item>
                <el-form-item label="分波下发">
                  <el-input-number
                    v-model="batchWaveSize"
                    :min="0"
                    :max="batchSelectedDevices.length || 100"
                    controls-position="right"
                    placeholder="0=一次全部"
                    style="width: 160px;"
                  />
                  <span class="form-hint" style="margin-left: 10px;">
                    0 一次全部；&gt;0 时先下发 N 台后暂停，确认后再继续（实验室灰度）
                  </span>
                </el-form-item>
              </el-form>
              <div v-if="batchPreviewSummary" class="batch-precheck">
                <el-alert
                  :title="batchPreviewSummary.message || '预检结果'"
                  type="info"
                  :closable="false"
                  show-icon
                />
                <el-table :data="batchPreviewSummary.devices || []" size="small" style="margin-top: 10px;" max-height="220">
                  <el-table-column prop="deviceName" label="设备" width="140" />
                  <el-table-column prop="commandCount" label="命令行" width="80" />
                  <el-table-column label="running" width="90">
                    <template #default="{ row }">
                      <el-tag size="small" :type="row.liveAvailable ? 'success' : 'info'">
                        {{ row.liveAvailable ? '已拉取' : '不可用' }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="newLineEstimate" label="估算新增" width="90" />
                  <el-table-column prop="alreadyPresentEstimate" label="已存在" width="80" />
                  <el-table-column label="样例新增行" min-width="180" show-overflow-tooltip>
                    <template #default="{ row }">
                      {{ (row.sampleNewLines || []).join('；') || '-' }}
                    </template>
                  </el-table-column>
                </el-table>
              </div>
              <div class="preview-list">
                <div
                  v-for="preview in batchPreviews"
                  :key="preview.deviceId"
                  class="preview-item"
                  :class="{ 'is-offline': preview.offline }"
                >
                  <div class="preview-header">
                    <span class="device-name">{{ preview.deviceName }}</span>
                    <span class="device-ip">{{ preview.deviceIp }}</span>
                    <el-tag v-if="preview.offline" type="info" size="small">离线</el-tag>
                  </div>
                  <pre class="preview-commands">{{ preview.commands }}</pre>
                </div>
              </div>
              <div class="batch-step-footer">
                <el-button @click="batchStep = 1"><el-icon><ArrowLeft /></el-icon> 上一步</el-button>
                <el-button :loading="batchPreviewLoading" @click="runBatchPrecheck">预检差异</el-button>
                <el-button type="primary" :loading="batchLoading" @click="executeBatchConfig()">
                  <el-icon><Upload /></el-icon> 确认下发
                </el-button>
              </div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="配置对比" name="compare">
          <div class="tab-content">
            <div class="compare-device-bar">
              <el-select
                v-model="compareDeviceId"
                placeholder="选择要对比的设备"
                filterable
                clearable
                style="width: 320px;"
                @change="onCompareDeviceChange"
              >
                <el-option
                  v-for="d in configDevices"
                  :key="'cmp-d-' + d.id"
                  :label="`${d.name} (${d.ipAddress})`"
                  :value="d.id"
                />
              </el-select>
            </div>
            <div class="compare-toolbar">
              <el-select v-model="compareMode" size="small" style="width: 180px;" @change="onCompareModeChange">
                <el-option label="备份 vs 备份" value="backup" />
                <el-option label="当前运行 vs 备份" value="live-running" />
                <el-option label="当前启动 vs 备份" value="live-startup" />
              </el-select>
              <el-select
                v-if="compareMode === 'backup'"
                v-model="compareSource"
                placeholder="源备份"
                clearable
                filterable
                style="width: 280px;"
                :disabled="!compareDeviceId"
              >
                <el-option
                  v-for="config in compareVersionOptions"
                  :key="'s-' + config.id"
                  :label="formatCompareOption(config)"
                  :value="config.id"
                />
              </el-select>
              <el-tag v-else type="info" effect="plain">{{ compareMode === 'live-startup' ? '当前启动配置' : '当前运行配置' }}</el-tag>
              <span class="vs-text">VS</span>
              <el-select
                v-model="compareTarget"
                placeholder="目标备份"
                clearable
                filterable
                style="width: 280px;"
                :disabled="!compareDeviceId"
              >
                <el-option
                  v-for="config in compareVersionOptions"
                  :key="'t-' + config.id"
                  :label="formatCompareOption(config)"
                  :value="config.id"
                />
              </el-select>
              <el-checkbox v-model="compareIgnoreNoise" @change="onCompareNoiseChange">过滤噪声行</el-checkbox>
              <el-button
                type="primary"
                :loading="compareLoading"
                :disabled="!canStartCompare"
                @click="doCompare"
              >
                <el-icon><Files /></el-icon>
                开始对比
              </el-button>
              <el-button
                v-if="compareResult"
                :disabled="!compareResult"
                @click="exportCompareDiff"
              >
                导出 Diff
              </el-button>
            </div>

            <div v-if="compareResult" class="compare-result">
              <div class="compare-header">
                <template v-if="compareResult.added === 0 && compareResult.removed === 0">
                  <el-tag type="success">配置一致（无差异）</el-tag>
                </template>
                <template v-else>
                  <span class="diff-count added">+ {{ compareResult.added }} 行新增</span>
                  <span class="diff-count removed">- {{ compareResult.removed }} 行删除</span>
                </template>
              </div>
              <div class="compare-side-by-side">
                <div class="compare-col">
                  <div class="compare-col-title">{{ compareLeftTitle }}</div>
                  <div ref="compareLeftPane" class="compare-content" @scroll="onCompareScrollLeft">
                    <div v-for="(line, index) in compareResult.lines" :key="'L'+index" :class="line.type">
                      <span class="line-num">{{ index + 1 }}</span>
                      <span class="line-content">{{ line.left || ' ' }}</span>
                    </div>
                  </div>
                </div>
                <div class="compare-col">
                  <div class="compare-col-title">{{ compareRightTitle }}</div>
                  <div ref="compareRightPane" class="compare-content" @scroll="onCompareScrollRight">
                    <div v-for="(line, index) in compareResult.lines" :key="'R'+index" :class="line.type">
                      <span class="line-num">{{ index + 1 }}</span>
                      <span class="line-content">{{ line.right || ' ' }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
            <el-empty
              v-else
              :description="!compareDeviceId
                ? '请先选择设备'
                : (canStartCompare ? '选择版本后点击「开始对比」' : '请选择对比模式与备份版本')"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane label="配置模板" name="template">
          <div class="tab-content">
            <div class="toolbar">
              <el-button v-permission="'configs:write'" type="primary" @click="showTemplateDialog(null)">
                <el-icon><Plus /></el-icon>
                新建模板
              </el-button>
            </div>

            <el-table 
              v-loading="templateLoading" 
              :data="templates" 
              style="width: 100%; margin-top: 20px;"
            >
              <el-table-column prop="name" label="模板名称" width="200" />
              <el-table-column prop="category" label="分类" width="120">
                <template #default="{ row }">
                  <el-tag size="small">{{ row.category || '未分类' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="deviceType" label="适用设备" width="150">
                <template #default="{ row }">
                  <el-tag type="info" size="small">{{ row.deviceType || '通用' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="description" label="描述" show-overflow-tooltip />
              <el-table-column prop="createdBy" label="创建人" width="120" />
              <el-table-column label="操作" width="200" fixed="right">
                <template #default="{ row }">
                  <el-button v-permission="'configs:write'" size="small" link @click="applyTemplate(row)">应用</el-button>
                  <el-button v-permission="'configs:write'" size="small" link @click="showTemplateDialog(row)">编辑</el-button>
                  <el-button v-permission="'configs:write'" size="small" link type="danger" @click="deleteTemplate(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>

            <el-empty v-if="!templateLoading && templates.length === 0" description="暂无配置模板" />
          </div>
        </el-tab-pane>

        <el-tab-pane label="任务中心" name="tasks">
          <div class="tab-content">
            <div class="toolbar">
              <el-button type="primary" :loading="taskListLoading" @click="loadConfigTasks">刷新</el-button>
              <span class="toolbar-hint">任务已持久化（约 7 天），支持取消 / 分波继续 / 失败重试；进行中任务为实时进度</span>
            </div>
            <el-table v-loading="taskListLoading" :data="configTasks" style="width: 100%; margin-top: 12px;">
              <el-table-column prop="label" label="任务" min-width="180" show-overflow-tooltip />
              <el-table-column prop="type" label="类型" width="100">
                <template #default="{ row }">
                  <el-tag size="small">{{ formatTaskType(row.type) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="operator" label="操作人" width="100" />
              <el-table-column prop="targetCount" label="目标数" width="80" />
              <el-table-column prop="status" label="状态" width="110">
                <template #default="{ row }">
                  <el-tag :type="taskStatusTag(row.status)" size="small">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="进度" width="160">
                <template #default="{ row }">
                  <el-progress
                    :percentage="Number(row.progress || 0)"
                    :status="row.status === 'FAILED' || row.status === 'CANCELLED' ? 'exception' : (row.progress >= 100 && row.status !== 'PAUSED' ? 'success' : undefined)"
                    :stroke-width="14"
                  />
                </template>
              </el-table-column>
              <el-table-column prop="message" label="消息" min-width="180" show-overflow-tooltip />
              <el-table-column prop="createdAt" label="创建时间" width="170">
                <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="220" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" link @click="viewTaskDetail(row)">详情</el-button>
                  <el-button
                    v-if="row.status === 'PAUSED'"
                    v-permission="'configs:write'"
                    size="small"
                    link
                    type="primary"
                    @click="continuePausedTask(row)"
                  >继续</el-button>
                  <el-button
                    v-if="['RUNNING', 'PENDING', 'PAUSED'].includes(row.status)"
                    v-permission="'configs:write'"
                    size="small"
                    link
                    type="warning"
                    @click="cancelTask(row)"
                  >取消</el-button>
                  <el-button
                    v-if="row.type === 'batch' && (row.status === 'PARTIAL' || row.status === 'FAILED')"
                    v-permission="'configs:write'"
                    size="small"
                    link
                    type="success"
                    @click="retryTaskFailed(row)"
                  >重试失败</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!taskListLoading && configTasks.length === 0" description="暂无异步任务" />
          </div>
        </el-tab-pane>

      </el-tabs>
      </div>
      <OpsInlineShell
        storage-key="configs"
        class="config-ops-side"
      />
      </div>
    </div>

    <el-dialog v-model="backupDialogVisible" title="备份配置" width="500px">
      <el-form :model="backupForm" label-width="100px">
        <el-form-item label="设备">
          <span>{{ selectedDeviceName }}</span>
        </el-form-item>
        <el-form-item label="配置类型">
          <el-radio-group v-model="backupForm.configType">
            <el-radio value="running">运行配置</el-radio>
            <el-radio value="startup">启动配置</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="backupForm.description" type="textarea" :rows="3" placeholder="请输入描述信息（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="backupDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="backupLoading" @click="handleBackup">开始备份</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="batchBackupDialogVisible" title="批量备份" width="640px">
      <el-form label-width="100px">
        <el-form-item label="配置类型">
          <el-radio-group v-model="batchBackupForm.configType">
            <el-radio value="running">运行配置</el-radio>
            <el-radio value="startup">启动配置</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="batchBackupForm.description" type="textarea" :rows="2" placeholder="可选，写入每台设备备份描述" />
        </el-form-item>
        <el-form-item label="筛选">
          <el-checkbox v-model="batchBackupForm.onlineOnly">仅在线设备</el-checkbox>
          <el-button size="small" style="margin-left: 12px;" @click="selectBatchBackupFiltered">勾选当前列表</el-button>
          <el-button size="small" @click="batchBackupSelectedIds = []">清空</el-button>
        </el-form-item>
        <el-form-item label="设备">
          <el-select
            v-model="batchBackupSelectedIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="选择要备份的设备"
            style="width: 100%;"
          >
            <el-option
              v-for="row in batchBackupDeviceOptions"
              :key="row.deviceId"
              :label="`${row.deviceName} (${row.ipAddress})`"
              :value="row.deviceId"
            />
          </el-select>
          <div class="form-hint">已选 {{ batchBackupSelectedIds.length }} 台</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchBackupDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchBackupLoading" @click="handleBatchBackup">开始备份</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="viewDialogVisible" title="查看配置" width="800px">
      <div class="config-content">
        <pre>{{ currentConfig?.content || '暂无内容' }}</pre>
      </div>
      <template #footer>
        <el-button @click="viewDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="templateDialogVisible" :title="editTemplate ? '编辑模板' : '新建模板'" width="700px">
      <el-form :model="templateForm" label-width="100px">
        <el-form-item label="模板名称" required>
          <el-input v-model="templateForm.name" placeholder="请输入模板名称" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="templateForm.category" placeholder="请选择分类" style="width: 100%;">
            <el-option label="接口配置" value="interface" />
            <el-option label="路由配置" value="route" />
            <el-option label="安全配置" value="security" />
            <el-option label="QoS配置" value="qos" />
            <el-option label="其他" value="other" />
          </el-select>
        </el-form-item>
        <el-form-item label="适用设备">
          <el-select v-model="templateForm.deviceType" placeholder="请选择设备类型" style="width: 100%;">
            <el-option label="通用" value="" />
            <el-option label="路由器 (AR)" value="AR" />
            <el-option label="交换机 (S)" value="S" />
            <el-option label="防火墙 (USG)" value="USG" />
          </el-select>
        </el-form-item>
        <el-form-item label="配置内容" required>
          <el-input v-model="templateForm.content" type="textarea" :rows="10" placeholder="请输入配置命令，每行一条" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="templateForm.description" type="textarea" :rows="3" placeholder="请输入模板描述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="templateDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="templateSubmitLoading" @click="handleTemplateSubmit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="applyDialogVisible" title="应用模板" width="620px">
      <el-form :model="applyForm" label-width="110px">
        <el-form-item label="目标设备" required>
          <el-select
            v-model="applyForm.deviceIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="可多选，走异步批量任务"
            style="width: 100%;"
          >
            <el-option
              v-for="device in configDevices"
              :key="device.id"
              :label="`${device.name} (${device.ipAddress})`"
              :value="device.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="变量替换">
          <el-switch v-model="applyForm.enableVariables" active-text="启用 ${name}/${ipAddress} 等" />
        </el-form-item>
        <el-alert
          v-if="templateVars.length"
          type="info"
          :closable="false"
          show-icon
          style="margin-bottom: 12px;"
          :title="`模板变量：${templateVars.join(', ')}`"
          description="内置：${deviceId} ${name} ${ipAddress} ${model} ${index}；开启变量替换后按设备自动填充。"
        />
        <el-form-item label="配置预览">
          <pre class="template-preview">{{ editTemplate?.content || '' }}</pre>
        </el-form-item>
        <el-form-item label="操作原因">
          <el-input v-model="applyForm.reason" type="textarea" :rows="2" placeholder="请输入操作原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applyDialogVisible = false">取消</el-button>
        <el-button @click="bridgeTemplateToBatch">填入批量下发</el-button>
        <el-button type="primary" :loading="applyLoading" @click="handleApplyTemplate">确认下发</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="taskDetailVisible" title="任务详情" width="720px">
      <el-descriptions v-if="currentTask" :column="2" border>
        <el-descriptions-item label="任务">{{ currentTask.label }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ formatTaskType(currentTask.type) }}</el-descriptions-item>
        <el-descriptions-item label="操作人">{{ currentTask.operator || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="taskStatusTag(currentTask.status)" size="small">{{ currentTask.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="进度">{{ currentTask.progress }}%</el-descriptions-item>
        <el-descriptions-item label="消息" :span="2">{{ currentTask.message }}</el-descriptions-item>
        <el-descriptions-item label="错误" :span="2" v-if="currentTask.error">{{ currentTask.error }}</el-descriptions-item>
        <el-descriptions-item label="结果" :span="2">
          <pre class="log-result">{{ formatTaskResult(currentTask.result) }}</pre>
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="taskDetailVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="syncGroupDialogVisible" title="同步分组备份计划" width="520px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom: 14px;"
        title="将按当前分组成员补齐/更新计划，并清理已离开该组、且标记为该组来源的计划。"
      />
      <el-form label-width="100px">
        <el-form-item label="设备分组" required>
          <el-select v-model="syncGroupForm.groupId" placeholder="选择分组" style="width: 100%;" filterable>
            <el-option
              v-for="g in deviceGroups"
              :key="g.id"
              :label="`${g.name}（${g.deviceCount || 0} 台）`"
              :value="g.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="备份周期">
          <el-radio-group v-model="syncGroupForm.scheduleType">
            <el-radio value="daily">每日</el-radio>
            <el-radio value="weekly">每周</el-radio>
            <el-radio value="monthly">每月</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="syncGroupForm.scheduleType === 'weekly'" label="星期">
          <el-select v-model="syncGroupForm.dayOfWeek" style="width: 100%;">
            <el-option :value="1" label="周一" />
            <el-option :value="2" label="周二" />
            <el-option :value="3" label="周三" />
            <el-option :value="4" label="周四" />
            <el-option :value="5" label="周五" />
            <el-option :value="6" label="周六" />
            <el-option :value="7" label="周日" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="syncGroupForm.scheduleType === 'monthly'" label="日期">
          <el-select v-model="syncGroupForm.dayOfMonth" style="width: 100%;">
            <el-option v-for="d in 31" :key="d" :value="d" :label="`${d} 日`" />
          </el-select>
        </el-form-item>
        <el-form-item label="备份时间">
          <el-time-picker
            v-model="syncGroupForm.scheduleTime"
            format="HH:mm"
            value-format="HH:mm"
            style="width: 100%;"
          />
        </el-form-item>
        <el-form-item label="配置类型">
          <el-radio-group v-model="syncGroupForm.configType">
            <el-radio value="running">运行配置</el-radio>
            <el-radio value="startup">启动配置</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="清理离组">
          <el-switch v-model="syncGroupForm.removeOrphans" active-text="清理已离组成员计划" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="syncGroupDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="syncGroupLoading" @click="submitSyncGroupPolicy">同步</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="scheduleDialogVisible" :title="editSchedule ? '编辑定时任务' : '新建备份策略'" width="560px">
      <el-form :model="scheduleForm" label-width="100px">
        <el-form-item v-if="!editSchedule" label="适用范围">
          <el-radio-group v-model="scheduleScope">
            <el-radio value="single">单设备</el-radio>
            <el-radio value="multi">多设备</el-radio>
            <el-radio value="group">按当前分组成员</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="editSchedule || scheduleScope === 'single'" label="设备">
          <el-select v-model="scheduleForm.deviceId" placeholder="请选择设备" style="width: 100%;" @change="handleScheduleDeviceChange">
            <el-option
              v-for="device in configDevices"
              :key="device.id"
              :label="`${device.name} (${device.ipAddress})`"
              :value="device.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-else-if="scheduleScope === 'multi'" label="设备">
          <el-select
            v-model="scheduleDeviceIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="选择多台设备应用同一策略"
            style="width: 100%;"
          >
            <el-option
              v-for="device in configDevices"
              :key="device.id"
              :label="`${device.name} (${device.ipAddress})`"
              :value="device.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="设备分组">
          <el-select v-model="scheduleGroupId" placeholder="请选择分组（按当前成员展开）" style="width: 100%;" filterable>
            <el-option
              v-for="g in deviceGroups"
              :key="g.id"
              :label="`${g.name}（${g.deviceCount || 0} 台）`"
              :value="g.id"
            />
          </el-select>
          <div class="form-hint">不会绑定组本身；成员变化后请使用「同步分组计划」。</div>
        </el-form-item>
        <el-form-item label="备份周期">
          <el-radio-group v-model="scheduleForm.scheduleType">
            <el-radio value="daily">每日</el-radio>
            <el-radio value="weekly">每周</el-radio>
            <el-radio value="monthly">每月</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="scheduleForm.scheduleType === 'weekly'" label="星期">
          <el-select v-model="scheduleForm.dayOfWeek" style="width: 100%;">
            <el-option :value="1" label="周一" />
            <el-option :value="2" label="周二" />
            <el-option :value="3" label="周三" />
            <el-option :value="4" label="周四" />
            <el-option :value="5" label="周五" />
            <el-option :value="6" label="周六" />
            <el-option :value="7" label="周日" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="scheduleForm.scheduleType === 'monthly'" label="日期">
          <el-select v-model="scheduleForm.dayOfMonth" style="width: 100%;">
            <el-option v-for="d in 31" :key="d" :value="d" :label="`${d} 日`" />
          </el-select>
        </el-form-item>
        <el-form-item label="备份时间">
          <el-time-picker
            v-model="scheduleForm.scheduleTime"
            format="HH:mm"
            value-format="HH:mm"
            placeholder="选择时间"
            style="width: 100%;"
          />
        </el-form-item>
        <el-form-item label="配置类型">
          <el-radio-group v-model="scheduleForm.configType">
            <el-radio value="running">运行配置</el-radio>
            <el-radio value="startup">启动配置</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="scheduleForm.isActive" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scheduleDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveSchedule">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="batchResultVisible" :title="batchResultTitle" width="860px">
      <div v-if="batchLoading" class="batch-progress">
        <el-progress 
          :percentage="batchProgress" 
          :status="batchProgress === 100 ? 'success' : undefined"
          :stroke-width="20"
        />
        <p class="progress-text">{{ batchProgressText }}</p>
      </div>
      
      <el-table v-else :data="batchResults" style="width: 100%;" max-height="420">
        <el-table-column prop="deviceName" label="设备" width="160" />
        <el-table-column prop="success" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'">
              {{ row.success ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="摘要" min-width="280">
          <template #default="{ row }">
            <span class="batch-result-summary">{{ row.message || row.result || row.configVersion || '-' }}</span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button
          v-if="!batchLoading && batchResultMode === 'apply' && batchFailedCount > 0"
          type="warning"
          @click="retryFailedBatch"
        >
          重试失败（{{ batchFailedCount }}）
        </el-button>
        <el-button @click="batchResultVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog 
      v-model="restoreDialogVisible" 
      title="恢复配置" 
      width="700px"
      :close-on-click-modal="false"
      :show-close="restoreProgress >= 100 || restoreError"
    >
      <div v-if="!restoreError && restoreProgress < 100" class="restore-progress">
        <el-progress 
          :percentage="restoreProgress" 
          :status="restoreProgress === 100 ? 'success' : undefined"
          :stroke-width="20"
        />
        <p class="progress-text">{{ restoreProgressText }}</p>
      </div>
      
      <div v-if="restoreError" class="restore-error">
        <el-result
          :icon="restoreFailCount > 0 && restoreSuccessCount > 0 ? 'warning' : 'error'"
          :title="restoreFailCount > 0 && restoreSuccessCount > 0 ? '恢复部分成功' : '恢复失败'"
          :sub-title="restoreErrorMessage"
        >
          <template #extra>
            <el-button type="primary" @click="showRestoreDetail">查看详情</el-button>
            <el-button @click="restoreDialogVisible = false">关闭</el-button>
          </template>
        </el-result>
      </div>
      
      <div v-if="restoreSuccess" class="restore-success">
        <el-result
          icon="success"
          title="恢复成功"
          :sub-title="`成功执行 ${restoreSuccessCount} 条命令`"
        >
          <template #extra>
            <el-button type="primary" @click="showRestoreDetail">查看详情</el-button>
            <el-button @click="restoreDialogVisible = false">关闭</el-button>
          </template>
        </el-result>
      </div>
      
      <div v-if="showRestoreLog" class="restore-log">
        <pre>{{ restoreLogContent }}</pre>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  DocumentCopy, Download, Connection, Files, Plus, Timer, Upload, Search,
  ArrowLeft, ArrowRight, Edit, Guide, Tickets, Document
} from '@element-plus/icons-vue'
import { deviceApi, configApi, configTemplateApi, backupScheduleApi, deviceGroupApi, configChangeLogApi } from '@/api/device'
import { canConfigBackup } from '@/utils/deviceCapabilities'
import { diffLines, toUnifiedDiff } from '@/utils/lineDiff'
import { ensureBlobFile } from '@/utils/blobResponse'
import { useAuthStore } from '@/stores/auth'
import { openWebTerminal } from '@/composables/webTerminalBus'
import { askOpsAssistant, syncOpsAssistantFocus } from '@/composables/askOpsAssistant'
import { clearPageContext, setPageContext, usePageOpsBus } from '@/composables/pageOpsBus'
import { requestOpenOpsInline } from '@/composables/useOpsInlinePanel'
import OpsInlineShell from '@/components/ops-assistant/OpsInlineShell.vue'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const { sink: pageSink } = usePageOpsBus()
const currentOperator = () => auth.displayName || auth.user?.username || 'system'

const formatScheduleCycle = (row) => {
  const type = row?.scheduleType || 'daily'
  if (type === 'weekly') {
    const names = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']
    const d = row.dayOfWeek || 1
    return `每周${names[d] || d}`
  }
  if (type === 'monthly') {
    return `每月${row.dayOfMonth || 1}日`
  }
  return '每日'
}

const healthOkCount = computed(() => {
  const list = healthOverview.value.devices || []
  return list.filter(d => d.health === 'ok').length
})

const filteredHealthDevices = computed(() => {
  let list = healthOverview.value.devices || []
  if (healthFilter.value) {
    list = list.filter(d => d.health === healthFilter.value)
  }
  const kw = (deviceListKeyword.value || '').trim().toLowerCase()
  if (kw) {
    list = list.filter(d =>
      (d.deviceName || '').toLowerCase().includes(kw) ||
      (d.ipAddress || '').toLowerCase().includes(kw)
    )
  }
  return list
})

const selectedHealthRow = computed(() =>
  (healthOverview.value.devices || []).find(d => d.deviceId === selectedDevice.value) || null
)

const canStartCompare = computed(() => {
  if (!compareDeviceId.value) return false
  if (!compareTarget.value) return false
  if (compareMode.value === 'backup') return !!compareSource.value
  return true
})

const compareVersionOptions = computed(() => {
  const list = compareVersions.value?.length ? compareVersions.value : []
  return Array.isArray(list) ? list : []
})

const compareDeviceName = computed(() => {
  const device = devices.value.find(d => d.id === compareDeviceId.value)
  return device ? `${device.name} (${device.ipAddress})` : ''
})

const compareLeftTitle = computed(() => {
  if (compareMode.value === 'live-startup') return '左侧 · 当前启动配置'
  if (compareMode.value === 'live-running') return '左侧 · 当前运行配置'
  const c = compareVersionOptions.value.find(x => x.id === compareSource.value)
  return c ? `左侧 · ${formatCompareOption(c)}` : '左侧（源）'
})

const compareRightTitle = computed(() => {
  const c = compareVersionOptions.value.find(x => x.id === compareTarget.value)
  return c ? `右侧 · ${formatCompareOption(c)}` : '右侧（目标）'
})

const activeTab = ref('backup')
const devices = ref([])
/** 可用于 SSH 配置备份/下发的设备（排除虚拟 PC 等） */
const configDevices = computed(() => devices.value.filter(d => canConfigBackup(d)))
const configs = ref([])
const compareVersions = ref([])
const compareDeviceId = ref(null)
const compareLeftText = ref('')
const compareRightText = ref('')
const compareLeftPane = ref(null)
const compareRightPane = ref(null)
let compareScrollLock = false
const healthOverview = ref({
  deviceTotal: 0,
  neverBackedUp: 0,
  staleOverDays: 0,
  scheduleFailed: 0,
  staleDays: 7,
  devices: []
})
const healthLoading = ref(false)
const healthFilter = ref('')
const deviceListKeyword = ref('')
const compareMode = ref('backup')
const compareIgnoreNoise = ref(true)
const compareLoading = ref(false)
const templates = ref([])
const schedules = ref([])
const loading = ref(false)
const templateLoading = ref(false)
const backupLoading = ref(false)
const batchExportLoading = ref(false)
const batchDeleteLoading = ref(false)
const testingConnection = ref(false)
const templateSubmitLoading = ref(false)
const applyLoading = ref(false)
const scheduleLoading = ref(false)

const selectedDevice = ref(null)
const configTableRef = ref(null)
const selectedConfigs = ref([])
const configPage = ref(1)
const configPageSize = ref(20)
const configTotal = ref(0)

const backupDialogVisible = ref(false)
const batchBackupDialogVisible = ref(false)
const batchBackupLoading = ref(false)
const batchBackupSelectedIds = ref([])
const batchBackupForm = ref({
  configType: 'running',
  description: '',
  onlineOnly: true
})
const viewDialogVisible = ref(false)
const templateDialogVisible = ref(false)
const applyDialogVisible = ref(false)

const currentConfig = ref(null)
const editTemplate = ref(null)
const editSchedule = ref(null)

const compareSource = ref(null)
const compareTarget = ref(null)
const compareResult = ref(null)

const scheduleDialogVisible = ref(false)
const batchDialogVisible = ref(false)
const batchResultVisible = ref(false)
const batchResultTitle = ref('批量下发结果')
const batchResultMode = ref('apply')
const batchResults = ref([])

const restoreDialogVisible = ref(false)
const restoreProgress = ref(0)
const restoreProgressText = ref('准备开始...')
const restoreError = ref(false)
const restoreErrorMessage = ref('')
const restoreSuccess = ref(false)
const restoreSuccessCount = ref(0)
const restoreFailCount = ref(0)
const restoreLogContent = ref('')
const showRestoreLog = ref(false)

const scheduleForm = ref({
  deviceId: null,
  deviceName: '',
  scheduleType: 'daily',
  scheduleTime: '02:00',
  dayOfWeek: 1,
  dayOfMonth: 1,
  configType: 'running',
  isActive: true
})
const scheduleScope = ref('single')
const scheduleDeviceIds = ref([])
const scheduleGroupId = ref(null)

const configTasks = ref([])
const taskListLoading = ref(false)
const taskDetailVisible = ref(false)
const currentTask = ref(null)
let taskPollTimer = null

const deviceGroups = ref([])
const groupLoading = ref(false)
const syncGroupDialogVisible = ref(false)
const syncGroupLoading = ref(false)
const syncGroupForm = ref({
  groupId: null,
  scheduleType: 'daily',
  scheduleTime: '02:00',
  dayOfWeek: 1,
  dayOfMonth: 1,
  configType: 'running',
  removeOrphans: true
})

const batchForm = ref({
  configType: 'custom',
  params: {
    interfaceType: 'GigabitEthernet',
    interfaceIndex: '0/0/1',
    ipAddress: '',
    mask: '255.255.255.0',
    description: '',
    vlanId: 10,
    vlanIds: '10',
    vlanName: '',
    vlanNameCmd: 'name',
    portMode: 'access',
    ports: '',
    trunkSetPvid: false,
    enableVlanif: false,
    vlanifIp: '',
    vlanifMask: '255.255.255.0',
    destNetwork: '',
    destMask: '255.255.255.0',
    nextHop: '',
    preference: undefined,
    processId: 1,
    enableRouterId: false,
    routerId: '${ipAddress}',
    areaId: '0',
    ospfNetwork: '',
    ospfMask: '0.0.0.255',
    ospfNetworks: '',
    network: '',
    wildcard: '0.0.0.255',
    customContent: ''
  }
})

const batchStep = ref(0)
const deviceSearch = ref('')
const batchDeviceTable = ref(null)
const batchLoading = ref(false)
const batchProgress = ref(0)
const batchProgressText = ref('准备下发...')
const batchEnableVariables = ref(false)
const batchReason = ref('')
const batchWaveSize = ref(0)
const batchPreviewLoading = ref(false)
const batchPreviewSummary = ref(null)
const compliance = ref(null)
const complianceLoading = ref(false)
const recentChanges = ref([])
const recentChangesLoading = ref(false)
const batchGroupFilter = ref(null)
const batchOnlineOnly = ref(false)
const batchLastCommands = ref('')
const batchLastEnableVariables = ref(false)
const batchTemplateId = ref(null)

const BATCH_STRUCT_TYPES = new Set(['interface', 'vlan', 'static-route', 'ospf'])
const hasBatchVar = (s) => /\$\{[a-zA-Z0-9_]+\}/.test(String(s || ''))

/** 前缀长度 → 子网掩码 */
const prefixToSubnetMask = (prefix) => {
  const p = Number(prefix)
  if (!Number.isInteger(p) || p < 0 || p > 32) return null
  const parts = []
  for (let i = 0; i < 4; i++) {
    const bits = Math.min(8, Math.max(0, p - i * 8))
    parts.push(bits === 0 ? 0 : 256 - (2 ** (8 - bits)))
  }
  return parts.join('.')
}

/** 子网掩码 → 通配符；通配符原样；支持 /24 */
const toWildcardMask = (input) => {
  let s = String(input || '').trim()
  if (!s || hasBatchVar(s)) return s
  if (s.startsWith('/')) s = s.slice(1)
  if (/^\d{1,2}$/.test(s)) {
    const mask = prefixToSubnetMask(s)
    return mask ? mask.split('.').map(n => 255 - Number(n)).join('.') : s
  }
  const parts = s.split('.').map(n => Number(n))
  if (parts.length !== 4 || parts.some(n => !Number.isInteger(n) || n < 0 || n > 255)) {
    return s
  }
  if (parts[0] === 255) {
    return parts.map(n => 255 - n).join('.')
  }
  return s
}

/** 解析「地址 [掩码|/前缀]」或「地址/前缀」 */
const parseNetworkAndMask = (addrRaw, maskRaw = '') => {
  let addr = String(addrRaw || '').trim()
  let mask = String(maskRaw || '').trim()
  if (!addr) return null
  if (addr.includes('/')) {
    const [ip, pref] = addr.split('/')
    addr = ip.trim()
    if (!mask) mask = `/${pref}`
  }
  if (!mask) return { network: addr, mask: '', wildcard: '' }
  const subnetOrWc = mask.startsWith('/') ? prefixToSubnetMask(mask.slice(1)) : (
    mask.split('.').length === 4 && Number(mask.split('.')[0]) === 255 ? mask : null
  )
  return {
    network: addr,
    mask: subnetOrWc || (mask.includes('.') && Number(mask.split('.')[0]) === 255 ? mask : ''),
    wildcard: toWildcardMask(mask)
  }
}

/** 华为接口名规范化：GE0/0/1 → GigabitEthernet0/0/1 */
const normalizeHuaweiIfName = (raw) => {
  let s = String(raw || '').trim().replace(/\s+/g, '')
  if (!s || hasBatchVar(s)) return s
  const rules = [
    [/^(?:gigabitethernet|gi|ge)(.+)$/i, 'GigabitEthernet'],
    [/^(?:xgigabitethernet|xge)(.+)$/i, 'XGigabitEthernet'],
    [/^(?:ethernet|eth)(.+)$/i, 'Ethernet'],
    [/^(?:serial|s)(\d.*)$/i, 'Serial'],
    [/^(?:loopback|lo)(\d.*)$/i, 'LoopBack'],
    [/^(?:vlanif|vlan-if|vlan)(\d+)$/i, 'Vlanif']
  ]
  for (const [re, type] of rules) {
    const m = s.match(re)
    if (m) return `${type}${m[1]}`
  }
  return s
}

/** 解析 VLAN ID 列表：10,20,30 或 10-15 */
const parseVlanIds = (params) => {
  const raw = String(params?.vlanIds ?? '').trim()
  const ids = []
  if (raw) {
    for (const token of raw.split(/[,，\s]+/).map(s => s.trim()).filter(Boolean)) {
      if (/^\d+\s*-\s*\d+$/.test(token)) {
        const [a, b] = token.split('-').map(s => Number(s.trim()))
        if (!Number.isInteger(a) || !Number.isInteger(b)) continue
        const lo = Math.min(a, b)
        const hi = Math.max(a, b)
        for (let i = lo; i <= hi; i++) {
          if (i >= 1 && i <= 4094) ids.push(i)
        }
      } else {
        const n = Number(token)
        if (Number.isInteger(n) && n >= 1 && n <= 4094) ids.push(n)
      }
    }
  } else if (params?.vlanId != null) {
    const n = Number(params.vlanId)
    if (Number.isInteger(n) && n >= 1 && n <= 4094) ids.push(n)
  }
  return [...new Set(ids)].sort((a, b) => a - b)
}

/** 生成 vlan batch 参数：10 to 12 20 30 */
const formatVlanBatchArgs = (ids) => {
  if (!ids.length) return ''
  const parts = []
  let i = 0
  while (i < ids.length) {
    let j = i
    while (j + 1 < ids.length && ids[j + 1] === ids[j] + 1) j++
    if (j > i) {
      parts.push(`${ids[i]} to ${ids[j]}`)
    } else {
      parts.push(String(ids[i]))
    }
    i = j + 1
  }
  return parts.join(' ')
}

/** 解析接口列表 */
const parsePortList = (raw) => {
  const parts = String(raw || '')
    .split(/[\n,;]+/)
    .map(s => s.trim())
    .filter(Boolean)
    .map(normalizeHuaweiIfName)
  return [...new Set(parts)]
}

/** OSPF 仅单个宣告网段 */
const parseOspfNetwork = (params) => {
  const primary = parseNetworkAndMask(params.ospfNetwork || params.network, params.ospfMask || params.wildcard)
  if (primary?.network && primary.wildcard) {
    return { network: primary.network, wildcard: primary.wildcard }
  }
  return null
}

const vlanIdList = computed(() => parseVlanIds(batchForm.value.params))
const vlanPortList = computed(() => parsePortList(batchForm.value.params.ports))

watch(vlanIdList, (ids) => {
  if (ids.length > 1) {
    if (batchForm.value.params.portMode === 'access') {
      batchForm.value.params.portMode = 'trunk'
    }
    batchForm.value.params.enableVlanif = false
  }
})

const batchSelectedDevices = ref([])
const batchSelectedDevicesList = ref([])

const filteredDevices = computed(() => {
  let list = devices.value || []
  if (batchGroupFilter.value != null) {
    list = list.filter(d => d.groupId === batchGroupFilter.value)
  }
  if (batchOnlineOnly.value) {
    list = list.filter(d => d.status === 'online')
  }
  const q = (deviceSearch.value || '').trim().toLowerCase()
  if (q) {
    list = list.filter(d =>
      (d.name || '').toLowerCase().includes(q) ||
      (d.ipAddress || '').includes(deviceSearch.value.trim())
    )
  }
  return list
})

const batchOfflineSelectedCount = computed(() =>
  batchSelectedDevicesList.value.filter(d => d.status !== 'online').length
)

const batchFailedCount = computed(() =>
  (batchResults.value || []).filter(r => !r.success).length
)

const batchConfirmTitle = computed(() =>
  batchOfflineSelectedCount.value
    ? `配置预览（含 ${batchOfflineSelectedCount.value} 台离线）`
    : '配置预览'
)

const batchConfirmDesc = computed(() => {
  const n = batchSelectedDevices.value.length
  const off = batchOfflineSelectedCount.value
  if (off) {
    return `将下发到 ${n} 台设备，其中 ${off} 台当前离线，SSH 可能失败。`
  }
  return `以下配置将下发到 ${n} 台设备（预览已按设备展开变量）。`
})

const replaceBatchVariables = (content, device, index) => {
  if (!content || !device) return content || ''
  return content
    .replace(/\$\{deviceId\}/g, String(device.id))
    .replace(/\$\{name\}/g, device.name || '')
    .replace(/\$\{ipAddress\}/g, device.ipAddress || '')
    .replace(/\$\{model\}/g, device.model || '')
    .replace(/\$\{index\}/g, String(index))
}

const batchCommandsNeedVars = (commands) => {
  const cmd = commands ?? buildBatchCommands()
  const t = batchForm.value.configType
  const p = batchForm.value.params
  return batchEnableVariables.value
    || (t === 'ospf' && p.enableRouterId && hasBatchVar(p.routerId || '${ipAddress}'))
    || hasBatchVar(cmd)
}

const batchSameParamRisk = computed(() => {
  if (batchSelectedDevices.value.length < 2) return false
  const t = batchForm.value.configType
  if (!BATCH_STRUCT_TYPES.has(t)) return false
  const p = batchForm.value.params
  if (t === 'ospf') {
    return true
  }
  if (t === 'interface') {
    const ip = String(p.ipAddress || '').trim()
    return !!ip && !hasBatchVar(ip)
  }
  if (t === 'static-route') {
    const hop = String(p.nextHop || '').trim()
    return !!hop && !hasBatchVar(hop)
  }
  if (t === 'vlan') {
    if (p.enableVlanif) {
      const ip = String(p.vlanifIp || '').trim()
      return !!ip && !hasBatchVar(ip)
    }
    return vlanPortList.value.length > 0
  }
  return false
})

const batchSameParamHint = computed(() => {
  const t = batchForm.value.configType
  if (t === 'interface') {
    return '接口 IP 等将写到每台设备。若各机地址不同，请填 ${ipAddress} 并开启变量，或改用「自定义命令 / 模板」。'
  }
  if (t === 'static-route') {
    return '下一跳等将写到每台设备。若各机不同，请使用 ${ipAddress} 变量或自定义命令。'
  }
  if (t === 'ospf') {
    return 'OSPF 进程/区域/network 默认对各设备相同。若开启 Router-ID 且为 ${ipAddress}，会按设备展开。'
  }
  if (t === 'vlan') {
    if (batchForm.value.params.enableVlanif) {
      return 'Vlanif IP 将对所选设备同参下发（除非使用 ${ipAddress}）。端口名也会相同。'
    }
    return 'VLAN 与端口绑定将对所选设备使用相同端口名；端口因机而异时请用自定义命令。'
  }
  return '结构化类型默认同参下发，按设备差异请用变量或自定义命令。'
})

const batchLivePreviewDevice = computed(() => batchSelectedDevicesList.value[0] || null)

const batchLivePreviewText = computed(() => {
  const device = batchLivePreviewDevice.value
  const needExpand = batchCommandsNeedVars()
  let commands = buildBatchCommands()
  if (needExpand && device) {
    commands = replaceBatchVariables(commands, device, 1)
  }
  return commands || '（填写参数后显示命令）'
})

const batchPreviews = computed(() => {
  if (batchStep.value !== 2) return []
  const needExpand = batchCommandsNeedVars()
  return batchSelectedDevicesList.value.map((device, i) => {
    let commands = buildBatchCommands()
    if (needExpand) {
      commands = replaceBatchVariables(commands, device, i + 1)
    }
    return {
      deviceId: device.id,
      deviceName: device.name,
      deviceIp: device.ipAddress,
      offline: device.status !== 'online',
      commands
    }
  })
})

const configTypes = [
  {
    value: 'custom',
    label: '自定义 / 模板',
    icon: Edit,
    recommended: true,
    description: '自由命令，支持按设备变量'
  },
  {
    value: 'interface',
    label: '接口配置',
    icon: Connection,
    sameParams: true,
    description: '接口 IP / 描述（同参）'
  },
  {
    value: 'vlan',
    label: 'VLAN配置',
    icon: Tickets,
    sameParams: true,
    description: '多 VLAN / 绑端口 / 单 VLAN 可配 Vlanif'
  },
  {
    value: 'static-route',
    label: '静态路由',
    icon: Guide,
    sameParams: true,
    description: '静态路由条目（同参）'
  },
  {
    value: 'ospf',
    label: 'OSPF配置',
    icon: Document,
    sameParams: true,
    description: '单网段宣告，RID 可选'
  }
]

const selectConfigType = (type) => {
  batchForm.value.configType = type.value
  const p = batchForm.value.params
  if (type.value === 'ospf' && p.enableRouterId) {
    if (!String(p.routerId || '').trim()) p.routerId = '${ipAddress}'
    batchEnableVariables.value = hasBatchVar(p.routerId || '${ipAddress}')
  }
  if (hasBatchVar(p.customContent)
    || hasBatchVar(p.ipAddress)
    || hasBatchVar(p.nextHop)
    || hasBatchVar(p.description)
    || hasBatchVar(p.vlanifIp)
    || hasBatchVar(p.routerId)
    || hasBatchVar(p.ospfNetwork)) {
    batchEnableVariables.value = true
  }
}

const applyBatchTemplate = (id) => {
  if (id == null) return
  const t = templates.value.find(x => x.id === id)
  if (!t) {
    ElMessage.warning('未找到模板')
    return
  }
  batchForm.value.configType = 'custom'
  batchForm.value.params.customContent = t.content || ''
  batchEnableVariables.value = hasBatchVar(t.content)
  ElMessage.success(`已填入模板「${t.name}」`)
}

const validateBatchConfig = () => {
  const t = batchForm.value.configType
  const p = batchForm.value.params
  if (!t) return '请选择配置类型'
  if (t === 'custom') {
    if (!String(p.customContent || '').trim()) return '请填写配置命令，或从模板填入'
  } else if (t === 'interface') {
    if (!String(p.interfaceType || '').trim()) return '请选择接口类型'
    if (!String(p.interfaceIndex || '').trim()) return '请填写接口编号'
    if (p.ipAddress && !String(p.mask || '').trim()) return '填写 IP 时请同时填写子网掩码'
  } else if (t === 'vlan') {
    const ids = parseVlanIds(p)
    if (!ids.length) return '请填写至少一个合法 VLAN ID（1–4094），支持 10,20 或 10-15'
    if (ids.length > 1 && p.portMode === 'access' && parsePortList(p.ports).length) {
      return '多 VLAN 绑端口时请使用 Trunk 模式'
    }
    if (p.enableVlanif) {
      if (ids.length !== 1) return 'Vlanif 仅支持单个 VLAN，请只填一个 VLAN ID'
      if (!String(p.vlanifIp || '').trim()) return '已开启 Vlanif，请填写 IP'
      if (!String(p.vlanifMask || '').trim()) return '已开启 Vlanif，请填写子网掩码'
    }
  } else if (t === 'static-route') {
    const dest = parseNetworkAndMask(p.destNetwork, p.destMask)
    if (!dest?.network) return '请填写目的网络'
    if (!dest.mask && !String(p.destNetwork || '').includes('/')) {
      return '请填写目的掩码，或使用 CIDR（如 192.168.10.0/24）'
    }
    if (!String(p.nextHop || '').trim()) return '请填写下一跳'
  } else if (t === 'ospf') {
    if (p.processId == null) return '请填写 OSPF 进程 ID'
    if (!String(p.areaId ?? '').toString().trim()) return '请填写区域 ID'
    if (p.enableRouterId && !String(p.routerId || '').trim()) return '已开启 Router-ID，请填写'
    const net = parseOspfNetwork(p)
    if (!net) {
      return '请填写单个宣告网络与掩码/通配符（支持 0.0.0.255、255.255.255.0 或 /24）'
    }
  }
  const cmd = buildBatchCommands()
  if (!String(cmd || '').trim()) return '生成的命令为空，请检查参数'
  return null
}

const goBatchPreview = async () => {
  const err = validateBatchConfig()
  if (err) {
    ElMessage.warning(err)
    return
  }
  if (batchSameParamRisk.value) {
    try {
      await ElMessageBox.confirm(
        batchSameParamHint.value + '\n\n确认仍要对所选设备同参下发？',
        '同参下发确认',
        { type: 'warning', confirmButtonText: '继续预览', cancelButtonText: '返回修改' }
      )
    } catch {
      return
    }
  }
  batchStep.value = 2
}

const handleBatchSelectionChange = (selection) => {
  batchSelectedDevicesList.value = selection
  batchSelectedDevices.value = selection.map(d => d.id)
}

const selectOnlineDevices = async () => {
  const online = filteredDevices.value.filter(d => d.status === 'online')
  if (!online.length) {
    ElMessage.warning('当前列表没有在线设备')
    return
  }
  batchSelectedDevices.value = online.map(d => d.id)
  batchSelectedDevicesList.value = online
  await nextTick()
  if (batchDeviceTable.value) {
    batchDeviceTable.value.clearSelection?.()
    online.forEach(row => batchDeviceTable.value.toggleRowSelection(row, true))
  }
  ElMessage.success(`已勾选 ${online.length} 台在线设备`)
}

const generateConfigCommands = () => buildBatchCommands()

/** 预览与实发共用，避免两边命令不一致 */
const buildBatchCommands = () => {
  const params = batchForm.value.params
  const type = batchForm.value.configType

  if (type === 'custom') {
    const body = String(params.customContent || '')
      .split('\n')
      .map(l => l.replace(/\s+$/, ''))
      .filter(c => c.trim())
    if (!body.length) return ''
    const joined = body.join('\n')
    if (/^\s*system-view\b/im.test(joined) || /(?:^|\n)\s*return\b/im.test(joined)) {
      return joined
    }
    return ['system-view', ...body, 'return', 'save'].join('\n')
  }

  const lines = ['system-view']

  switch (type) {
    case 'interface': {
      const ifType = String(params.interfaceType || 'GigabitEthernet').trim()
      const ifIndex = String(params.interfaceIndex || '').trim()
      const ifName = normalizeHuaweiIfName(`${ifType}${ifIndex}`)
      lines.push(`interface ${ifName}`)
      const ip = String(params.ipAddress || '').trim()
      const mask = String(params.mask || '').trim()
      if (ip) {
        lines.push(`ip address ${ip} ${mask || '255.255.255.0'}`)
      }
      if (params.description) {
        lines.push(`description ${params.description}`)
      }
      lines.push('undo shutdown')
      break
    }

    case 'vlan': {
      const ids = parseVlanIds(params)
      if (!ids.length) return ''
      if (ids.length === 1) {
        lines.push(`vlan ${ids[0]}`)
        if (params.vlanName) {
          const nameCmd = params.vlanNameCmd === 'description' ? 'description' : 'name'
          lines.push(`${nameCmd} ${params.vlanName}`)
        }
        lines.push('quit')
      } else {
        lines.push(`vlan batch ${formatVlanBatchArgs(ids)}`)
      }
      const ports = parsePortList(params.ports)
      const mode = (ids.length > 1 ? 'trunk' : (params.portMode || 'access'))
      const allowList = ids.join(' ')
      for (const port of ports) {
        lines.push(`interface ${port}`)
        lines.push(`port link-type ${mode}`)
        if (mode === 'trunk') {
          lines.push(`port trunk allow-pass vlan ${allowList}`)
          if (params.trunkSetPvid) {
            lines.push(`port trunk pvid vlan ${ids[0]}`)
          }
        } else {
          lines.push(`port default vlan ${ids[0]}`)
        }
        lines.push('quit')
      }
      if (params.enableVlanif && ids.length === 1) {
        const vip = String(params.vlanifIp || '').trim()
        const vmask = String(params.vlanifMask || '').trim() || '255.255.255.0'
        lines.push(`interface Vlanif${ids[0]}`)
        if (vip) {
          lines.push(`ip address ${vip} ${vmask}`)
        }
        lines.push('quit')
      }
      break
    }

    case 'static-route': {
      const dest = parseNetworkAndMask(params.destNetwork, params.destMask)
      const network = dest?.network || params.destNetwork
      const mask = dest?.mask || params.destMask
      const hop = normalizeHuaweiIfName(String(params.nextHop || '').trim()) || String(params.nextHop || '').trim()
      let cmd = `ip route-static ${network} ${mask} ${hop}`
      const pref = params.preference
      if (pref != null && pref !== '' && Number(pref) > 0) {
        cmd += ` preference ${pref}`
      }
      lines.push(cmd)
      break
    }

    case 'ospf': {
      const processId = params.processId
      if (params.enableRouterId) {
        const routerId = String(params.routerId || '${ipAddress}').trim() || '${ipAddress}'
        lines.push(`ospf ${processId} router-id ${routerId}`)
      } else {
        lines.push(`ospf ${processId}`)
      }
      lines.push(`area ${String(params.areaId).trim()}`)
      const net = parseOspfNetwork(params)
      if (net) {
        lines.push(`network ${net.network} ${net.wildcard}`)
      }
      lines.push('quit')
      lines.push('quit')
      break
    }

    default:
      return ''
  }

  lines.push('return')
  lines.push('save')
  return lines.join('\n')
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** 轮询异步配置任务，按服务端真实进度更新 UI */
const pollConfigTask = async (taskId, onTick) => {
  for (let i = 0; i < 600; i++) {
    const task = await configApi.getConfigTask(taskId)
    if (onTick) onTick(task)
    const status = task?.status
    if (['SUCCESS', 'PARTIAL', 'FAILED', 'PAUSED', 'CANCELLED'].includes(status)) {
      return task
    }
    await sleep(500)
  }
  throw new Error('任务超时，请稍后在任务中心或审计记录中查看结果')
}

const executeBatchConfig = async (deviceIdsOverride = null) => {
  // 勿用 `override || selected`：按钮 @click 会传入 MouseEvent
  const targetIds = Array.isArray(deviceIdsOverride) ? deviceIdsOverride : batchSelectedDevices.value
  if (!targetIds?.length) {
    ElMessage.warning('请选择设备')
    return
  }
  const validationError = validateBatchConfig()
  if (validationError) {
    ElMessage.warning(validationError)
    return
  }

  const targetList = devices.value.filter(d => targetIds.includes(d.id))
  const offline = targetList.filter(d => d.status !== 'online')
  if (offline.length) {
    try {
      await ElMessageBox.confirm(
        `所选设备中有 ${offline.length} 台离线（${offline.slice(0, 5).map(d => d.name).join('、')}${offline.length > 5 ? '…' : ''}），SSH 下发可能失败。是否继续？`,
        '离线设备确认',
        { type: 'warning', confirmButtonText: '继续下发', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }

  batchResultMode.value = 'apply'
  batchResultTitle.value = '批量下发结果'
  batchLoading.value = true
  batchResultVisible.value = true
  batchProgress.value = 0
  batchProgressText.value = '提交任务...'

  try {
    const commands = buildBatchCommands()
    const needVars = batchCommandsNeedVars(commands)
    batchLastCommands.value = commands
    batchLastEnableVariables.value = needVars

    const started = await configApi.startBatchApplyTask(targetIds, {
      content: commands,
      enableVariables: needVars,
      parallel: false,
      reason: batchReason.value.trim() || '批量配置下发',
      waveSize: Number(batchWaveSize.value) > 0 ? Number(batchWaveSize.value) : undefined
    })
    if (started?.needsApproval) {
      batchResultVisible.value = false
      ElMessage.warning(started.message || `已创建审批单 #${started.requestId}，请在策略护栏批准后再下发`)
      return
    }
    const taskId = started.taskId || started.task?.id
    if (!taskId) throw new Error('未返回任务 ID')

    const task = await pollConfigTask(taskId, (t) => {
      batchProgress.value = Number(t.progress || 0)
      batchProgressText.value = t.message || '执行中...'
    })

    const res = task.result || {}
    batchResults.value = (res.results || []).map(r => ({
      ...r,
      message: r.message || (r.success ? '成功' : (r.result || '失败'))
    }))
    batchProgress.value = Number(task.progress || 100)
    batchProgressText.value = task.message || '下发完成！'

    if (task.status === 'PAUSED') {
      ElMessage.warning(task.message || `首波完成，待继续 ${res.pendingCount || 0} 台，请到任务中心继续`)
      activeTab.value = 'tasks'
      loadConfigTasks()
      return
    }

    if (task.status === 'CANCELLED') {
      ElMessage.info('任务已取消')
    } else if (task.status === 'FAILED') {
      ElMessage.error(task.error || task.message || '批量下发失败')
    } else if (task.status === 'PARTIAL') {
      ElMessage.warning(`部分成功：${res.successCount}/${res.totalCount}，可对失败设备重试`)
    } else {
      ElMessage.success(`批量下发完成，成功 ${res.successCount}/${res.totalCount}`)
      // 全部成功才重置向导
      batchStep.value = 0
      batchSelectedDevices.value = []
      batchSelectedDevicesList.value = []
      batchForm.value.configType = 'custom'
      batchForm.value.params.customContent = ''
      batchTemplateId.value = null
      batchEnableVariables.value = false
      batchReason.value = ''
      batchWaveSize.value = 0
      batchPreviewSummary.value = null
    }
    loadConfigTasks()
  } catch (error) {
    batchProgress.value = 0
    batchProgressText.value = '下发失败'
    ElMessage.error('批量下发失败: ' + (error.response?.data?.message || error.message))
  } finally {
    batchLoading.value = false
  }
}

const runBatchPrecheck = async () => {
  const targetIds = batchSelectedDevices.value
  if (!targetIds?.length) {
    ElMessage.warning('请选择设备')
    return
  }
  const validationError = validateBatchConfig()
  if (validationError) {
    ElMessage.warning(validationError)
    return
  }
  batchPreviewLoading.value = true
  try {
    const commands = buildBatchCommands()
    const needVars = batchCommandsNeedVars(commands)
    const res = await configApi.previewBatchApply(targetIds, {
      content: commands,
      enableVariables: needVars
    })
    batchPreviewSummary.value = res
    ElMessage.success(res.message || '预检完成')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e.message || '预检失败')
  } finally {
    batchPreviewLoading.value = false
  }
}

const retryFailedBatch = async () => {
  const failedIds = (batchResults.value || [])
    .filter(r => !r.success && r.deviceId != null)
    .map(r => Number(r.deviceId))
  if (!failedIds.length) {
    ElMessage.info('没有失败设备')
    return
  }
  if (!batchLastCommands.value && !batchForm.value.configType) {
    ElMessage.warning('无法恢复上次命令，请回到预览步重新下发')
    return
  }
  batchSelectedDevices.value = failedIds
  batchSelectedDevicesList.value = devices.value.filter(d => failedIds.includes(d.id))
  batchResultVisible.value = false
  batchStep.value = 2
  await nextTick()
  await executeBatchConfig(failedIds)
}

const backupForm = ref({
  configType: 'running',
  description: ''
})

const templateForm = ref({
  name: '',
  category: '',
  deviceType: '',
  content: '',
  description: ''
})

const applyForm = ref({
  deviceIds: [],
  enableVariables: false,
  reason: ''
})

const templateVars = computed(() => {
  const content = editTemplate.value?.content || ''
  const found = new Set()
  const re = /\$\{([a-zA-Z0-9_]+)\}/g
  let m
  while ((m = re.exec(content)) !== null) {
    found.add('${' + m[1] + '}')
  }
  return [...found]
})

const selectedDeviceName = computed(() => {
  const device = devices.value.find(d => d.id === selectedDevice.value)
  return device ? `${device.name} (${device.ipAddress})` : ''
})

const formatDate = (dateStr) => {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN')
}

const getStatusTag = (status) => {
  const map = { success: 'success', failed: 'danger', pending: 'warning', running: '' }
  return map[status] || 'info'
}

const loadDevices = async () => {
  try {
    const res = await deviceApi.getDevices()
    devices.value = Array.isArray(res) ? res : (res.data || [])
  } catch (error) {
    ElMessage.error('加载设备列表失败: ' + (error.response?.data?.message || error.message))
  }
}

const loadBackupHealth = async () => {
  healthLoading.value = true
  try {
    const res = await configApi.getBackupHealth()
    healthOverview.value = res || healthOverview.value
  } catch (e) {
    ElMessage.error('加载备份健康态失败')
  } finally {
    healthLoading.value = false
  }
}

const setHealthFilter = (key) => {
  healthFilter.value = healthFilter.value === key ? '' : key
}

const healthTagType = (health) => {
  if (health === 'ok') return 'success'
  if (health === 'stale') return 'warning'
  if (health === 'never' || health === 'failed') return 'danger'
  return 'info'
}

const selectDeviceFromHealth = async (row) => {
  selectedDevice.value = row.deviceId
  syncOpsAssistantFocus({
    deviceId: row.deviceId,
    title: row.deviceName || `设备 #${row.deviceId}`,
    deviceName: row.deviceName || row.ipAddress || '',
    source: 'config',
    scenario: 'CONFIG',
    scenarioLabel: '配置'
  })
  setPageContext({
    page: 'configs',
    deviceId: row.deviceId,
    title: row.deviceName || `设备 #${row.deviceId}`,
    deviceName: row.deviceName || row.ipAddress || '',
    scenario: 'CONFIG'
  })
  await handleDeviceChange(row.deviceId)
  loadCompliance()
  loadRecentChanges()
}

const askConfigRisk = () => {
  const id = selectedDevice.value
  if (!id) {
    ElMessage.warning('请先选择设备')
    return
  }
  const row = selectedHealthRow.value
  const name = row?.deviceName || selectedDeviceName.value || `设备 #${id}`
  askOpsAssistant({
    deviceId: id,
    title: name,
    deviceName: name,
    source: 'config',
    scenario: 'CONFIG',
    scenarioLabel: '配置',
    primaryToolLabel: '配置差异摘要',
    recommendedTools: [
      { name: 'get_config_diff_summary', label: '配置差异摘要', needConfirm: false, args: { deviceId: id } },
      { name: 'pull_live_config', label: '拉取 running', needConfirm: false, args: { deviceId: id, configType: 'running' } },
      { name: 'backup', label: '先备份', needConfirm: true, args: { deviceId: id } }
    ],
    expand: false,
    autoAsk: true,
    autoAskQuestion: `评估配置风险（备份与 running 差异）：${name}`
  })
}

function openOpsAssist() {
  const id = selectedDevice.value
  if (id) {
    const row = selectedHealthRow.value
    const name = row?.deviceName || selectedDeviceName.value || `设备 #${id}`
    syncOpsAssistantFocus({
      deviceId: id,
      title: name,
      deviceName: name,
      source: 'config',
      scenario: 'CONFIG',
      scenarioLabel: '配置'
    })
  }
  requestOpenOpsInline('configs')
}

const askConfigSuggest = () => {
  const id = selectedDevice.value
  if (!id) {
    ElMessage.warning('请先选择设备')
    return
  }
  const row = selectedHealthRow.value
  const name = row?.deviceName || selectedDeviceName.value || `设备 #${id}`
  askOpsAssistant({
    deviceId: id,
    title: name,
    deviceName: name,
    source: 'config',
    scenario: 'CONFIG',
    scenarioLabel: '配置建议',
    primaryToolLabel: '配置命令建议',
    recommendedTools: [
      { name: 'suggest_config_commands', label: '配置命令建议', needConfirm: false, args: { deviceId: id, intent: 'general' } },
      { name: 'pull_live_config', label: '拉取 running', needConfirm: false, args: { deviceId: id, configType: 'running' } },
      { name: 'get_config_diff_summary', label: '配置差异', needConfirm: false, args: { deviceId: id } }
    ],
    expand: false,
    autoAsk: true,
    autoAskQuestion: `配置命令建议：${name}`
  })
}

const handleDeviceChange = async (deviceId) => {
  if (!deviceId) {
    configs.value = []
    configTotal.value = 0
    return
  }
  configPage.value = 1
  await loadConfigs()
}

const onConfigPageSizeChange = () => {
  configPage.value = 1
  loadConfigs()
}

const loadConfigs = async () => {
  if (!selectedDevice.value) {
    configs.value = []
    configTotal.value = 0
    return
  }
  loading.value = true
  try {
    const res = await configApi.getConfigsByDeviceIdPage(
      selectedDevice.value,
      configPage.value - 1,
      configPageSize.value
    )
    configs.value = Array.isArray(res?.content) ? res.content : (Array.isArray(res) ? res : [])
    configTotal.value = res?.totalElements != null ? Number(res.totalElements) : configs.value.length
  } catch (error) {
    ElMessage.error('加载配置列表失败')
  } finally {
    loading.value = false
  }
}

/** 对比下拉用全量摘要（不含正文），绑定对比页所选设备 */
const loadCompareVersions = async () => {
  if (!compareDeviceId.value) {
    compareVersions.value = []
    return
  }
  try {
    const res = await configApi.getConfigsByDeviceId(compareDeviceId.value)
    compareVersions.value = Array.isArray(res) ? res : []
  } catch {
    compareVersions.value = []
  }
}

const onCompareDeviceChange = async () => {
  compareSource.value = null
  compareTarget.value = null
  compareResult.value = null
  compareLeftText.value = ''
  compareRightText.value = ''
  await loadCompareVersions()
}

const formatCompareOption = (config) => {
  if (!config) return ''
  const type = config.configType === 'startup' ? '启动' : '运行'
  const time = config.createdAt ? formatDate(config.createdAt) : (config.configVersion || '')
  const desc = (config.description || '').trim()
  const shortDesc = desc.length > 24 ? desc.slice(0, 24) + '…' : desc
  return shortDesc
    ? `${time} · ${type} · ${shortDesc}`
    : `${time} · ${type} · ${config.configVersion || '#' + config.id}`
}

const handleSelectionChange = (selection) => {
  selectedConfigs.value = selection
}

const clearConfigSelection = () => {
  selectedConfigs.value = []
  configTableRef.value?.clearSelection?.()
}

const batchExportConfigs = async () => {
  const rows = selectedConfigs.value || []
  if (!rows.length) {
    ElMessage.warning('请选择要导出的备份')
    return
  }
  batchExportLoading.value = true
  try {
    const ids = rows.map(r => r.id)
    const raw = await configApi.batchExportConfigs(ids)
    const file = await ensureBlobFile(raw, '批量导出')
    const blob = file instanceof Blob
      ? (file.type.includes('zip') ? file : new Blob([await file.arrayBuffer()], { type: 'application/zip' }))
      : new Blob([file], { type: 'application/zip' })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14)
    const deviceName = (selectedHealthRow.value?.deviceName || 'device').replace(/[\\/:*?"<>|]/g, '_')
    link.setAttribute('download', `${deviceName}_configs_${stamp}.zip`)
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
    ElMessage.success(`已导出 ${ids.length} 个备份`)
    clearConfigSelection()
  } catch (error) {
    ElMessage.error('批量导出失败: ' + (error.message || ''))
  } finally {
    batchExportLoading.value = false
  }
}

const batchDeleteConfigs = async () => {
  const rows = selectedConfigs.value || []
  if (!rows.length) {
    ElMessage.warning('请选择要删除的备份')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${rows.length} 个备份？此操作不可恢复。`,
      '批量删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  batchDeleteLoading.value = true
  try {
    const ids = rows.map(r => r.id)
    const res = await configApi.batchDeleteConfigs(ids, selectedDevice.value)
    const deleted = res?.deletedCount ?? 0
    const fail = res?.failCount ?? 0
    if (fail > 0) {
      ElMessage.warning(`已删除 ${deleted} 个，失败 ${fail} 个`)
    } else {
      ElMessage.success(`已删除 ${deleted} 个备份`)
    }
    clearConfigSelection()
    await loadConfigs()
    loadBackupHealth()
  } catch (error) {
    ElMessage.error('批量删除失败: ' + (error.response?.data?.message || error.message))
  } finally {
    batchDeleteLoading.value = false
  }
}

const showBackupDialog = () => {
  backupForm.value = { configType: 'running', description: '' }
  backupDialogVisible.value = true
}

const batchBackupDeviceOptions = computed(() => {
  const rows = healthOverview.value?.devices || []
  if (!batchBackupForm.value.onlineOnly) return rows
  return rows.filter(r => r.status === 'online')
})

const showBatchBackupDialog = () => {
  batchBackupForm.value = { configType: 'running', description: '', onlineOnly: true }
  batchBackupSelectedIds.value = []
  // 预勾选：当前健康筛选下的设备，或当前选中设备
  const filtered = filteredHealthDevices.value || []
  if (filtered.length) {
    const opts = batchBackupForm.value.onlineOnly
      ? filtered.filter(r => r.status === 'online')
      : filtered
    batchBackupSelectedIds.value = opts.map(r => r.deviceId)
  } else if (selectedDevice.value) {
    batchBackupSelectedIds.value = [selectedDevice.value]
  }
  batchBackupDialogVisible.value = true
}

const selectBatchBackupFiltered = () => {
  batchBackupSelectedIds.value = batchBackupDeviceOptions.value.map(r => r.deviceId)
}

const handleBatchBackup = async () => {
  const ids = batchBackupSelectedIds.value || []
  if (!ids.length) {
    ElMessage.warning('请选择设备')
    return
  }
  batchBackupLoading.value = true
  batchBackupDialogVisible.value = false
  batchResultMode.value = 'backup'
  batchResultTitle.value = '批量备份结果'
  batchResultVisible.value = true
  batchLoading.value = true
  batchProgress.value = 0
  batchProgressText.value = '提交任务...'
  batchResults.value = []
  try {
    const started = await configApi.startBatchBackupTask(ids, {
      configType: batchBackupForm.value.configType,
      description: batchBackupForm.value.description
    })
    const taskId = started.taskId || started.task?.id
    if (!taskId) throw new Error('未返回任务 ID')
    const task = await pollConfigTask(taskId, (t) => {
      batchProgress.value = Number(t.progress || 0)
      batchProgressText.value = t.message || '备份中...'
    })
    const res = task.result || {}
    batchResults.value = (res.results || []).map(r => ({
      ...r,
      message: r.message || (r.success ? `成功 ${r.configVersion || ''}` : (r.message || '失败'))
    }))
    batchProgress.value = 100
    batchProgressText.value = task.message || '备份完成'
    if (task.status === 'FAILED') {
      ElMessage.error(task.error || task.message || '批量备份失败')
    } else if (task.status === 'PARTIAL') {
      ElMessage.warning(`部分成功：${res.successCount}/${res.totalCount}`)
    } else {
      ElMessage.success(`批量备份完成：${res.successCount}/${res.totalCount}`)
    }
    loadBackupHealth()
    loadConfigTasks()
    if (selectedDevice.value) await loadConfigs()
  } catch (error) {
    batchProgressText.value = '备份失败'
    ElMessage.error('批量备份失败: ' + (error.response?.data?.message || error.message))
  } finally {
    batchLoading.value = false
    batchBackupLoading.value = false
  }
}

const handleBackup = async () => {
  if (!selectedDevice.value) {
    ElMessage.warning('请先选择设备')
    return
  }
  
  backupLoading.value = true
  try {
    await configApi.backupConfig({
      deviceId: selectedDevice.value,
      configType: backupForm.value.configType,
      description: backupForm.value.description
    })
    ElMessage.success('备份成功')
    backupDialogVisible.value = false
    await loadConfigs()
    loadBackupHealth()
  } catch (error) {
    ElMessage.error('备份失败: ' + (error.response?.data?.message || error.message))
  } finally {
    backupLoading.value = false
  }
}

const viewConfig = async (config) => {
  try {
    const full = await configApi.getConfig(config.id)
    currentConfig.value = full || config
    viewDialogVisible.value = true
  } catch (e) {
    ElMessage.error('加载配置内容失败')
  }
}

const restoreConfig = async (config) => {
  if (!selectedDevice.value) {
    ElMessage.warning('请先选择设备')
    return
  }
  
  try {
    await ElMessageBox.confirm(
      `确定恢复配置版本 ${config.configVersion}（${config.configType === 'startup' ? '启动' : '运行'}备份）？\n\n` +
      `将先自动备份当前运行配置（生成预备份版本），再将目标备份下发到运行配置并执行 save。\n` +
      `完成后可在备份列表与变更记录中对照「预备份 → 恢复版本」。`,
      '安全恢复确认',
      {
        type: 'warning',
        confirmButtonText: '预备份并恢复',
        cancelButtonText: '取消',
        distinguishCancelAndClose: true
      }
    )

    restoreDialogVisible.value = true
    restoreProgress.value = 0
    restoreProgressText.value = '提交任务...'
    restoreError.value = false
    restoreErrorMessage.value = ''
    restoreSuccess.value = false
    showRestoreLog.value = false
    restoreSuccessCount.value = 0
    restoreFailCount.value = 0

    const started = await configApi.startRestoreTask(selectedDevice.value, config.id, true)
    const taskId = started.taskId || started.task?.id
    if (!taskId) throw new Error('未返回任务 ID')

    const task = await pollConfigTask(taskId, (t) => {
      restoreProgress.value = Number(t.progress || 0)
      restoreProgressText.value = t.message || '执行中...'
    })

    const res = task.result || {}
    if (task.status === 'FAILED' && !res.result) {
      throw new Error(task.error || task.message || '恢复失败')
    }

    if (res.successCount != null) restoreSuccessCount.value = Number(res.successCount)
    if (res.failCount != null) restoreFailCount.value = Number(res.failCount)
    if (restoreSuccessCount.value === 0 && restoreFailCount.value === 0 && res.result) {
      const match = String(res.result).match(/成功[:：]\s*(\d+).*?失败[:：]\s*(\d+)/)
      if (match) {
        restoreSuccessCount.value = parseInt(match[1])
        restoreFailCount.value = parseInt(match[2])
      }
    }
    restoreLogContent.value = res.result || task.message || '恢复完成'
    restoreProgress.value = 100
    const failN = Number(res.failCount != null ? res.failCount : restoreFailCount.value) || 0
    const okN = Number(res.successCount != null ? res.successCount : restoreSuccessCount.value) || 0
    restoreSuccessCount.value = okN
    restoreFailCount.value = failN

    const allOk = (task.status === 'SUCCESS' || !!res.success) && failN === 0
    restoreSuccess.value = allOk
    restoreError.value = !allOk
    if (allOk) {
      const pre = res.preBackupVersion ? `预备份 ${res.preBackupVersion}` : ''
      const restored = res.restoredVersion ? `恢复 ${res.restoredVersion}` : ''
      restoreProgressText.value = ['恢复完成', pre, restored].filter(Boolean).join(' · ')
    } else if (okN > 0 && failN > 0) {
      restoreProgressText.value = `恢复部分成功：成功 ${okN}，失败 ${failN}`
      restoreErrorMessage.value = `有 ${failN} 条命令失败（常见原因：视图层级或设备已存在相同配置）。请点「查看详情」核对 ✗ 行。`
    } else {
      restoreProgressText.value = task.message || '恢复失败'
      restoreErrorMessage.value = task.error || res.message || '恢复失败，请查看详情'
    }
    loadConfigTasks()
    loadBackupHealth()
    await loadConfigs()
  } catch (error) {
    if (error === 'cancel') {
      return
    }

    restoreError.value = true
    restoreErrorMessage.value = error.response?.data?.message || error.message || '恢复过程中发生错误'
    restoreProgress.value = 0

    const errorMsg = error.response?.data?.message || error.message || '未知错误'
    if (errorMsg.includes('timeout') || errorMsg.includes('超时')) {
      restoreErrorMessage.value = 'SSH连接超时，请检查设备是否可达或SSH服务是否启动'
    } else if (errorMsg.includes('credential') || errorMsg.includes('认证')) {
      restoreErrorMessage.value = 'SSH认证失败，请检查设备用户名和密码是否正确'
    } else if (errorMsg.includes('not found') || errorMsg.includes('不存在')) {
      restoreErrorMessage.value = '设备未找到，请检查设备配置'
    }
  }
}

const showRestoreDetail = () => {
  showRestoreLog.value = !showRestoreLog.value
}

const handleDelete = (config) => {
  ElMessageBox.confirm('确定要删除该配置吗？', '提示', { type: 'warning' })
    .then(async () => {
      try {
        await configApi.deleteConfig(config.id)
        ElMessage.success('删除成功')
        await loadConfigs()
      } catch (error) {
        ElMessage.error('删除失败')
      }
    }).catch(() => {})
}

const testConnection = async () => {
  testingConnection.value = true
  try {
    const res = await configApi.testSshConnection(selectedDevice.value)
    const success = res?.success ?? (res.data && res.data.success)
    if (success) {
      ElMessage.success('SSH 连接成功')
    } else {
      ElMessage.error('SSH 连接失败')
    }
  } catch (error) {
    ElMessage.error('连接测试失败')
  } finally {
    testingConnection.value = false
  }
}

const showCompareDialog = async () => {
  if (selectedConfigs.value.length < 1) {
    ElMessage.warning('请至少选择一个备份版本')
    return
  }
  if (!selectedDevice.value) {
    ElMessage.warning('请先选择设备')
    return
  }
  // 从备份页带入设备与版本，对比页仍可自行改选
  compareDeviceId.value = selectedDevice.value
  await loadCompareVersions()
  compareMode.value = selectedConfigs.value.length >= 2 ? 'backup' : 'live-running'
  if (selectedConfigs.value.length >= 2) {
    compareSource.value = selectedConfigs.value[0].id
    compareTarget.value = selectedConfigs.value[1].id
  } else {
    compareSource.value = null
    compareTarget.value = selectedConfigs.value[0].id
  }
  activeTab.value = 'compare'
  doCompare()
}

const onCompareModeChange = () => {
  compareResult.value = null
  compareLeftText.value = ''
  compareRightText.value = ''
  if (compareMode.value !== 'backup') {
    compareSource.value = null
  }
}

const recomputeCompareDiff = () => {
  if (!compareLeftText.value && !compareRightText.value) return
  compareResult.value = diffLines(compareLeftText.value, compareRightText.value, {
    ignoreNoise: compareIgnoreNoise.value
  })
}

const onCompareNoiseChange = () => {
  if (compareLeftText.value || compareRightText.value) {
    recomputeCompareDiff()
  }
}

const onCompareScrollLeft = () => {
  if (compareScrollLock || !compareLeftPane.value || !compareRightPane.value) return
  compareScrollLock = true
  compareRightPane.value.scrollTop = compareLeftPane.value.scrollTop
  compareRightPane.value.scrollLeft = compareLeftPane.value.scrollLeft
  requestAnimationFrame(() => { compareScrollLock = false })
}

const onCompareScrollRight = () => {
  if (compareScrollLock || !compareLeftPane.value || !compareRightPane.value) return
  compareScrollLock = true
  compareLeftPane.value.scrollTop = compareRightPane.value.scrollTop
  compareLeftPane.value.scrollLeft = compareRightPane.value.scrollLeft
  requestAnimationFrame(() => { compareScrollLock = false })
}

const doCompare = async () => {
  if (!compareDeviceId.value) {
    ElMessage.warning('请先选择设备')
    return
  }
  if (!canStartCompare.value) return
  compareLoading.value = true
  try {
    let leftText = ''
    let rightText = ''
    if (compareMode.value === 'backup') {
      const [sourceConfig, targetConfig] = await Promise.all([
        configApi.getConfig(compareSource.value),
        configApi.getConfig(compareTarget.value)
      ])
      leftText = sourceConfig?.content || ''
      rightText = targetConfig?.content || ''
    } else {
      const type = compareMode.value === 'live-startup' ? 'startup' : 'running'
      const [live, targetConfig] = await Promise.all([
        configApi.pullLiveConfig(compareDeviceId.value, type),
        configApi.getConfig(compareTarget.value)
      ])
      if (!live?.success && live?.message) {
        throw new Error(live.message)
      }
      leftText = live?.content || ''
      rightText = targetConfig?.content || ''
    }
    compareLeftText.value = leftText
    compareRightText.value = rightText
    compareResult.value = diffLines(leftText, rightText, { ignoreNoise: compareIgnoreNoise.value })
    if (compareResult.value.added === 0 && compareResult.value.removed === 0) {
      ElMessage.success('两侧配置一致')
    }
  } catch (e) {
    ElMessage.error('对比失败: ' + (e.response?.data?.message || e.message || '无法加载配置'))
  } finally {
    compareLoading.value = false
  }
}

const exportCompareDiff = () => {
  if (!compareResult.value) return
  const left = compareLeftTitle.value.replace(/[\\/:*?"<>|]/g, '_')
  const right = compareRightTitle.value.replace(/[\\/:*?"<>|]/g, '_')
  const text = toUnifiedDiff(compareResult.value, left, right)
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `config-diff_${Date.now()}.diff`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
  ElMessage.success('已导出 Diff')
}

const loadTemplates = async () => {
  templateLoading.value = true
  try {
    const res = await configTemplateApi.getTemplates()
    templates.value = Array.isArray(res) ? res : (res.data || [])
  } catch (error) {
    ElMessage.error('加载模板列表失败')
  } finally {
    templateLoading.value = false
  }
}

const showTemplateDialog = (template) => {
  editTemplate.value = template
  if (template) {
    templateForm.value = { ...template }
  } else {
    templateForm.value = { name: '', category: '', deviceType: '', content: '', description: '' }
  }
  templateDialogVisible.value = true
}

const handleTemplateSubmit = async () => {
  if (!templateForm.value.name || !templateForm.value.content) {
    ElMessage.warning('请填写模板名称和配置内容')
    return
  }
  templateSubmitLoading.value = true
  try {
    const data = { ...templateForm.value, createdBy: currentOperator() }
    if (editTemplate.value) {
      await configTemplateApi.updateTemplate(editTemplate.value.id, data)
      ElMessage.success('模板更新成功')
    } else {
      await configTemplateApi.createTemplate(data)
      ElMessage.success('模板创建成功')
    }
    templateDialogVisible.value = false
    loadTemplates()
  } catch (error) {
    ElMessage.error('操作失败')
  } finally {
    templateSubmitLoading.value = false
  }
}

const applyTemplate = (template) => {
  editTemplate.value = template
  const vars = []
  const re = /\$\{([a-zA-Z0-9_]+)\}/g
  let m
  while ((m = re.exec(template?.content || '')) !== null) {
    vars.push(m[1])
  }
  applyForm.value = {
    deviceIds: selectedDevice.value ? [selectedDevice.value] : [],
    enableVariables: vars.length > 0,
    reason: ''
  }
  applyDialogVisible.value = true
}

const bridgeTemplateToBatch = async () => {
  if (!editTemplate.value?.content) {
    ElMessage.warning('模板内容为空')
    return
  }
  batchForm.value.configType = 'custom'
  batchForm.value.params.customContent = editTemplate.value.content
  batchEnableVariables.value = !!applyForm.value.enableVariables || hasBatchVar(editTemplate.value.content)
  batchTemplateId.value = editTemplate.value.id || null
  const ids = applyForm.value.deviceIds || []
  batchSelectedDevices.value = [...ids]
  batchSelectedDevicesList.value = devices.value.filter(d => ids.includes(d.id))
  applyDialogVisible.value = false
  activeTab.value = 'batch'
  batchStep.value = ids.length ? 2 : 0
  await nextTick()
  await syncBatchTableSelection()
  ElMessage.success(ids.length ? '已填入批量下发，请确认预览后下发' : '已填入配置内容，请先选择设备')
}

const syncBatchTableSelection = async () => {
  if (batchStep.value !== 0 || !batchDeviceTable.value) return
  await nextTick()
  batchDeviceTable.value.clearSelection?.()
  const rows = filteredDevices.value.filter(d => batchSelectedDevices.value.includes(d.id))
  rows.forEach(row => batchDeviceTable.value.toggleRowSelection(row, true))
}

const handleApplyTemplate = async () => {
  const deviceIds = applyForm.value.deviceIds || []
  if (!deviceIds.length) {
    ElMessage.warning('请选择至少一台设备')
    return
  }
  const content = editTemplate.value?.content || ''
  if (!content.trim()) {
    ElMessage.warning('模板内容为空')
    return
  }
  applyLoading.value = true
  try {
    const reason = applyForm.value.reason?.trim() || `应用模板：${editTemplate.value?.name || '未命名'}`
    ElMessage.info('已提交异步任务...')
    const started = await configApi.startBatchApplyTask(deviceIds, {
      content,
      enableVariables: !!applyForm.value.enableVariables,
      parallel: false,
      reason
    })
    if (started?.needsApproval) {
      ElMessage.warning(started.message || `已创建审批单 #${started.requestId}，请先批准`)
      applyDialogVisible.value = false
      return
    }
    const taskId = started.taskId || started.task?.id
    if (!taskId) throw new Error('未返回任务 ID')
    applyDialogVisible.value = false
    activeTab.value = 'tasks'
    await loadConfigTasks()
    const task = await pollConfigTask(taskId, () => loadConfigTasks())
    if (task.status === 'FAILED') {
      ElMessage.error(task.error || task.message || '模板下发失败')
    } else {
      const res = task.result || {}
      ElMessage.success(`模板下发完成，成功 ${res.successCount ?? '-'}/${res.totalCount ?? deviceIds.length}`)
    }
    loadConfigTasks()
  } catch (error) {
    ElMessage.error('应用失败: ' + (error.response?.data?.message || error.message))
  } finally {
    applyLoading.value = false
  }
}

const deleteTemplate = (template) => {
  ElMessageBox.confirm(`确定要删除模板 "${template.name}" 吗？`, '提示', { type: 'warning' })
    .then(async () => {
      try {
        await configTemplateApi.deleteTemplate(template.id)
        ElMessage.success('删除成功')
        loadTemplates()
      } catch (error) {
        ElMessage.error('删除失败')
      }
    }).catch(() => {})
}

const loadSchedules = async () => {
  scheduleLoading.value = true
  try {
    const res = await backupScheduleApi.getAllSchedules()
    schedules.value = Array.isArray(res) ? res : (res.data || [])
  } catch (error) {
    ElMessage.error('加载定时任务失败')
  } finally {
    scheduleLoading.value = false
  }
}

const showScheduleDialog = () => {
  const device = devices.value.find(d => d.id === selectedDevice.value)
  scheduleScope.value = 'single'
  scheduleDeviceIds.value = []
  scheduleGroupId.value = null
  scheduleForm.value = {
    deviceId: selectedDevice.value,
    deviceName: device ? device.name : '',
    scheduleType: 'daily',
    scheduleTime: '02:00',
    dayOfWeek: 1,
    dayOfMonth: 1,
    configType: 'running',
    isActive: true
  }
  editSchedule.value = null
  scheduleDialogVisible.value = true
}

const showScheduleCreateDialog = () => {
  scheduleScope.value = 'single'
  scheduleDeviceIds.value = []
  scheduleGroupId.value = null
  scheduleForm.value = {
    deviceId: null,
    deviceName: '',
    scheduleType: 'daily',
    scheduleTime: '02:00',
    dayOfWeek: 1,
    dayOfMonth: 1,
    configType: 'running',
    isActive: true
  }
  editSchedule.value = null
  loadDeviceGroups()
  scheduleDialogVisible.value = true
}

const handleScheduleDeviceChange = (deviceId) => {
  const device = devices.value.find(d => d.id === deviceId)
  scheduleForm.value.deviceName = device ? device.name : ''
}

const openEditSchedule = (schedule) => {
  editSchedule.value = schedule
  scheduleScope.value = 'single'
  scheduleForm.value = {
    ...schedule,
    dayOfWeek: schedule.dayOfWeek || 1,
    dayOfMonth: schedule.dayOfMonth || 1
  }
  scheduleDialogVisible.value = true
}

const saveSchedule = async () => {
  try {
    if (editSchedule.value) {
      if (!scheduleForm.value.deviceId) {
        ElMessage.warning('请选择设备')
        return
      }
      await backupScheduleApi.updateSchedule(editSchedule.value.id, scheduleForm.value)
      ElMessage.success('更新成功')
      scheduleDialogVisible.value = false
      loadSchedules()
      loadBackupHealth()
      return
    } else if (scheduleScope.value === 'single') {
      if (!scheduleForm.value.deviceId) {
        ElMessage.warning('请选择设备')
        return
      }
      await backupScheduleApi.createSchedule(scheduleForm.value)
      ElMessage.success('创建成功')
      scheduleDialogVisible.value = false
      loadSchedules()
      loadBackupHealth()
      return
    } else if (scheduleScope.value === 'multi') {
      if (!scheduleDeviceIds.value.length) {
        ElMessage.warning('请选择至少一台设备')
        return
      }
      const res = await backupScheduleApi.createPolicy({
        deviceIds: scheduleDeviceIds.value,
        scheduleType: scheduleForm.value.scheduleType,
        scheduleTime: scheduleForm.value.scheduleTime,
        dayOfWeek: scheduleForm.value.dayOfWeek,
        dayOfMonth: scheduleForm.value.dayOfMonth,
        configType: scheduleForm.value.configType,
        isActive: scheduleForm.value.isActive
      })
      const created = res.createdCount || 0
      const updated = res.updatedCount || 0
      ElMessage.success(`策略已应用：新建 ${created}，更新 ${updated}`)
    } else {
      if (!scheduleGroupId.value) {
        ElMessage.warning('请选择设备分组')
        return
      }
      const res = await backupScheduleApi.createPolicy({
        groupId: scheduleGroupId.value,
        scheduleType: scheduleForm.value.scheduleType,
        scheduleTime: scheduleForm.value.scheduleTime,
        dayOfWeek: scheduleForm.value.dayOfWeek,
        dayOfMonth: scheduleForm.value.dayOfMonth,
        configType: scheduleForm.value.configType,
        isActive: scheduleForm.value.isActive
      })
      const created = res.createdCount || 0
      const updated = res.updatedCount || 0
      ElMessage.success(
        `已按当前成员展开：新建 ${created}，更新 ${updated}。成员变化后请「同步分组计划」。`
      )
    }
    scheduleDialogVisible.value = false
    loadSchedules()
    loadBackupHealth()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '操作失败')
  }
}

const deleteSchedule = (schedule) => {
  ElMessageBox.confirm('确定要删除该定时任务吗？', '提示', { type: 'warning' })
    .then(async () => {
      try {
        await backupScheduleApi.deleteSchedule(schedule.id)
        ElMessage.success('删除成功')
        loadSchedules()
      } catch (error) {
        ElMessage.error('删除失败')
      }
    }).catch(() => {})
}

const executeSchedule = async (schedule) => {
  try {
    const res = await backupScheduleApi.executeSchedule(schedule.id)
    if (res?.success === false || res?.ok === false) {
      ElMessage.error(res?.message || '执行失败')
    } else {
      ElMessage.success(res?.message || '执行成功')
    }
    loadSchedules()
    loadBackupHealth()
  } catch (error) {
    const msg = error.response?.data?.message || error.message || '执行失败'
    ElMessage.error(msg)
    loadSchedules()
    loadBackupHealth()
  }
}

const exportConfig = async (config) => {
  try {
    const raw = await configApi.exportConfig(config.id)
    const blob = await ensureBlobFile(raw, '配置导出')
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.setAttribute('download', `config_${config.configVersion}_${config.configType}.txt`)
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
  } catch (error) {
    ElMessage.error('导出失败')
  }
}

const formatTaskType = (type) => {
  const map = { restore: '恢复', batch: '批量下发', backup: '批量备份' }
  return map[type] || type || '-'
}

const taskStatusTag = (status) => {
  const map = {
    SUCCESS: 'success',
    PARTIAL: 'warning',
    FAILED: 'danger',
    CANCELLED: 'info',
    PAUSED: 'warning',
    RUNNING: '',
    PENDING: 'info'
  }
  return map[status] || 'info'
}

const complianceLevelLabel = (level) => {
  const map = {
    compliant: '合规',
    mostly: '基本合规',
    drift: '有偏差',
    non_compliant: '不合规',
    unknown: '未知'
  }
  return map[level] || level || '-'
}

const formatChangeType = (type) => {
  const map = {
    backup: '备份',
    restore: '恢复',
    batch: '批量下发',
    apply: '应用模板',
    schedule: '定时备份'
  }
  return map[type] || type || '-'
}

const loadCompliance = async () => {
  if (!selectedDevice.value) {
    compliance.value = null
    return
  }
  complianceLoading.value = true
  try {
    compliance.value = await configApi.getCompliance(selectedDevice.value)
  } catch {
    compliance.value = null
  } finally {
    complianceLoading.value = false
  }
}

const loadRecentChanges = async () => {
  if (!selectedDevice.value) {
    recentChanges.value = []
    return
  }
  recentChangesLoading.value = true
  try {
    const res = await configChangeLogApi.queryLogs({
      deviceId: selectedDevice.value,
      page: 0,
      size: 8
    })
    recentChanges.value = res?.content || (Array.isArray(res) ? res : [])
  } catch {
    recentChanges.value = []
  } finally {
    recentChangesLoading.value = false
  }
}

const formatTaskResult = (result) => {
  if (!result) return '-'
  try {
    return JSON.stringify(result, null, 2)
  } catch {
    return String(result)
  }
}

const loadConfigTasks = async () => {
  taskListLoading.value = true
  try {
    const res = await configApi.listConfigTasks()
    configTasks.value = Array.isArray(res) ? res : []
  } catch (error) {
    configTasks.value = []
  } finally {
    taskListLoading.value = false
  }
}

const viewTaskDetail = async (row) => {
  try {
    const fresh = await configApi.getConfigTask(row.id)
    currentTask.value = fresh || row
  } catch {
    currentTask.value = row
  }
  taskDetailVisible.value = true
}

const cancelTask = async (row) => {
  try {
    await ElMessageBox.confirm(`确定取消任务「${row.label || row.id}」？`, '取消任务', { type: 'warning' })
    await configApi.cancelConfigTask(row.id)
    ElMessage.success('已提交取消')
    loadConfigTasks()
  } catch (e) {
    if (e === 'cancel') return
    ElMessage.error(e?.response?.data?.message || e.message || '取消失败')
  }
}

const continuePausedTask = async (row) => {
  try {
    const { value } = await ElMessageBox.prompt(
      `待继续约 ${row.pendingCount ?? (row.pendingDeviceIds?.length || '?')} 台。下一波数量（留空=剩余全部）：`,
      '继续分波下发',
      {
        inputPattern: /^\d*$/,
        inputErrorMessage: '请输入数字',
        confirmButtonText: '继续',
        cancelButtonText: '取消'
      }
    )
    const wave = value ? Number(value) : undefined
    const res = await configApi.continueConfigTask(row.id, wave)
    const taskId = res.task?.id || row.id
    ElMessage.success('已继续下发')
    activeTab.value = 'tasks'
    await pollConfigTask(taskId, () => loadConfigTasks())
    loadConfigTasks()
  } catch (e) {
    if (e === 'cancel') return
    ElMessage.error(e?.response?.data?.message || e.message || '继续失败')
  }
}

const retryTaskFailed = async (row) => {
  try {
    const res = await configApi.retryFailedConfigTask(row.id)
    const taskId = res.taskId || res.task?.id
    ElMessage.success('已提交失败重试')
    if (taskId) {
      await pollConfigTask(taskId, () => loadConfigTasks())
    }
    loadConfigTasks()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e.message || '重试失败')
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

const goDeviceGroups = async () => {
  try {
    await ElMessageBox.confirm(
      '将打开设备管理页以维护分组与成员。当前配置页未保存的筛选不会保留，是否继续？',
      '打开设备管理',
      { type: 'info', confirmButtonText: '前往', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  router.push('/devices')
}

const goConfigAudit = async () => {
  try {
    await ElMessageBox.confirm(
      '将打开日志中心查看配置变更记录，是否继续？',
      '打开日志中心',
      { type: 'info', confirmButtonText: '前往', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  router.push({ path: '/audit', query: { tab: 'history' } })
}

const goDeviceAudit = async (deviceId) => {
  if (!deviceId) return
  try {
    await ElMessageBox.confirm(
      '将打开日志中心查看该设备的配置变更，是否继续？',
      '打开日志中心',
      { type: 'info', confirmButtonText: '前往', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  router.push({ path: '/audit', query: { tab: 'history', deviceId: String(deviceId) } })
}

const showSyncGroupDialog = async () => {
  await loadDeviceGroups()
  syncGroupForm.value = {
    groupId: scheduleGroupId.value || null,
    scheduleType: 'daily',
    scheduleTime: '02:00',
    dayOfWeek: 1,
    dayOfMonth: 1,
    configType: 'running',
    removeOrphans: true
  }
  syncGroupDialogVisible.value = true
}

const submitSyncGroupPolicy = async () => {
  if (!syncGroupForm.value.groupId) {
    ElMessage.warning('请选择分组')
    return
  }
  syncGroupLoading.value = true
  try {
    const res = await backupScheduleApi.syncGroupPolicy({
      groupId: syncGroupForm.value.groupId,
      scheduleType: syncGroupForm.value.scheduleType,
      scheduleTime: syncGroupForm.value.scheduleTime,
      dayOfWeek: syncGroupForm.value.dayOfWeek,
      dayOfMonth: syncGroupForm.value.dayOfMonth,
      configType: syncGroupForm.value.configType,
      removeOrphans: syncGroupForm.value.removeOrphans
    })
    ElMessage.success(
      `同步完成：新建 ${res.createdCount || 0}，更新 ${res.updatedCount || 0}` +
      (syncGroupForm.value.removeOrphans ? `，清理 ${res.removedOrphanCount || 0}` : '')
    )
    syncGroupDialogVisible.value = false
    loadSchedules()
    loadBackupHealth()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '同步失败')
  } finally {
    syncGroupLoading.value = false
  }
}

const stopTaskPoll = () => {
  if (taskPollTimer) {
    clearInterval(taskPollTimer)
    taskPollTimer = null
  }
}

watch(activeTab, (tab) => {
  if (tab === 'tasks') {
    loadConfigTasks()
    stopTaskPoll()
    taskPollTimer = setInterval(() => {
      const hasRunning = configTasks.value.some(t => t.status === 'RUNNING' || t.status === 'PENDING')
      if (hasRunning) loadConfigTasks()
    }, 2000)
  } else {
    stopTaskPoll()
  }
  if (tab === 'batch') {
    loadDeviceGroups()
  }
  if (tab === 'compare') {
    // 若对比页尚未选设备，可顺带带入备份页已选设备（可选，非强制）
    if (!compareDeviceId.value && selectedDevice.value) {
      compareDeviceId.value = selectedDevice.value
    }
    loadCompareVersions()
  }
})

watch(batchStep, (step) => {
  if (step === 0) syncBatchTableSelection()
})

onMounted(() => {
  setPageContext({ page: 'configs' })
  loadDevices()
  loadBackupHealth()
  loadTemplates()
  loadSchedules()
  loadDeviceGroups()
  loadConfigTasks()
  // 旧链接 /configs?tab=history 跳转到审计中心
  if (route.query.tab === 'history' || route.query.changeLogId) {
    router.replace({
      path: '/audit',
      query: {
        tab: 'history',
        deviceId: route.query.deviceId,
        changeLogId: route.query.changeLogId
      }
    })
    return
  }
  // 旧链接 /configs?tab=webssh → 全局浮窗终端
  if (route.query.tab === 'webssh') {
    const deviceId = route.query.deviceId
    openWebTerminal({
      deviceId: deviceId != null && deviceId !== '' ? Number(deviceId) : null
    })
    const q = { ...route.query }
    delete q.tab
    router.replace({ path: '/configs', query: q })
  }
})

onUnmounted(() => {
  clearPageContext()
  stopTaskPoll()
})

/** 助手工具结果回写本页 */
watch(
  () => pageSink.token,
  async (token) => {
    if (!token || !pageSink.last) return
    const { result, tool, source } = pageSink.last
    if (source === 'config') return
    if (!result || result.ok === false) return
    const detail = result.detail || {}
    const name = tool || result.tool || ''
    if (detail.navigate && detail.path) {
      // 助手侧已处理显式导航；业务页只做同页聚焦，禁止自动跳走
      if (source === 'assistant') return
      const { offerToolNavigate } = await import('@/composables/useSafeNavigate')
      await offerToolNavigate(router, route, {
        toolName: name,
        detail,
        onSamePage: async () => {
          await loadBackupHealth()
          if (selectedDevice.value) await loadConfigs()
        }
      })
      return
    }
    if (['backup', 'pull_live_config', 'refresh_device', 'inspect', 'ping_check', 'probe_device'].includes(name)) {
      await loadBackupHealth()
      if (selectedDevice.value) await loadConfigs()
    }
  }
)
</script>

<style scoped>
.config-manage-container {
  padding: 20px;
  background: #f5f7fa;
  min-height: calc(100vh - 60px);
}

.header-card {
  margin-bottom: 20px;
}

.header-card .header-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.page-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.content-card {
  min-height: 600px;
}

.tab-content {
  padding: 10px 0;
}

.config-page-body {
  display: block;
}

.config-page-body.with-ops {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 14px;
  align-items: start;
}

.config-main {
  min-width: 0;
}

.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: flex-start;
  flex-wrap: wrap;
}

.toolbar.toolbar-spread {
  justify-content: space-between;
}

.toolbar-hint {
  color: #909399;
  font-size: 13px;
}

.schedule-alert {
  margin-bottom: 14px;
}

.schedule-alert :deep(.el-alert__title) {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0 2px;
  line-height: 1.5;
}

.form-hint {
  margin-top: 6px;
  color: #909399;
  font-size: 12px;
  line-height: 1.4;
}

.template-preview {
  margin: 0;
  max-height: 200px;
  overflow: auto;
  padding: 10px;
  background: #1e1e1e;
  color: #d4d4d4;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.5;
  width: 100%;
  box-sizing: border-box;
}

.toolbar-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.health-bar {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.health-card {
  min-width: 110px;
  padding: 10px 14px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.health-card:hover,
.health-card.active {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 1px rgba(64, 158, 255, 0.2);
}

.health-card.is-danger .health-num { color: #f56c6c; }
.health-card.is-warning .health-num { color: #e6a23c; }
.health-card.is-ok .health-num { color: #67c23a; }

.health-num {
  font-size: 22px;
  font-weight: 700;
  line-height: 1.2;
  color: #303133;
}

.health-label {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.backup-split {
  display: grid;
  grid-template-columns: minmax(220px, 260px) minmax(0, 1fr);
  gap: 14px;
  min-height: 420px;
  align-items: start;
}

.config-ops-side {
  /* page-level OpsInlineShell：各 Tab 共用，避免仅备份页可展开 */
}

.device-panel {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 10px;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.device-search {
  margin-bottom: 8px;
}

.device-list {
  overflow: auto;
  flex: 1;
  max-height: 560px;
}

.device-item {
  padding: 10px;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid transparent;
  margin-bottom: 6px;
}

.device-item:hover {
  background: #f5f7fa;
}

.device-item.active {
  background: #ecf5ff;
  border-color: #b3d8ff;
}

.device-item-main {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.device-name {
  font-weight: 600;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.device-item-sub {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.device-item-meta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #a8abb2;
  margin-top: 6px;
}

.version-panel {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px;
  min-width: 0;
}

.device-insight {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-top: 16px;
}

.compliance-card,
.change-timeline {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 10px 12px;
  background: #fafbfc;
  min-height: 120px;
}

.compliance-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-size: 13px;
}

.compliance-score {
  font-size: 28px;
  font-weight: 700;
  line-height: 1.2;
}

.compliance-score.lv-compliant { color: #67c23a; }
.compliance-score.lv-mostly { color: #409eff; }
.compliance-score.lv-drift { color: #e6a23c; }
.compliance-score.lv-non_compliant { color: #f56c6c; }
.compliance-score.lv-unknown { color: #909399; }

.compliance-level {
  margin-left: 8px;
  font-size: 13px;
  font-weight: 500;
}

.compliance-meta {
  font-size: 12px;
  color: #909399;
  margin: 4px 0 8px;
}

.rule-tag {
  margin: 0 6px 6px 0;
}

.change-list {
  max-height: 160px;
  overflow: auto;
}

.change-item {
  padding: 6px 0;
  border-bottom: 1px solid #ebeef5;
}

.change-item:last-child {
  border-bottom: none;
}

.change-main {
  display: flex;
  gap: 8px;
  align-items: center;
}

.change-reason {
  font-size: 13px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.change-meta {
  display: flex;
  gap: 10px;
  font-size: 12px;
  color: #a8abb2;
  margin-top: 4px;
}

.batch-precheck {
  margin-bottom: 14px;
}

@media (max-width: 1100px) {
  .device-insight {
    grid-template-columns: 1fr;
  }
}

.selected-device-title {
  display: flex;
  gap: 10px;
  align-items: baseline;
}

.muted {
  color: #909399;
  font-size: 13px;
}

.compare-side-by-side {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.compare-col-title {
  font-size: 12px;
  color: #606266;
  margin-bottom: 6px;
  font-weight: 600;
}

.compare-col .compare-content {
  max-height: 480px;
  overflow: auto;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  background: #fafafa;
}

.config-pager {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.config-batch-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  padding: 10px 12px;
  background: #f5f7fa;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  font-size: 13px;
  color: #606266;
}

@media (max-width: 1100px) {
  .config-page-body.with-ops {
    grid-template-columns: 1fr;
  }
  .backup-split {
    grid-template-columns: 1fr;
  }
  .device-list {
    max-height: 240px;
  }
  .compare-side-by-side {
    grid-template-columns: 1fr;
  }
}

.compare-toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.compare-device-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  min-height: 32px;
  flex-wrap: wrap;
}

.vs-text {
  font-weight: bold;
  color: var(--el-color-primary);
}

.compare-result {
  margin-top: 20px;
}

.compare-header {
  display: flex;
  gap: 20px;
  margin-bottom: 16px;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 4px;
}

.diff-count {
  font-weight: 600;
}

.diff-count.added {
  color: #67c23a;
}

.diff-count.removed {
  color: #f56c6c;
}

.diff-count.modified {
  color: #e6a23c;
}

.compare-content {
  max-height: 500px;
  overflow: auto;
  background: #1e1e1e;
  border-radius: 4px;
  padding: 16px;
}

.compare-content {
  max-height: 500px;
  overflow: auto;
  background: #1e1e1e;
  border-radius: 4px;
  padding: 16px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  line-height: 1.6;
}

.compare-content > div {
  display: flex;
  padding: 2px 0;
}

.compare-content .line-num {
  display: inline-block;
  width: 40px;
  text-align: right;
  padding-right: 12px;
  color: #858585;
  user-select: none;
}

.compare-content .line-content {
  flex: 1;
  white-space: pre-wrap;
  word-break: break-all;
}

.compare-content .added {
  background: rgba(63, 185, 80, 0.15);
}

.compare-content .added .line-content {
  color: #7ee787;
}

.compare-content .removed {
  background: rgba(248, 81, 73, 0.15);
}

.compare-content .removed .line-content {
  color: #ffa198;
}

.compare-content .unchanged {
  color: #d4d4d4;
}

.config-content {
  background: #f5f7fa;
  padding: 16px;
  border-radius: 4px;
  max-height: 500px;
  overflow: auto;
}

.config-content pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 13px;
  line-height: 1.6;
  color: #303133;
}

.restore-progress {
  padding: 20px;
}

.progress-text {
  margin-top: 16px;
  text-align: center;
  color: #606266;
  font-size: 14px;
}

.restore-error,
.restore-success {
  padding: 20px;
}

.restore-log {
  margin-top: 20px;
  max-height: 300px;
  overflow: auto;
  background: #f5f7fa;
  padding: 16px;
  border-radius: 4px;
}

.restore-log pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 13px;
  line-height: 1.6;
  color: #303133;
}

.batch-step {
  min-height: 400px;
}

.batch-device-filter {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 15px;
}

.batch-result-summary {
  display: inline-block;
  max-width: 100%;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  color: #606266;
  line-height: 1.4;
}

.batch-step-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid #ebeef5;
}

.selected-count {
  color: #606266;
  font-size: 14px;
}

.config-type-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.config-type-grid.compact .config-type-card {
  padding: 14px 12px;
  position: relative;
}

.config-type-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 20px 14px;
  border: 2px solid #e4e7ed;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  text-align: center;
  gap: 4px;
}

.config-type-card:hover {
  border-color: var(--el-color-primary);
  background: #f0f7ff;
}

.config-type-card.active {
  border-color: var(--el-color-primary);
  background: #ecf5ff;
  box-shadow: 0 2px 12px rgba(64, 158, 255, 0.2);
}

.config-type-card.recommended.active {
  border-color: #67c23a;
  background: #f0f9eb;
}

.config-type-card .config-type-name {
  margin-top: 6px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.config-type-card .config-type-desc {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
  line-height: 1.3;
}

.batch-config-layout {
  display: grid;
  grid-template-columns: minmax(280px, 1fr) minmax(240px, 1fr);
  gap: 16px;
  align-items: start;
}

@media (max-width: 900px) {
  .batch-config-layout {
    grid-template-columns: 1fr;
  }
}

.batch-config-form {
  max-width: none;
}

.batch-live-preview {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  background: #fafafa;
  padding: 12px;
  min-height: 200px;
}

.batch-live-preview-title {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 8px;
}

.batch-live-preview-device {
  font-weight: 400;
  color: #909399;
}

.batch-live-preview .preview-commands {
  margin: 0;
  max-height: 360px;
  overflow: auto;
}

.form-hint {
  color: #909399;
  font-size: 13px;
}

.batch-progress {
  padding: 40px 20px;
}

.preview-list {
  max-height: 400px;
  overflow-y: auto;
}

.preview-item {
  margin-bottom: 20px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
}

.preview-item.is-offline {
  border-color: #e6a23c;
}

.preview-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 15px;
  background: #f5f7fa;
  border-bottom: 1px solid #e4e7ed;
}

.preview-header .device-ip {
  margin-left: auto;
  color: #909399;
  font-size: 13px;
}

.preview-header .device-name {
  font-weight: 600;
  color: #303133;
}

.preview-header .device-ip {
  color: #909399;
  font-size: 13px;
}

.preview-commands {
  margin: 0;
  padding: 15px;
  background: #1e1e1e;
  color: #d4d4d4;
  font-family: 'Consolas', monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>

<template>
  <div class="nms-page alarm-page">
    <div class="nms-page-header">
      <div class="nms-page-title-block">
        <h1 class="nms-page-title">
          告警管理
          <el-tag v-if="!canHandle" type="info" size="small" effect="plain" style="margin-left: 8px; vertical-align: middle;">只读</el-tag>
          <el-tag v-else-if="!canWrite" type="warning" size="small" effect="plain" style="margin-left: 8px; vertical-align: middle;">值班处置</el-tag>
        </h1>
        <p class="nms-page-subtitle">Trap / 轮询告警的确认、清除与值班收敛</p>
      </div>
      <div class="nms-page-actions">
        <el-radio-group v-model="sceneView" size="small">
          <el-radio-button label="duty">值班中</el-radio-button>
          <el-radio-button label="history">全部历史</el-radio-button>
        </el-radio-group>
        <el-switch
          v-model="stormCollapse"
          inline-prompt
          active-text="收敛"
          inactive-text="明细"
          @change="onStormToggle"
        />
        <el-button
          v-if="hasActiveAlarms"
          v-permission="'alarms:handle'"
          type="warning"
          size="small"
          @click="openBatchAck"
        >
          批量确认 ({{ activeAlarmCount }})
        </el-button>
        <el-button
          v-if="secondaryActiveCount > 0"
          v-permission="'alarms:handle'"
          type="warning"
          plain
          size="small"
          :loading="ackingSecondary"
          @click="ackSecondaryAlarms"
        >
          确认连带 ({{ secondaryActiveCount }})
        </el-button>
        <el-button
          v-if="hasUnclearedAlarms"
          v-permission="'alarms:handle'"
          type="success"
          size="small"
          @click="batchClear"
        >
          批量清除 ({{ unclearedAlarmCount }})
        </el-button>
        <el-button
          v-if="canWrite && selectedAlarms.length > 0"
          v-permission="'alarms:write'"
          type="danger"
          size="small"
          @click="batchDelete"
        >
          批量删除 ({{ selectedAlarms.length }})
        </el-button>
        <el-button size="small" type="primary" plain @click="$router.push('/aiops/workbench')">
          智能运维
        </el-button>
        <el-button
          v-if="auth.hasAnyPermission('aiops:read', 'alarms:read')"
          size="small"
          type="primary"
          @click="openOpsAssist"
        >
          运维辅助
        </el-button>
        <el-button size="small" @click="refreshAll">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button v-if="canExport" v-permission="'alarms:read'" size="small" @click="exportAlarms" :loading="exporting">
          <el-icon><Download /></el-icon>
          导出筛选
        </el-button>
      </div>
    </div>

    <div class="nms-panel">
      <div class="nms-panel-body alarm-panel-body">
      <el-alert
        v-if="!canHandle"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
        title="当前为只读权限：可查看统计与列表，无法确认/清除/删除告警。"
      />

      <div class="summary-row">
        <div class="metrics">
          <div
            class="metric is-clickable"
            :class="{ 'is-active': activeMetric === 'active' }"
            title="筛选：待处理告警"
            @click="onMetricClick('active')"
          >
            <span class="metric-value">{{ alarmStats?.activeCount || 0 }}</span>
            <span class="metric-label">待处理</span>
          </div>
          <div
            class="metric is-danger is-clickable"
            :class="{ 'is-active': activeMetric === 'critical' }"
            title="筛选：严重待处理"
            @click="onMetricClick('critical')"
          >
            <span class="metric-value">{{ alarmStats?.criticalActiveCount || 0 }}</span>
            <span class="metric-label">严重待处理</span>
          </div>
          <div
            class="metric is-warning is-clickable"
            :class="{ 'is-active': activeMetric === 'major' }"
            title="筛选：重要未确认"
            @click="onMetricClick('major')"
          >
            <span class="metric-value">{{ alarmStats?.majorActiveCount || 0 }}</span>
            <span class="metric-label">重要未确认</span>
          </div>
          <div
            class="metric is-clickable"
            :class="{
              'is-danger': (alarmStats?.overdueActiveCount || 0) > 0,
              'is-active': activeMetric === 'overdue'
            }"
            :title="`筛选：超时≥${overdueMinutes}分`"
            @click="onMetricClick('overdue')"
          >
            <span class="metric-value">{{ alarmStats?.overdueActiveCount || 0 }}</span>
            <span class="metric-label">超时≥{{ overdueMinutes }}分</span>
          </div>
          <div class="metric-divider" />
          <div
            class="metric is-muted is-clickable"
            :class="{ 'is-active': activeMetric === 'results' }"
            title="恢复值班视图"
            @click="onMetricClick('results')"
          >
            <span class="metric-value">{{ total }}</span>
            <span class="metric-label">
              当前结果
              <template v-if="stormCollapse"> / {{ displayRows.length }} 组</template>
            </span>
          </div>
        </div>
        <div class="trend-panel">
          <div class="trend-caption">近 24 小时</div>
          <div ref="trendChartRef" class="trend-chart"></div>
        </div>
      </div>

      <div class="filter-bar">
        <div class="sev-group">
          <span
            class="sev-chip"
            :class="{ active: !selectedSeverity }"
            @click="onSeverityCardClick('')"
          >全部 {{ alarmStats?.total || 0 }}</span>
          <span
            v-for="item in severityCards"
            :key="item.key"
            class="sev-chip"
            :class="['sev-chip-' + item.key, { active: selectedSeverity === item.filter }]"
            @click="onSeverityCardClick(item.filter)"
          >{{ item.label }} {{ item.value }}</span>
        </div>

        <el-divider direction="vertical" class="filter-divider" />

        <el-checkbox-group
          v-model="statusFilter"
          class="status-group"
          :disabled="isMetricDrillLocked"
          @change="onStatusFilterChange"
        >
          <el-checkbox value="ACTIVE">待处理</el-checkbox>
          <el-checkbox value="ACKNOWLEDGED">处理中</el-checkbox>
          <el-checkbox value="CLEARED">已关闭</el-checkbox>
        </el-checkbox-group>

        <el-divider direction="vertical" class="filter-divider" />

        <el-radio-group
          v-model="timePreset"
          size="small"
          class="time-group"
          :disabled="overdueOnly"
        >
          <el-radio-button label="1h">1h</el-radio-button>
          <el-radio-button label="6h">6h</el-radio-button>
          <el-radio-button label="24h">24h</el-radio-button>
          <el-radio-button label="7d">7d</el-radio-button>
          <el-radio-button label="30d">30d</el-radio-button>
          <el-radio-button label="90d">90d</el-radio-button>
          <el-radio-button label="all">全部</el-radio-button>
        </el-radio-group>

        <el-button
          size="small"
          class="custom-time-btn"
          :class="{ 'is-active-range': hasCustomTimeRange || customTimePanelVisible }"
          :disabled="overdueOnly"
          @click="toggleCustomTimePanel"
        >
          <span class="custom-time-btn-text">{{ customTimeLabel }}</span>
          <el-icon
            v-if="hasCustomTimeRange"
            class="custom-time-clear-icon"
            title="清除自定义时间"
            @click.stop="clearCustomTimeRange"
          >
            <CircleClose />
          </el-icon>
        </el-button>

        <el-input
          v-model="searchKeyword"
          placeholder="设备 / IP / 标题"
          clearable
          class="search-input"
          @keyup.enter="onSearchCommit"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-tag
          v-if="deviceIdFilter != null"
          closable
          type="info"
          class="device-filter-tag"
          @close="clearDeviceIdFilter"
        >
          设备 #{{ deviceIdFilter }}
        </el-tag>
      </div>

      <div v-if="customTimePanelVisible" class="custom-time-row">
        <span class="custom-time-row-label">自定义区间</span>
        <el-date-picker
          v-model="customTimeDraftStart"
          type="datetime"
          size="small"
          placeholder="开始时间"
          value-format="YYYY-MM-DDTHH:mm:ss"
          :disabled="overdueOnly"
          :clearable="true"
        />
        <span class="custom-time-row-sep">至</span>
        <el-date-picker
          v-model="customTimeDraftEnd"
          type="datetime"
          size="small"
          placeholder="结束时间"
          value-format="YYYY-MM-DDTHH:mm:ss"
          :disabled="overdueOnly"
          :clearable="true"
        />
        <el-button type="primary" size="small" :disabled="overdueOnly" @click="confirmCustomTimeRange">
          应用
        </el-button>
        <el-button size="small" @click="clearCustomTimeRange">清除</el-button>
        <el-button link size="small" @click="customTimePanelVisible = false">收起</el-button>
      </div>

      <div class="alarm-work-split">
      <div class="alarm-work-main">
      <el-table
        ref="alarmTableRef"
        :data="displayRows"
        v-loading="loading"
        row-key="rowKey"
        highlight-current-row
        style="width: 100%"
        :row-class-name="tableRowClassName"
        @selection-change="handleSelectionChange"
        @sort-change="handleSortChange"
        @row-click="onRowClick"
        @row-dblclick="onRowDblClick"
      >
        <el-table-column v-if="stormCollapse" type="expand" width="40">
          <template #default="{ row }">
            <div v-if="row.stormCount > 1" class="storm-expand">
              <div class="storm-expand-title">本组共 {{ row.stormCount }} 条（本页）</div>
              <el-table :data="row.members" size="small">
                <el-table-column prop="id" label="ID" width="70" />
                <el-table-column prop="occurredAt" label="发生时间" width="170">
                  <template #default="{ row: m }">{{ formatDateTime(m.occurredAt) }}</template>
                </el-table-column>
                <el-table-column prop="severity" label="级别" width="80">
                  <template #default="{ row: m }">{{ getSeverityText(m.severity) }}</template>
                </el-table-column>
                <el-table-column label="操作" width="100">
                  <template #default="{ row: m }">
                    <el-button link type="primary" size="small" @click="showDetail(m)">详情</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
            <div v-else class="storm-expand muted">单条告警</div>
          </template>
        </el-table-column>
        <el-table-column
          v-if="auth.hasAnyPermission('alarms:handle', 'alarms:write')"
          type="selection"
          width="48"
        />
        <el-table-column prop="severity" label="级别" width="88" sortable="custom">
          <template #default="{ row }">
            <el-tag
              :type="getSeverityType(row.severity)"
              size="small"
              :effect="row.status === 'ACTIVE' && (row.severity === 'CRITICAL' || row.severity === 'MAJOR') ? 'dark' : 'light'"
            >
              {{ getSeverityText(row.severity) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110" sortable="custom">
          <template #default="{ row }">
            <el-tooltip
              :disabled="!(row.status === 'ACKNOWLEDGED' && (row.acknowledgedBy || row.acknowledgeNote))"
              placement="top"
              :content="ackHint(row)"
            >
              <span class="status-cell" :class="'is-' + String(row.status || '').toLowerCase()">
                <i class="dot" />
                {{ getStatusText(row.status) }}
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="告警标题" min-width="180" show-overflow-tooltip sortable="custom">
          <template #default="{ row }">
            <span
              class="alarm-title-cell"
              :class="'sev-text-' + severityCssKey(row.severity)"
            >
              {{ row.title }}
            </span>
            <el-tag v-if="row.stormCount > 1" size="small" type="warning" class="storm-tag" effect="plain">
              ×{{ row.stormCount }}
            </el-tag>
            <el-tag v-else-if="(row.repeatCount || 1) > 1" size="small" type="info" class="storm-tag" effect="plain">
              重复×{{ row.repeatCount }}
            </el-tag>
            <el-tag v-if="row.secondaryAlarm" size="small" type="info" class="storm-tag">连带</el-tag>
            <el-tag v-if="row.correlationType === 'LINK'" size="small" class="storm-tag">链路</el-tag>
            <el-tag v-if="row.correlationType === 'STORM' && !row.stormCount" size="small" type="warning" class="storm-tag" effect="plain">风暴</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="deviceName" label="设备" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <el-button v-if="row.deviceId" link type="primary" @click.stop="gotoTopology(row, 'alarms')">
              {{ row.deviceName || ('设备#' + row.deviceId) }}
            </el-button>
            <span v-else class="text-muted">{{ row.deviceName || '未关联' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="deviceIp" label="管理IP" width="120" sortable="custom">
          <template #default="{ row }">
            <span
              class="ip-text"
              :class="{ linkable: row.deviceId }"
              @click.stop="row.deviceId && gotoTopology(row, 'alarms')"
            >{{ row.deviceIp || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="trapType" label="类型" width="140" show-overflow-tooltip sortable="custom">
          <template #default="{ row }">{{ getTrapTypeText(row.trapType, row.rawData) }}</template>
        </el-table-column>
        <el-table-column label="持续时长" width="110">
          <template #default="{ row }">
            <span :class="{ overdue: isOverdue(row) }">{{ formatDuration(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="occurredAt" label="最近发生" width="160" sortable="custom">
          <template #default="{ row }">{{ formatDateTime(row.occurredAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click.stop="showDetail(row)">详情</el-button>
            <el-button
              v-if="auth.hasAnyPermission('aiops:read', 'alarms:read')"
              size="small"
              link
              type="primary"
              @click.stop="askSmartAnalyze(row)"
            >智能分析</el-button>
            <el-button
              v-if="row.status === 'ACTIVE'"
              v-permission="'alarms:handle'"
              size="small"
              link
              type="warning"
              @click.stop="openAckDialog(row)"
            >{{ row.ackCloses ? '阅知关闭' : '确认' }}</el-button>
            <el-button
              v-if="row.status !== 'CLEARED'"
              v-permission="'alarms:handle'"
              size="small"
              link
              type="success"
              @click.stop="clearAlarm(row.id)"
            >清除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          background
          @size-change="onPageSizeChange"
          @current-change="onPageChange"
        />
      </div>
      </div>

      <OpsInlineShell storage-key="alarms" class="alarm-work-side" />
      </div>
      </div>
    </div>

    <!-- 确认对话框 -->
    <el-dialog
      v-model="ackDialogVisible"
      :title="ackDialogTitle"
      width="480px"
      destroy-on-close
      append-to-body
      :z-index="3100"
    >
      <el-form label-width="80px">
        <el-form-item label="确认人">
          <el-input :model-value="auth.displayName" disabled />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="ackForm.note"
            type="textarea"
            :rows="3"
            :placeholder="ackDialogIsAckClose
              ? '阅知类事件：确认后直接关闭（选填备注）'
              : '建议填写排查结论或后续计划（选填）'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ackDialogVisible = false">取消</el-button>
        <el-button type="warning" @click="submitAck">{{ ackDialogConfirmLabel }}</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-dialog
      v-model="showDetailPanel"
      title="告警运维详情"
      width="720px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <div v-if="currentAlarm" class="detail-body">
        <div class="detail-top">
          <el-tag
            :type="getSeverityType(currentAlarm.severity)"
            :effect="currentAlarm.severity === 'CRITICAL' || currentAlarm.severity === 'MAJOR' ? 'dark' : 'light'"
          >
            {{ getSeverityText(currentAlarm.severity) }}
          </el-tag>
          <el-tag :type="getStatusType(currentAlarm.status)" effect="plain">{{ getStatusText(currentAlarm.status) }}</el-tag>
          <span class="detail-duration">持续 {{ formatDuration(currentAlarm) }}</span>
        </div>
        <h3 class="detail-title">{{ currentAlarm.title }}</h3>

        <el-steps :active="lifecycleStep" finish-status="success" align-center class="life-steps">
          <el-step title="发生" :description="formatDateTime(currentAlarm.occurredAt)" />
          <el-step
            title="处理中"
            :description="currentAlarm.acknowledgedAt ? formatDateTime(currentAlarm.acknowledgedAt) : '未确认 · 待接手'"
          />
          <el-step
            title="已关闭"
            :description="currentAlarm.clearedAt ? formatDateTime(currentAlarm.clearedAt) : '未关闭'"
          />
        </el-steps>

        <div class="playbook">
          <div class="playbook-title">真实处置</div>
          <el-text type="info" size="small" style="display:block;margin-bottom:8px">
            下列按钮会真实刷新状态或写入 ACK/配置，不是文字流程。
          </el-text>
          <div class="playbook-actions">
            <el-button
              v-if="currentAlarm.status === 'ACTIVE' || currentAlarm.status === 'ACKNOWLEDGED'"
              type="warning"
              size="small"
              :loading="disposingAlarm"
              @click="disposeCurrentAlarm"
            >
              标准处置
            </el-button>
            <el-button
              v-if="currentAlarm.deviceId"
              type="primary"
              size="small"
              :loading="refreshingAlarmDevice"
              @click="refreshCurrentAlarmDevice"
            >
              刷新设备
            </el-button>
            <el-button
              v-if="currentAlarm.secondaryAlarm || currentAlarm.secondary || currentAlarm.parentAlarmId"
              size="small"
              :loading="ackingSecondary"
              @click="ackSecondaryAlarms"
            >
              确认噪音
            </el-button>
            <el-button
              v-if="currentAlarm.status === 'ACTIVE'"
              size="small"
              @click="openAckDialog(currentAlarm)"
            >
              确认本告警{{ currentAlarm.ackCloses ? '（阅知关闭）' : '' }}
            </el-button>
            <el-button
              v-if="auth.hasAnyPermission('aiops:read', 'alarms:read')"
              type="primary"
              size="small"
              @click="askSmartAnalyze(currentAlarm)"
            >
              智能分析
            </el-button>
            <el-button
              type="primary"
              size="small"
              plain
              @click="gotoAiopsAnalyze(currentAlarm)"
            >
              智能运维中心
            </el-button>
            <el-button
              v-if="currentAlarm.deviceId && auth.hasPermission('webssh:connect')"
              size="small"
              @click="openTerminalDialog"
            >
              打开终端
            </el-button>
          </div>
        </div>

        <el-descriptions :column="2" border size="small" class="detail-desc">
          <el-descriptions-item label="告警ID">{{ currentAlarm.id }}</el-descriptions-item>
          <el-descriptions-item label="纳管设备">
            <el-button
              v-if="currentAlarm.deviceId"
              link
              type="primary"
              @click="gotoTopology(currentAlarm, 'alarms')"
            >
              {{ currentAlarm.deviceName || ('设备#' + currentAlarm.deviceId) }}
            </el-button>
            <span v-else>{{ currentAlarm.deviceName || '未关联' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="管理IP">{{ currentAlarm.deviceIp || '-' }}</el-descriptions-item>
          <el-descriptions-item label="告警类型">{{ getTrapTypeText(currentAlarm.trapType, currentAlarm.rawData) }}</el-descriptions-item>
          <el-descriptions-item
            v-if="parseDescField(currentAlarm.description, 'Agent地址')"
            label="Agent地址"
          >
            {{ parseDescField(currentAlarm.description, 'Agent地址') }}
          </el-descriptions-item>
          <el-descriptions-item
            v-if="parseDescField(currentAlarm.description, 'Trap源地址')"
            label="Trap源"
          >
            {{ parseDescField(currentAlarm.description, 'Trap源地址') }}
          </el-descriptions-item>
          <el-descriptions-item v-if="(currentAlarm.repeatCount || 1) > 1" label="重复次数">
            {{ currentAlarm.repeatCount }}
            <span v-if="currentAlarm.lastOccurredAt" class="text-muted">
              （最近 {{ formatDateTime(currentAlarm.lastOccurredAt) }}）
            </span>
          </el-descriptions-item>
          <el-descriptions-item v-if="currentAlarm.acknowledgedBy" label="确认人">
            {{ currentAlarm.acknowledgedBy }}
          </el-descriptions-item>
          <el-descriptions-item v-if="currentAlarm.acknowledgeNote" label="处理备注" :span="2">
            {{ currentAlarm.acknowledgeNote }}
          </el-descriptions-item>
          <el-descriptions-item v-if="currentAlarm.clearNote" label="关闭原因" :span="2">
            {{ currentAlarm.clearNote }}
          </el-descriptions-item>
          <el-descriptions-item v-if="currentAlarm.correlationType || currentAlarm.secondaryAlarm" label="智能关联" :span="2">
            <el-tag v-if="currentAlarm.secondaryAlarm" size="small" type="info" style="margin-right: 6px">可能是连带</el-tag>
            <el-tag v-if="currentAlarm.correlationType" size="small" style="margin-right: 6px">{{ currentAlarm.correlationType }}</el-tag>
            <span>{{ currentAlarm.correlationNote || '' }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="alarmAiopsCtx" class="aiops-ctx-block">
          <h4>智能上下文</h4>
          <p v-if="(alarmAiopsCtx.relatedChanges || []).length" class="aiops-ctx-tip">
            告警前后发现 {{ alarmAiopsCtx.relatedChanges.length }} 条疑似相关配置变更
          </p>
          <el-table v-if="(alarmAiopsCtx.relatedChanges || []).length" :data="alarmAiopsCtx.relatedChanges" size="small">
            <el-table-column prop="changeType" label="类型" width="90" />
            <el-table-column prop="operator" label="操作人" width="90" />
            <el-table-column prop="createdAt" label="时间" />
          </el-table>
          <p v-else class="aiops-ctx-tip muted">时间窗内无配置变更记录</p>
          <el-button link type="primary" @click="$router.push('/aiops/overview')">打开智能运维中心</el-button>
        </div>

        <div v-if="alarmSummaryText" class="alarm-summary-box">
          <div class="alarm-summary-label">事件说明</div>
          <p class="alarm-summary-text">{{ alarmSummaryText }}</p>
        </div>

        <el-collapse class="adv-collapse" :model-value="['desc']">
          <el-collapse-item title="告警详情" name="desc">
            <pre class="block-pre desc-pre">{{ alarmDetailBody || currentAlarm.description || '-' }}</pre>
          </el-collapse-item>
          <el-collapse-item
            v-if="canHandle"
            title="原始 Trap（高级）"
            name="raw"
          >
            <pre class="block-pre mono">{{ currentAlarm.rawData || '-' }}</pre>
          </el-collapse-item>
        </el-collapse>
      </div>

      <template #footer>
        <el-button
          v-if="currentAlarm?.status === 'ACTIVE'"
          v-permission="'alarms:handle'"
          type="warning"
          @click="openAckDialog(currentAlarm)"
        >
          {{ currentAlarm.ackCloses ? '阅知关闭' : '确认' }}
        </el-button>
        <el-button
          v-if="currentAlarm?.status !== 'CLEARED'"
          v-permission="'alarms:handle'"
          type="success"
          @click="clearAlarm(currentAlarm.id)"
        >
          清除
        </el-button>
        <el-button v-permission="'alarms:write'" type="danger" @click="deleteAlarm(currentAlarm?.id)">删除</el-button>
        <el-button @click="closeDetailPanel">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 告警页内 WebSSH，不跳转拓扑 -->
    <el-dialog
      v-model="terminalDialogVisible"
      width="92vw"
      top="3vh"
      destroy-on-close
      :close-on-click-modal="false"
      append-to-body
      class="alarm-terminal-dialog"
      @opened="onTerminalOpened"
      @closed="onTerminalClosed"
    >
      <template #header>
        <div class="term-dlg-header">
          <div class="term-dlg-title-row">
            <span class="term-dlg-title">WebSSH 终端</span>
            <el-tag
              v-if="terminalAlarm"
              :type="getSeverityType(terminalAlarm.severity)"
              size="small"
              effect="dark"
            >
              {{ getSeverityText(terminalAlarm.severity) }}
            </el-tag>
          </div>
          <div v-if="terminalAlarm" class="term-dlg-meta">
            <span class="meta-item" :title="terminalAlarm.title">{{ terminalAlarm.title }}</span>
            <span class="meta-sep">·</span>
            <span class="meta-item">{{ terminalDeviceName || ('#' + terminalDeviceId) }}</span>
            <span v-if="terminalAlarm.deviceIp" class="meta-sep">·</span>
            <span v-if="terminalAlarm.deviceIp" class="meta-item mono">{{ terminalAlarm.deviceIp }}</span>
          </div>
        </div>
      </template>

      <div class="alarm-terminal-wrap">
        <XtermTerminal
          v-if="terminalDialogVisible && terminalDeviceId"
          :device-id="terminalDeviceId"
          :device-name="terminalDeviceName"
          :auto-connect="true"
        />
      </div>

      <template #footer>
        <div class="term-dlg-footer">
          <div class="term-dlg-hint">关闭窗口将断开 SSH。SSH 账号取自设备管理配置。</div>
          <div class="term-dlg-actions">
            <el-button
              v-if="terminalAlarm?.status === 'ACTIVE' && canHandle"
              type="warning"
              @click="ackFromTerminal"
            >
              {{ terminalAlarm?.ackCloses ? '阅知关闭' : '确认告警' }}
            </el-button>
            <el-button
              v-if="terminalAlarm && terminalAlarm.status !== 'CLEARED' && canHandle"
              type="success"
              @click="clearFromTerminal"
            >
              清除告警
            </el-button>
            <el-button @click="backToAlarmDetail">回详情</el-button>
            <el-button type="primary" @click="terminalDialogVisible = false">关闭终端</el-button>
          </div>
        </div>
      </template>
    </el-dialog>

    <!-- 告警页内设备监视，不跳转拓扑 -->
    <el-dialog
      v-model="monitorDialogVisible"
      :title="monitorTitle"
      width="720px"
      top="8vh"
      destroy-on-close
      append-to-body
      class="alarm-monitor-dialog"
      @opened="loadMonitorData"
      @closed="onMonitorClosed"
    >
      <div v-loading="monitorLoading">
        <el-alert
          v-if="!monitorLatest && !monitorLoading"
          type="info"
          :closable="false"
          show-icon
          title="暂无性能数据（设备可能离线或尚未采集到 SNMP 指标）"
          style="margin-bottom: 12px"
        />
        <el-row :gutter="16">
          <el-col :span="8">
            <div class="mon-card">
              <div class="mon-label">CPU</div>
              <div class="mon-value">{{ formatMetricPct(monitorLatest?.cpuUsage) }}</div>
            </div>
          </el-col>
          <el-col :span="8">
            <div class="mon-card">
              <div class="mon-label">内存</div>
              <div class="mon-value">{{ formatMetricPct(monitorLatest?.memoryUsage) }}</div>
            </div>
          </el-col>
          <el-col :span="8">
            <div class="mon-card">
              <div class="mon-label">温度</div>
              <div class="mon-value">{{ monitorLatest?.temperature != null ? monitorLatest.temperature.toFixed(1) + '°C' : '未采集' }}</div>
            </div>
          </el-col>
        </el-row>
        <div class="mon-ports-title">端口速率（最新）</div>
        <el-table :data="monitorPorts" size="small" border max-height="280" empty-text="暂无端口数据">
          <el-table-column prop="portName" label="端口" min-width="120" show-overflow-tooltip />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">{{ row.portOperStatus || '-' }}</template>
          </el-table-column>
          <el-table-column label="入向" width="110">
            <template #default="{ row }">{{ formatRateSimple(row.ifInRate) }}</template>
          </el-table-column>
          <el-table-column label="出向" width="110">
            <template #default="{ row }">{{ formatRateSimple(row.ifOutRate) }}</template>
          </el-table-column>
        </el-table>
      </div>
      <template #footer>
        <el-button
          v-if="monitorDeviceId && auth.hasPermission('performance:read')"
          @click="goFullPerformance"
        >
          打开性能页
        </el-button>
        <el-button type="primary" @click="monitorDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { alarmApi, performanceApi, aiopsApi, deviceApi } from '@/api/device'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as echarts from 'echarts'
import { createVisibilityPoller } from '@/composables/useVisibilityPoller'
import { useAuthStore } from '@/stores/auth'
import { storeToRefs } from 'pinia'
import { useThemeStore } from '@/stores/theme'
import { readThemePrimary, primaryAlpha, useThemeSync } from '@/composables/useThemeColors'
import XtermTerminal from '@/components/XtermTerminal.vue'
import { openWebTerminal } from '@/composables/webTerminalBus'
import { askOpsAssistant, syncOpsAssistantFocus } from '@/composables/askOpsAssistant'
import { clearPageContext, setPageContext, usePageOpsBus } from '@/composables/pageOpsBus'
import { requestOpenOpsInline } from '@/composables/useOpsInlinePanel'
import OpsInlineShell from '@/components/ops-assistant/OpsInlineShell.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const { sink: pageSink } = usePageOpsBus()
const themeStore = useThemeStore()
const { accent, revision } = storeToRefs(themeStore)
const STORM_WINDOW_MS = 10 * 60 * 1000
const overdueMinutes = 30

const alarms = ref([])
const alarmStats = ref({})
const loading = ref(false)
const searchKeyword = ref('')
/** 从拓扑等入口带入的设备过滤 */
const deviceIdFilter = ref(null)
const statusFilter = ref(['ACTIVE', 'ACKNOWLEDGED'])
const timeRange = ref('24h')
/** 已生效的自定义区间 [from, to] */
const customTimeRange = ref(null)
const customTimePanelVisible = ref(false)
const customTimeDraftStart = ref(null)
const customTimeDraftEnd = ref(null)

const hasCustomTimeRange = computed(() =>
  Array.isArray(customTimeRange.value) && customTimeRange.value.length === 2
)

const customTimeLabel = computed(() => {
  if (!hasCustomTimeRange.value) return '自定义'
  const [a, b] = customTimeRange.value
  const fmt = (s) => String(s || '').replace('T', ' ').slice(5, 16)
  return `${fmt(a)} ~ ${fmt(b)}`
})

/** 预设 Radio：自定义生效时不高亮任一预设，避免非法 model 值 */
const timePreset = computed({
  get() {
    if (hasCustomTimeRange.value || timeRange.value === 'custom') return undefined
    return timeRange.value
  },
  set(v) {
    if (!v) return
    timeRange.value = v
    onTimeRangeChange()
  }
})

const resetCustomTimeDraft = () => {
  if (hasCustomTimeRange.value) {
    customTimeDraftStart.value = customTimeRange.value[0]
    customTimeDraftEnd.value = customTimeRange.value[1]
  } else {
    customTimeDraftStart.value = null
    customTimeDraftEnd.value = null
  }
}
const selectedAlarms = ref([])
const showDetailPanel = ref(false)
const currentAlarm = ref(null)
const alarmAiopsCtx = ref(null)
const trendChartRef = ref(null)
const selectedSeverity = ref('')
const alarmTableRef = ref(null)
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const sortProp = ref('occurredAt')
const sortOrder = ref('desc')
/** 内部场景：duty | critical | overdue | history（后两者由指标下钻设置） */
const dutyView = ref('duty')

/** 顶部只暴露「值班中 / 全部历史」；指标下钻时仍显示为值班中 */
const sceneView = computed({
  get() {
    return dutyView.value === 'history' ? 'history' : 'duty'
  },
  set(v) {
    dutyView.value = v === 'history' ? 'history' : 'duty'
    applyDutyView()
  }
})
const stormCollapse = ref(true)
const overdueOnly = ref(false)

const ackDialogVisible = ref(false)
const ackForm = ref({ note: '', ids: [], mode: 'single' })
/** 打开确认框时记录是否阅知关闭（批量时：全部命中才视为阅知关闭） */
const ackDialogAckClose = ref(false)
const ackDialogTitle = computed(() => (ackDialogAckClose.value ? '阅知关闭' : '确认告警'))
const ackDialogIsAckClose = computed(() => ackDialogAckClose.value)
const ackDialogConfirmLabel = computed(() => (ackDialogAckClose.value ? '阅知关闭' : '确认告警'))
const exporting = ref(false)
const ackingSecondary = ref(false)
const disposingAlarm = ref(false)
const refreshingAlarmDevice = ref(false)
const terminalDialogVisible = ref(false)
const terminalDeviceId = ref(null)
const terminalDeviceName = ref('')
const terminalAlarm = ref(null)
const monitorDialogVisible = ref(false)
const monitorDeviceId = ref(null)
const monitorDeviceName = ref('')
const monitorLatest = ref(null)
const monitorPorts = ref([])
const monitorLoading = ref(false)

const canHandle = computed(() => auth.hasAnyPermission('alarms:handle', 'alarms:write'))
const canWrite = computed(() => auth.hasPermission('alarms:write'))
const canExport = computed(() => auth.hasPermission('alarms:read'))
const monitorTitle = computed(() => {
  const name = monitorDeviceName.value || (monitorDeviceId.value ? `设备#${monitorDeviceId.value}` : '')
  return name ? `设备监视 · ${name}` : '设备监视'
})

let refreshInterval = null
let trendChart = null
let selectedIds = []
let searchTimer = null
let lastActiveCount = null
let chartResizeHandler = null
let skipStatusWatch = false

const alarmPoller = createVisibilityPoller(() => {
  loadAlarms(true)
  loadStats(true)
}, 15000)

const severityCards = computed(() => {
  void revision.value
  return [
    { key: 'critical', label: '严重', filter: 'CRITICAL', color: '#f56c6c', value: alarmStats.value?.severityStats?.critical || 0 },
    { key: 'major', label: '重要', filter: 'MAJOR', color: '#e6a23c', value: alarmStats.value?.severityStats?.major || 0 },
    { key: 'minor', label: '次要', filter: 'MINOR', color: accent.value.primary, value: alarmStats.value?.severityStats?.minor || 0 },
    { key: 'info', label: '提示', filter: 'INFO', color: '#909399', value: alarmStats.value?.severityStats?.info || 0 }
  ]
})

const hasActiveAlarms = computed(() => selectedAlarms.value.some(a => a.status === 'ACTIVE'))
const activeAlarmCount = computed(() => selectedAlarms.value.filter(a => a.status === 'ACTIVE').length)
const secondaryActiveCount = computed(() =>
  (alarms.value || []).filter(a => a.status === 'ACTIVE' && (a.secondaryAlarm || a.secondary)).length
)
const hasUnclearedAlarms = computed(() => selectedAlarms.value.some(a => a.status !== 'CLEARED'))
const unclearedAlarmCount = computed(() => selectedAlarms.value.filter(a => a.status !== 'CLEARED').length)

/** Element Plus Steps 的 active 从 0 起：0=发生 1=处理中 2=已关闭；≥3 表示全部完成 */
const lifecycleStep = computed(() => {
  const a = currentAlarm.value
  if (!a) return 0
  if (a.status === 'CLEARED' || a.clearedAt) return 3
  if (a.status === 'ACKNOWLEDGED' || a.acknowledgedAt) return 1
  return 0
})

/** 本页风暴收敛 */
const displayRows = computed(() => {
  const list = alarms.value || []
  if (!stormCollapse.value) {
    return list.map(a => ({
      ...a,
      rowKey: String(a.id),
      stormCount: 1,
      members: [a]
    }))
  }
  const groups = new Map()
  for (const a of list) {
    const t = a.occurredAt ? new Date(a.occurredAt).getTime() : 0
    const bucket = Math.floor(t / STORM_WINDOW_MS)
    const key = `${a.deviceId || a.deviceIp || ''}||${a.title || ''}||${a.status || ''}||${bucket}`
    if (!groups.has(key)) {
      groups.set(key, [])
    }
    groups.get(key).push(a)
  }
  const rows = []
  for (const members of groups.values()) {
    members.sort((x, y) => new Date(y.occurredAt) - new Date(x.occurredAt))
    const head = members[0]
    rows.push({
      ...head,
      rowKey: `g-${head.id}`,
      stormCount: members.length,
      members
    })
  }
  rows.sort((a, b) => new Date(b.occurredAt) - new Date(a.occurredAt))
  return rows
})

const buildQueryParams = () => {
  const params = {
    page: page.value - 1,
    size: pageSize.value,
    sort: `${sortProp.value},${sortOrder.value}`
  }
  if (overdueOnly.value) {
    params.overdueOnly = true
    params.overdueMinutes = overdueMinutes
  } else {
    if (hasCustomTimeRange.value) {
      params.from = customTimeRange.value[0]
      params.to = customTimeRange.value[1]
    } else if (timeRange.value && timeRange.value !== 'custom') {
      params.timeRange = timeRange.value
    }
    if (statusFilter.value.length > 0) {
      params.status = statusFilter.value
    }
  }
  if (selectedSeverity.value) {
    params.severity = selectedSeverity.value
  }
  if (searchKeyword.value && searchKeyword.value.trim()) {
    params.keyword = searchKeyword.value.trim()
  }
  if (deviceIdFilter.value != null) {
    params.deviceId = deviceIdFilter.value
  }
  return params
}

const loadAlarms = async (silent = false) => {
  if (!silent) loading.value = true
  try {
    selectedIds = selectedAlarms.value.map(a => a.id)
    const data = await alarmApi.queryAlarms(buildQueryParams())
    const content = Array.isArray(data?.content) ? data.content : (Array.isArray(data) ? data : [])
    alarms.value = content
    total.value = data?.totalElements != null ? Number(data.totalElements) : content.length

    nextTick(() => {
      if (alarmTableRef.value && selectedIds.length > 0) {
        displayRows.value.forEach(row => {
          if (selectedIds.includes(row.id)) {
            alarmTableRef.value.toggleRowSelection(row, true)
          }
        })
      }
    })
  } catch (err) {
    console.error('加载告警列表失败', err)
    if (!silent) ElMessage.error('加载告警列表失败')
  } finally {
    if (!silent) loading.value = false
  }
}

const loadStats = async (silent = false) => {
  try {
    const stats = await alarmApi.getAlarmStats()
    alarmStats.value = stats || {}
    const active = Number(stats?.activeCount || 0)
    if (lastActiveCount != null && active > lastActiveCount && silent) {
      ElMessage.warning(`新增 ${active - lastActiveCount} 条活跃告警`)
    }
    lastActiveCount = active
    updateTrendChart()
  } catch (err) {
    console.error('加载统计失败', err)
  }
}

const refreshAll = async () => {
  page.value = 1
  await Promise.all([loadAlarms(false), loadStats(false)])
}

function openOpsAssist() {
  requestOpenOpsInline('alarms')
}

const sameStatus = (expected) => {
  const cur = [...statusFilter.value].sort().join(',')
  const exp = [...expected].sort().join(',')
  return cur === exp
}

const activeMetric = computed(() => {
  if (overdueOnly.value) return 'overdue'
  if (sameStatus(['ACTIVE']) && selectedSeverity.value === 'CRITICAL') return 'critical'
  if (sameStatus(['ACTIVE']) && selectedSeverity.value === 'MAJOR') return 'major'
  if (sameStatus(['ACTIVE']) && !selectedSeverity.value) return 'active'
  if (dutyView.value === 'duty' && sameStatus(['ACTIVE', 'ACKNOWLEDGED']) && !selectedSeverity.value) {
    return 'results'
  }
  return ''
})

/** 严重/超时下钻时锁定状态筛选，避免与指标语义冲突 */
const isMetricDrillLocked = computed(() => {
  return dutyView.value === 'critical' || dutyView.value === 'overdue' || overdueOnly.value
})

const applyDutyView = () => {
  skipStatusWatch = true
  overdueOnly.value = false
  customTimeRange.value = null
  customTimePanelVisible.value = false
  customTimeDraftStart.value = null
  customTimeDraftEnd.value = null
  if (dutyView.value === 'duty') {
    statusFilter.value = ['ACTIVE', 'ACKNOWLEDGED']
    selectedSeverity.value = ''
    timeRange.value = '24h'
  } else if (dutyView.value === 'critical') {
    statusFilter.value = ['ACTIVE']
    selectedSeverity.value = 'CRITICAL'
    timeRange.value = '7d'
  } else if (dutyView.value === 'overdue') {
    statusFilter.value = ['ACTIVE']
    selectedSeverity.value = ''
    overdueOnly.value = true
  } else {
    // 全部历史：不限时间窗，可由预设或自定义区间再收窄
    statusFilter.value = ['ACTIVE', 'ACKNOWLEDGED', 'CLEARED']
    selectedSeverity.value = ''
    timeRange.value = 'all'
  }
  page.value = 1
  nextTick(() => {
    skipStatusWatch = false
    loadAlarms(false)
  })
}

const onMetricClick = (key) => {
  // 再次点击同一指标 → 回到值班中视图
  if (activeMetric.value === key && key !== 'results') {
    dutyView.value = 'duty'
    applyDutyView()
    return
  }
  if (key === 'critical') {
    dutyView.value = 'critical'
    applyDutyView()
    return
  }
  if (key === 'overdue') {
    dutyView.value = 'overdue'
    applyDutyView()
    return
  }
  if (key === 'results') {
    dutyView.value = 'duty'
    applyDutyView()
    return
  }

  skipStatusWatch = true
  overdueOnly.value = false
  dutyView.value = 'duty'
  customTimeRange.value = null
  customTimePanelVisible.value = false
  statusFilter.value = ['ACTIVE']
  timeRange.value = '7d'
  if (key === 'major') {
    selectedSeverity.value = 'MAJOR'
  } else {
    // active
    selectedSeverity.value = ''
  }
  page.value = 1
  nextTick(() => {
    skipStatusWatch = false
    loadAlarms(false)
  })
}

const onSeverityCardClick = (filter) => {
  if (dutyView.value === 'critical' || dutyView.value === 'overdue') {
    dutyView.value = 'duty'
    skipStatusWatch = true
    overdueOnly.value = false
    customTimeRange.value = null
    customTimePanelVisible.value = false
    statusFilter.value = ['ACTIVE', 'ACKNOWLEDGED']
    timeRange.value = '24h'
    skipStatusWatch = false
  }
  if (filter === '') {
    selectedSeverity.value = ''
    page.value = 1
    loadAlarms(false)
    return
  }
  filterBySeverity(filter)
}

const onFilterChange = () => {
  page.value = 1
  loadAlarms(false)
}

/** 状态勾选：用 @change 入参，避免 v-model 时序导致读到旧值 */
const onStatusFilterChange = (val) => {
  if (skipStatusWatch) return
  const next = Array.isArray(val) ? [...val] : [...(statusFilter.value || [])]
  // 至少保留一种状态，避免空筛选语义不清
  if (next.length === 0) {
    statusFilter.value = ['ACTIVE']
    ElMessage.info('已默认勾选「待处理」')
  } else {
    statusFilter.value = next
  }
  page.value = 1
  loadAlarms(false)
}

const onTimeRangeChange = () => {
  customTimeRange.value = null
  customTimePanelVisible.value = false
  resetCustomTimeDraft()
  onFilterChange()
}

const toggleCustomTimePanel = () => {
  if (overdueOnly.value) return
  customTimePanelVisible.value = !customTimePanelVisible.value
  if (customTimePanelVisible.value) {
    resetCustomTimeDraft()
  }
}

const confirmCustomTimeRange = () => {
  const start = customTimeDraftStart.value
  const end = customTimeDraftEnd.value
  if (!start || !end) {
    ElMessage.warning('请选择完整的开始与结束时间')
    return
  }
  if (String(start) > String(end)) {
    ElMessage.warning('开始时间不能晚于结束时间')
    return
  }
  customTimeRange.value = [start, end]
  // 保留上一预设高亮无关；查询以自定义区间为准。置空避免 radio 无匹配值异常
  timeRange.value = 'custom'
  customTimePanelVisible.value = false
  onFilterChange()
}

const clearCustomTimeRange = () => {
  customTimeRange.value = null
  customTimeDraftStart.value = null
  customTimeDraftEnd.value = null
  if (timeRange.value === 'custom' || !timeRange.value) {
    timeRange.value = dutyView.value === 'history' ? 'all' : '24h'
  }
  customTimePanelVisible.value = false
  onFilterChange()
}

const onSearchCommit = () => {
  page.value = 1
  loadAlarms(false)
}

const onPageChange = () => loadAlarms(false)
const onPageSizeChange = () => {
  page.value = 1
  loadAlarms(false)
}

const onStormToggle = () => {
  selectedAlarms.value = []
}

const updateTrendChart = () => {
  if (!trendChartRef.value) return
  if (!trendChart) {
    nextTick(() => {
      if (!trendChartRef.value) return
      trendChart = echarts.init(trendChartRef.value)
      renderChart()
    })
  } else {
    renderChart()
  }
}

const renderChart = () => {
  if (!trendChart) return
  const trend = Array.isArray(alarmStats.value.hourlyTrend) ? alarmStats.value.hourlyTrend : []
  const hours = trend.length ? trend.map(t => t.hour) : []
  const counts = trend.length ? trend.map(t => Number(t.count) || 0) : []
  trendChart.setOption({
    grid: { left: 28, right: 4, top: 6, bottom: 16 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: hours,
      boundaryGap: false,
      axisLabel: { show: false },
      axisLine: { show: false },
      axisTick: { show: false }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitNumber: 2,
      axisLabel: { show: false },
      splitLine: { show: false },
      axisLine: { show: false },
      axisTick: { show: false }
    },
    series: [{
      type: 'line',
      smooth: true,
      symbol: 'none',
      data: counts,
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: primaryAlpha(0.22) },
          { offset: 1, color: primaryAlpha(0.02) }
        ])
      },
      lineStyle: { color: readThemePrimary(), width: 2 }
    }]
  })
}

useThemeSync(() => {
  if (trendChart) renderChart()
})

const filterBySeverity = (severity) => {
  selectedSeverity.value = selectedSeverity.value === severity ? '' : severity
  page.value = 1
  loadAlarms(false)
}

const handleSelectionChange = (val) => {
  selectedAlarms.value = val
}

const handleSortChange = ({ prop, order }) => {
  if (!prop || !order) {
    sortProp.value = 'occurredAt'
    sortOrder.value = 'desc'
  } else {
    sortProp.value = prop
    sortOrder.value = order === 'ascending' ? 'asc' : 'desc'
  }
  loadAlarms(false)
}

const tableRowClassName = ({ row }) => {
  let sev = String(row.severity || '').toLowerCase()
  // WARNING 与 MAJOR 统一按「重要」底色
  if (sev === 'warning') sev = 'major'
  const classes = [`sev-${sev}`]
  if (row.status === 'CLEARED') {
    classes.push('row-cleared')
  }
  if (isOverdue(row)) classes.push('row-overdue')
  return classes.join(' ')
}

const resolveAlarmRow = (alarm) => {
  if (!alarm) return null
  if (alarm.id && !alarm.rowKey) return alarm
  if (alarm.members && alarm.members[0] && alarm.stormCount) {
    return { ...alarm, id: alarm.id }
  }
  return alarm
}

const focusAlarmRow = (alarm, { autoAsk = false } = {}) => {
  const cur = resolveAlarmRow(alarm)
  if (!cur?.id) return cur
  nextTick(() => {
    try {
      const hit = displayRows.value.find((r) => Number(r.id) === Number(cur.id))
      alarmTableRef.value?.setCurrentRow?.(hit || cur)
    } catch { /* ignore */ }
  })
  setPageContext({
    page: 'alarms',
    alarmId: cur.id,
    deviceId: cur.deviceId ?? null,
    title: cur.title || `告警 #${cur.id}`,
    deviceName: cur.deviceName || cur.deviceIp || '',
    scenario: cur.secondaryAlarm || cur.secondary ? 'secondary' : ''
  })
  const payload = {
    alarmId: cur.id,
    deviceId: cur.deviceId,
    title: cur.title || `告警 #${cur.id}`,
    deviceName: cur.deviceName || cur.deviceIp || '',
    source: 'alarm',
    scenario: cur.secondaryAlarm || cur.secondary ? 'GENERIC' : '',
    primaryToolLabel: '标准处置',
    recommendedTools: [
      { name: 'get_alarm_detail', label: '告警详情', needConfirm: false, args: { alarmId: cur.id } },
      { name: 'list_active_alarms_for_device', label: '查看设备活动告警', needConfirm: false, args: { deviceId: cur.deviceId } },
      { name: 'get_device_summary', label: '设备摘要', needConfirm: false, args: { deviceId: cur.deviceId } },
      { name: 'ping_check', label: '连通性探测', needConfirm: false, args: { deviceId: cur.deviceId } },
      { name: 'dispose_incident', label: '标准处置', needConfirm: true, args: { alarmId: cur.id, deviceId: cur.deviceId } }
    ].filter((t) => t.args.deviceId != null || t.args.alarmId != null)
  }
  if (autoAsk) {
    askOpsAssistant({
      ...payload,
      expand: false,
      autoAsk: true,
      autoAskQuestion: `诊断告警「${cur.title || cur.id}」，优先标准处置。`
    })
  } else {
    syncOpsAssistantFocus(payload)
  }
  return cur
}

const onRowClick = (row) => {
  focusAlarmRow(row, { autoAsk: false })
}

const onRowDblClick = (row) => showDetail(row)

const showDetail = async (alarm) => {
  const cur = focusAlarmRow(alarm, { autoAsk: false })
  if (!cur) return
  currentAlarm.value = cur
  showDetailPanel.value = true
  alarmAiopsCtx.value = null
  const id = cur?.id
  if (id) {
    try {
      alarmAiopsCtx.value = await aiopsApi.getAlarmContext(id)
    } catch {
      alarmAiopsCtx.value = null
    }
  }
}

const closeDetailPanel = () => {
  showDetailPanel.value = false
  currentAlarm.value = null
  alarmAiopsCtx.value = null
}

const parseDescField = (text, label) => {
  if (!text || !label) return ''
  const m = String(text).match(new RegExp(label + '[：:]\\s*([^\\n,;|}]+)'))
  return m ? m[1].trim() : ''
}

/** 从【摘要】提取一句话说明；其余作为详情正文（去掉重复计数尾巴） */
const alarmSummaryText = computed(() => {
  const d = currentAlarm.value?.description
  if (!d) return ''
  const m = String(d).match(/【摘要】\s*([^\n]+)/)
  if (m) return m[1].trim()
  // 旧格式：取第一段非空、非重复标记
  const first = String(d).split(/\n|;\s*/).map((s) => s.trim()).find((s) => s && !s.startsWith('[重复'))
  return first && first.length < 200 ? first : ''
})

const alarmDetailBody = computed(() => {
  let d = currentAlarm.value?.description
  if (!d) return ''
  d = String(d).replace(/\n?\[重复 \d+ 次[\s\S]*$/, '').trim()
  d = d.replace(/^【摘要】[^\n]*\n+/, '')
  return d.trim()
})

const gotoTopology = async (row, tab = 'alarms') => {
  if (!row?.deviceId) {
    ElMessage.warning('该告警未关联纳管设备，无法定位')
    return
  }
  try {
    await ElMessageBox.confirm('将打开拓扑视图并定位到该设备，是否继续？', '打开拓扑', {
      type: 'info', confirmButtonText: '前往', cancelButtonText: '取消'
    })
  } catch {
    return
  }
  router.push({
    name: 'Topology',
    query: { deviceId: String(row.deviceId), tab }
  })
}

const gotoAiopsAnalyze = async (row) => {
  try {
    await ElMessageBox.confirm('将打开智能运维工作台处置该告警，是否继续？', '打开工作台', {
      type: 'info', confirmButtonText: '前往', cancelButtonText: '取消'
    })
  } catch {
    return
  }
  router.push({
    path: '/aiops/workbench',
    query: {
      alarmId: row?.id != null ? String(row.id) : undefined,
      deviceId: row?.deviceId != null ? String(row.deviceId) : undefined
    }
  })
}

const askSmartAnalyze = (row) => {
  if (!row) return
  focusAlarmRow(row, { autoAsk: true })
}

const openTerminalDialog = () => {
  const alarm = currentAlarm.value
  if (!alarm?.deviceId) {
    ElMessage.warning('该告警未关联纳管设备，无法打开终端')
    return
  }
  if (!auth.hasPermission('webssh:connect')) {
    ElMessage.warning('无 WebSSH 权限')
    return
  }
  openWebTerminal({
    deviceId: Number(alarm.deviceId),
    deviceName: alarm.deviceName || alarm.deviceIp || `设备#${alarm.deviceId}`
  })
}

const onTerminalOpened = () => {
  // 布局稳定后再触发一次窗口 resize，便于 xterm fit
  nextTick(() => window.dispatchEvent(new Event('resize')))
}

const onTerminalClosed = () => {
  terminalDeviceId.value = null
  terminalDeviceName.value = ''
  terminalAlarm.value = null
}

const backToAlarmDetail = () => {
  const alarm = terminalAlarm.value || currentAlarm.value
  terminalDialogVisible.value = false
  if (alarm) {
    currentAlarm.value = alarm
    showDetailPanel.value = true
  }
}

const ackFromTerminal = () => {
  const alarm = terminalAlarm.value
  if (!alarm) return
  // 保持终端打开，弹出确认框；确认后刷新列表
  currentAlarm.value = alarm
  openAckDialog(alarm)
}

const clearFromTerminal = async () => {
  const alarm = terminalAlarm.value
  if (!alarm?.id) return
  await clearAlarm(alarm.id)
  if (terminalAlarm.value?.id === alarm.id) {
    terminalAlarm.value = { ...terminalAlarm.value, status: 'CLEARED', clearedAt: new Date().toISOString() }
  }
}

const openMonitorDialog = () => {
  const alarm = currentAlarm.value
  if (!alarm?.deviceId) {
    ElMessage.warning('该告警未关联纳管设备')
    return
  }
  monitorDeviceId.value = Number(alarm.deviceId)
  monitorDeviceName.value = alarm.deviceName || alarm.deviceIp || `设备#${alarm.deviceId}`
  monitorLatest.value = null
  monitorPorts.value = []
  monitorDialogVisible.value = true
}

const loadMonitorData = async () => {
  if (!monitorDeviceId.value) return
  monitorLoading.value = true
  try {
    const [latest, ports] = await Promise.all([
      performanceApi.getLatestPerformance(monitorDeviceId.value).catch(() => null),
      performanceApi.getLatestPortMetrics(monitorDeviceId.value).catch(() => [])
    ])
    monitorLatest.value = latest && latest.portIndex == null ? latest : null
    monitorPorts.value = Array.isArray(ports) ? ports.slice(0, 30) : []
  } catch (e) {
    monitorLatest.value = null
    monitorPorts.value = []
  } finally {
    monitorLoading.value = false
  }
}

const onMonitorClosed = () => {
  monitorDeviceId.value = null
  monitorDeviceName.value = ''
  monitorLatest.value = null
  monitorPorts.value = []
}

const goFullPerformance = async () => {
  const id = monitorDeviceId.value
  try {
    await ElMessageBox.confirm('将打开性能监控页查看该设备完整指标，是否继续？', '打开性能监控', {
      type: 'info', confirmButtonText: '前往', cancelButtonText: '取消'
    })
  } catch {
    return
  }
  monitorDialogVisible.value = false
  router.push({ name: 'Performance', query: id ? { deviceId: String(id) } : {} })
}

const formatMetricPct = (v) => (v == null || Number.isNaN(Number(v)) ? '-' : `${Number(v).toFixed(1)}%`)

const formatRateSimple = (rate) => {
  if (rate == null || rate === 0) return '-'
  if (rate > 1e9) return (rate / 1e9).toFixed(2) + ' Gbps'
  if (rate > 1e6) return (rate / 1e6).toFixed(2) + ' Mbps'
  if (rate > 1e3) return (rate / 1e3).toFixed(2) + ' Kbps'
  return Number(rate).toFixed(0) + ' bps'
}

const openAckDialog = (row) => {
  ackForm.value = {
    note: '',
    ids: [row.id],
    mode: 'single'
  }
  ackDialogAckClose.value = !!row.ackCloses
  ackDialogVisible.value = true
}

const openBatchAck = () => {
  const active = selectedAlarms.value.filter(a => a.status === 'ACTIVE')
  const ids = active.map(a => a.id)
  if (!ids.length) return
  ackForm.value = { note: '', ids, mode: 'batch' }
  ackDialogAckClose.value = active.length > 0 && active.every(a => a.ackCloses)
  ackDialogVisible.value = true
}

const ackSecondaryAlarms = async () => {
  try {
    await ElMessageBox.confirm(
      `确认批量处理全网连带/收敛噪音告警？当前页可见 ACTIVE 连带约 ${secondaryActiveCount.value} 条。阅知类将直接关闭，其余进入处理中。不会修改设备配置。`,
      '确认噪音告警',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  ackingSecondary.value = true
  try {
    const res = await aiopsApi.ackSecondary(null, true, null)
    if (res?.ok === false) {
      ElMessage.error(res.error || '确认失败')
    } else if ((res?.count ?? 0) === 0) {
      ElMessage.warning(res.message || '没有待确认的 ACTIVE 噪音告警')
      await refreshAll()
    } else {
      ElMessage.success(res.message || '关联告警已处理')
      await refreshAll()
    }
  } catch {
    ElMessage.error('确认失败')
  } finally {
    ackingSecondary.value = false
  }
}

const disposeCurrentAlarm = async () => {
  const a = currentAlarm.value
  if (!a?.id) return
  try {
    await ElMessageBox.confirm(
      '将真实执行：网管探测 → 处理关联告警（阅知类直接关闭）→ 连续可达确认后关闭，否则进入「处理中」。单次可达不算恢复。不会改设备配置。',
      '标准处置',
      { type: 'warning', confirmButtonText: '确认执行', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  disposingAlarm.value = true
  try {
    const res = await aiopsApi.disposeIncident(a.id, a.deviceId ?? null, true)
    if (res?.ok === false) {
      ElMessage.error(res.error || '处置失败')
      return
    }
    ElMessage.success(res?.message || '标准处置完成')
    await refreshAll()
    if (currentAlarm.value?.id === a.id) {
      const latest = (alarms.value || []).find(x => x.id === a.id)
      if (latest) {
        currentAlarm.value = { ...latest }
      } else {
        const st = String(res?.status || '').toUpperCase()
        currentAlarm.value = {
          ...currentAlarm.value,
          status: st || (res?.closed ? 'CLEARED' : 'ACKNOWLEDGED'),
          clearNote: res?.closeReason || currentAlarm.value.clearNote,
          clearedAt: res?.closed || st === 'CLEARED' ? new Date().toISOString() : currentAlarm.value.clearedAt,
          acknowledgedAt: currentAlarm.value.acknowledgedAt || new Date().toISOString()
        }
      }
    }
  } catch {
    ElMessage.error('标准处置失败')
  } finally {
    disposingAlarm.value = false
  }
}

const refreshCurrentAlarmDevice = async () => {
  const a = currentAlarm.value
  if (!a?.deviceId) return
  refreshingAlarmDevice.value = true
  try {
    const d = await deviceApi.refreshDevice(a.deviceId)
    ElMessage.success(`设备已刷新：${d?.name || a.deviceId} → ${d?.status || '-'}`)
  } catch {
    ElMessage.error('刷新设备失败')
  } finally {
    refreshingAlarmDevice.value = false
  }
}

const submitAck = async () => {
  try {
    const { note, ids, mode } = ackForm.value
    const asAckClose = ackDialogAckClose.value
    if (mode === 'batch') {
      await alarmApi.batchAcknowledge(ids, note || undefined)
      ElMessage.success(asAckClose
        ? `成功阅知关闭 ${ids.length} 条告警`
        : `成功确认 ${ids.length} 条告警`)
      selectedAlarms.value = []
    } else {
      const res = await alarmApi.acknowledgeAlarm(ids[0], note || undefined)
      const closed = res?.status === 'CLEARED' || asAckClose
      ElMessage.success(closed ? '已阅知关闭' : '告警已进入处理中')
      const nextStatus = closed ? 'CLEARED' : 'ACKNOWLEDGED'
      if (currentAlarm.value?.id === ids[0]) {
        currentAlarm.value.status = nextStatus
        currentAlarm.value.acknowledgedBy = auth.displayName
        currentAlarm.value.acknowledgeNote = note || null
        currentAlarm.value.acknowledgedAt = new Date().toISOString()
        if (closed) {
          currentAlarm.value.clearedAt = res?.clearedAt || new Date().toISOString()
          currentAlarm.value.clearNote = res?.clearNote || '阅知关闭'
        }
      }
      if (terminalAlarm.value?.id === ids[0]) {
        terminalAlarm.value = {
          ...terminalAlarm.value,
          status: nextStatus,
          acknowledgedBy: auth.displayName,
          acknowledgeNote: note || null,
          acknowledgedAt: new Date().toISOString(),
          ...(closed ? {
            clearedAt: res?.clearedAt || new Date().toISOString(),
            clearNote: res?.clearNote || '阅知关闭'
          } : {})
        }
      }
    }
    ackDialogVisible.value = false
    loadAlarms(mode === 'batch' ? false : true)
    loadStats(true)
  } catch (err) {
    ElMessage.error('确认失败')
  }
}

const batchClear = async () => {
  try {
    const ids = selectedAlarms.value.filter(a => a.status !== 'CLEARED').map(a => a.id)
    if (!ids.length) return
    await ElMessageBox.confirm(`确定要清除选中的 ${ids.length} 条告警吗?`, '提示', {
      type: 'warning'
    })
    await alarmApi.batchClear(ids)
    ElMessage.success(`成功清除 ${ids.length} 条告警`)
    selectedAlarms.value = []
    loadAlarms(false)
    loadStats(true)
  } catch (err) {
    if (err !== 'cancel') ElMessage.error('批量清除失败')
  }
}

const clearAlarm = async (id) => {
  try {
    await ElMessageBox.confirm('确定要清除此告警吗?', '提示', { type: 'warning' })
    await alarmApi.clearAlarm(id)
    ElMessage.success('告警已关闭')
    loadAlarms(true)
    loadStats(true)
    if (currentAlarm.value?.id === id) {
      currentAlarm.value.status = 'CLEARED'
      currentAlarm.value.clearedAt = new Date().toISOString()
    }
  } catch (err) {
    if (err !== 'cancel') ElMessage.error('清除告警失败')
  }
}

const deleteAlarm = async (id) => {
  try {
    await ElMessageBox.confirm('确定要删除此告警吗?', '提示', { type: 'warning' })
    await alarmApi.deleteAlarm(id)
    ElMessage.success('告警已删除')
    loadAlarms(false)
    loadStats(true)
    if (currentAlarm.value?.id === id) closeDetailPanel()
  } catch (err) {
    if (err !== 'cancel') ElMessage.error('删除告警失败')
  }
}

const batchDelete = async () => {
  try {
    const ids = selectedAlarms.value.map(a => a.id)
    await ElMessageBox.confirm(`确定要删除选中的 ${ids.length} 条告警吗?`, '提示', { type: 'danger' })
    await alarmApi.batchDelete(ids)
    ElMessage.success(`成功删除 ${ids.length} 条告警`)
    selectedAlarms.value = []
    closeDetailPanel()
    loadAlarms(false)
    loadStats(true)
  } catch (err) {
    if (err !== 'cancel') ElMessage.error('批量删除失败')
  }
}

const exportAlarms = async () => {
  exporting.value = true
  try {
    const params = { ...buildQueryParams() }
    delete params.page
    delete params.size
    delete params.sort
    const blob = await alarmApi.exportAlarms(params)
    if (!(blob instanceof Blob)) {
      ElMessage.error('导出失败')
      return
    }
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `alarms-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.csv`
    link.click()
    URL.revokeObjectURL(url)
    ElMessage.success('已按当前筛选导出（最多 2000 条）')
  } catch (err) {
    ElMessage.error('导出失败')
  } finally {
    exporting.value = false
  }
}

const isOverdue = (row) => {
  if (!row || row.status !== 'ACTIVE' || !row.occurredAt) return false
  return Date.now() - new Date(row.occurredAt).getTime() >= overdueMinutes * 60 * 1000
}

const formatDuration = (row) => {
  if (!row?.occurredAt) return '-'
  const start = new Date(row.occurredAt).getTime()
  const end = row.clearedAt ? new Date(row.clearedAt).getTime() : Date.now()
  let sec = Math.max(0, Math.floor((end - start) / 1000))
  const d = Math.floor(sec / 86400)
  sec %= 86400
  const h = Math.floor(sec / 3600)
  sec %= 3600
  const m = Math.floor(sec / 60)
  const s = sec % 60
  if (d > 0) return `${d}天${h}时`
  if (h > 0) return `${h}时${m}分`
  if (m > 0) return `${m}分${s}秒`
  return `${s}秒`
}

const getSeverityType = (severity) => {
  switch (severity) {
    case 'CRITICAL': return 'danger'
    case 'MAJOR':
    case 'WARNING': return 'warning'
    case 'MINOR': return 'info'
    case 'INFO': return 'info'
    default: return 'info'
  }
}

const getSeverityText = (severity) => {
  switch (severity) {
    case 'CRITICAL': return '严重'
    case 'MAJOR':
    case 'WARNING': return '重要'
    case 'MINOR': return '次要'
    case 'INFO': return '提示'
    default: return severity || '未知'
  }
}

/** WARNING 与 MAJOR 共用样式键 */
const severityCssKey = (severity) => {
  const s = String(severity || '').toLowerCase()
  return s === 'warning' ? 'major' : s
}

const getStatusType = (status) => {
  switch (status) {
    case 'ACTIVE': return 'danger'
    case 'ACKNOWLEDGED': return 'warning'
    case 'CLEARED': return 'success'
    default: return 'info'
  }
}

const getStatusText = (status) => {
  switch (status) {
    case 'ACTIVE': return '待处理'
    case 'ACKNOWLEDGED': return '处理中'
    case 'CLEARED': return '已关闭'
    default: return status || '未知'
  }
}

const ackHint = (row) => {
  const by = row?.acknowledgedBy || '未知'
  const note = row?.acknowledgeNote ? ` · ${row.acknowledgeNote}` : ''
  return `确认人：${by}${note}`
}

/** 从 rawData 解析 snmpTrapOID，纠偏历史「通用Trap告警」展示 */
const refineTrapTypeFromRaw = (rawData) => {
  if (!rawData) return null
  const m = String(rawData).match(/1\.3\.6\.1\.6\.3\.1\.1\.4\.1\.0\s*=\s*([0-9.]+)/i)
  const oid = m ? m[1] : null
  if (!oid) return null
  if (oid === '1.3.6.1.2.1.14.16.2.13' || oid.endsWith('.14.16.2.13')) {
    return '路由告警-OSPF-LSA-MaxAge'
  }
  if (oid === '1.3.6.1.2.1.14.16.2.12' || oid.endsWith('.14.16.2.12')) {
    return '路由告警-OSPF产生LSA'
  }
  if (oid === '1.3.6.1.2.1.14.16.2.2' || oid.endsWith('.14.16.2.2')) {
    return '路由告警-OSPF邻居状态变更'
  }
  if (oid === '1.3.6.1.2.1.14.16.2.16' || oid.endsWith('.14.16.2.16')) {
    return '路由告警-OSPF接口状态变更'
  }
  if (oid === '1.3.6.1.2.1.14.16.2.6' || oid.endsWith('.14.16.2.6')) {
    return '路由告警-OSPF认证失败'
  }
  if (oid.includes('1.3.6.1.2.1.14.16.2.')) {
    return '路由告警-OSPF'
  }
  if (oid === '1.3.6.1.6.3.1.1.5.3' || oid.endsWith('.1.1.5.3')) {
    return '链路告警-接口断开'
  }
  if (oid === '1.3.6.1.6.3.1.1.5.4' || oid.endsWith('.1.1.5.4')) {
    return '链路告警-接口恢复'
  }
  return null
}

const getTrapTypeText = (trapType, rawData) => {
  let type = trapType
  if (!type || type === '通用Trap告警' || type === '通用告警' || type === '设备告警') {
    type = refineTrapTypeFromRaw(rawData) || type
  }
  if (!type) return '-'
  switch (type) {
    case 'DEVICE_OFFLINE': return '设备离线'
    case 'linkDown':
    case '链路告警-接口断开':
    case '链路告警-LinkDown':
      return '链路断开'
    case 'linkUp':
    case '链路告警-接口恢复':
    case '链路告警-LinkUp':
      return '链路恢复'
    case 'coldStart':
    case '系统告警-冷启动':
      return '设备冷启动'
    case 'warmStart':
    case '系统告警-热启动':
      return '设备热启动'
    case 'authenticationFailure':
    case '安全告警-认证失败':
      return '认证失败'
    case '路由告警-OSPF-LSA-MaxAge':
      return 'OSPF LSA MaxAge'
    case '路由告警-OSPF产生LSA':
      return 'OSPF产生LSA'
    case '路由告警-OSPF邻居状态变更':
      return 'OSPF邻居变更'
    case '路由告警-OSPF接口状态变更':
      return 'OSPF接口变更'
    case '路由告警-OSPF认证失败':
      return 'OSPF认证失败'
    case '路由告警-OSPF':
      return 'OSPF路由事件'
    default: return type
  }
}

const formatDateTime = (dateStr) => {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

// 状态筛选由 checkbox-group @change → onStatusFilterChange 触发，不再 watch 以免重复请求

watch(searchKeyword, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    page.value = 1
    loadAlarms(false)
  }, 400)
})

onMounted(() => {
  setPageContext({ page: 'alarms' })
  const qKeyword = route.query.keyword != null ? String(route.query.keyword).trim() : ''
  if (qKeyword) {
    searchKeyword.value = qKeyword
  }
  const qDeviceId = route.query.deviceId
  if (qDeviceId != null && qDeviceId !== '') {
    const n = Number(qDeviceId)
    if (Number.isFinite(n)) deviceIdFilter.value = n
  }
  loadAlarms(false).then(() => {
    const qAlarmId = route.query.alarmId
    if (qAlarmId != null && qAlarmId !== '') {
      const hit = alarms.value.find((a) => String(a.id) === String(qAlarmId))
      if (hit) showDetail(hit)
      else {
        alarmApi.getAlarm(qAlarmId).then((a) => {
          if (a) showDetail(a)
        }).catch(() => {})
      }
    }
  })
  loadStats(false)
  alarmPoller.start()
  chartResizeHandler = () => trendChart && trendChart.resize()
  window.addEventListener('resize', chartResizeHandler)
})

watch(
  () => route.query.deviceId,
  (v) => {
    if (v == null || v === '') {
      if (deviceIdFilter.value != null) {
        deviceIdFilter.value = null
        page.value = 1
        loadAlarms(false)
      }
      return
    }
    const n = Number(v)
    if (!Number.isFinite(n) || n === deviceIdFilter.value) return
    deviceIdFilter.value = n
    page.value = 1
    loadAlarms(false)
  }
)

const clearDeviceIdFilter = () => {
  deviceIdFilter.value = null
  const q = { ...route.query }
  delete q.deviceId
  router.replace({ query: q })
  page.value = 1
  loadAlarms(false)
}

/** 助手工具结果回写本页 */
watch(
  () => pageSink.token,
  async (token) => {
    if (!token || !pageSink.last) return
    const { result, tool, source } = pageSink.last
    if (source === 'alarms') return
    if (!result || result.ok === false) return
    const detail = result.detail || {}
    const name = tool || result.tool || ''
    if (detail.navigate && detail.path) {
      if (source === 'assistant') return
      const aid = detail.alarmId != null ? Number(detail.alarmId) : Number(detail.query?.alarmId)
      if ((detail.path === '/alarms' || name === 'open_alarm_row') && Number.isFinite(aid)) {
        const hit = alarms.value.find((a) => Number(a.id) === aid)
        if (hit) {
          focusAlarmRow(hit, { autoAsk: false })
          return
        }
      }
      const { offerToolNavigate } = await import('@/composables/useSafeNavigate')
      await offerToolNavigate(router, route, {
        toolName: name,
        detail,
        onSamePage: async (d) => {
          const id = d.alarmId != null ? Number(d.alarmId) : Number(d.query?.alarmId)
          if (Number.isFinite(id)) {
            const hit = alarms.value.find((a) => Number(a.id) === id)
            if (hit) focusAlarmRow(hit, { autoAsk: false })
          }
        }
      })
      return
    }
    if (['inspect', 'refresh_device', 'refresh_offline', 'ack_noise', 'dispose_incident', 'ack_alarm', 'ping_check', 'probe_device'].includes(name)) {
      await refreshAll()
      const aid = Number(detail.alarmId || detail.id)
      if (Number.isFinite(aid)) {
        const hit = alarms.value.find((a) => Number(a.id) === aid)
        if (hit) focusAlarmRow(hit, { autoAsk: false })
      }
    }
  }
)

onUnmounted(() => {
  clearPageContext()
  alarmPoller.stop()
  if (searchTimer) clearTimeout(searchTimer)
  if (chartResizeHandler) window.removeEventListener('resize', chartResizeHandler)
  if (trendChart) {
    trendChart.dispose()
    trendChart = null
  }
})
</script>

<style scoped>
.alarm-panel-body {
  padding-top: 12px;
}

.alarm-work-split {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: start;
  min-height: 420px;
}
.alarm-work-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.alarm-work-side {
  /* OpsInlineShell 自身控制宽高 */
}
@media (max-width: 1100px) {
  .alarm-work-split {
    grid-template-columns: 1fr;
  }
}

.nms-page-title {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
}

.summary-row {
  display: flex;
  align-items: stretch;
  gap: 20px;
  margin-bottom: 14px;
  padding: 12px 14px;
  background: #fafbfc;
  border: 1px solid #eef0f3;
  border-radius: 4px;
}

.metrics {
  display: flex;
  align-items: center;
  gap: 20px;
  flex: 1;
  flex-wrap: wrap;
  min-width: 0;
}

.metric {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 64px;
  padding: 4px 8px;
  margin: -4px -8px;
  border-radius: 4px;
  border: 1px solid transparent;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.metric.is-clickable {
  cursor: pointer;
  user-select: none;
}

.metric.is-clickable:hover {
  background: #f0f2f5;
}

.metric.is-clickable.is-active {
  background: #ecf5ff;
  border-color: #b3d8ff;
}

.metric-value {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}

.metric-label {
  font-size: 12px;
  color: #909399;
}

.metric.is-danger .metric-value { color: #f56c6c; }
.metric.is-warning .metric-value { color: #e6a23c; }
.metric.is-muted .metric-value { color: #606266; font-size: 18px; }

.metric-divider {
  width: 1px;
  align-self: stretch;
  background: #e4e7ed;
  margin: 0 4px;
}

.trend-panel {
  width: 280px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.trend-caption {
  font-size: 12px;
  color: #909399;
  margin-bottom: 2px;
}

.trend-chart {
  height: 56px;
  width: 100%;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: nowrap;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid #eef0f3;
  overflow-x: auto;
}

.sev-group {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: nowrap;
  flex-shrink: 0;
}

.sev-chip {
  display: inline-flex;
  align-items: center;
  height: 28px;
  padding: 0 8px;
  font-size: 12px;
  color: #606266;
  background: #fff;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}

.sev-chip:hover {
  border-color: #c0c4cc;
  color: #303133;
}

.sev-chip.active {
  background: #ecf5ff;
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
  font-weight: 500;
}

.sev-chip-critical {
  border-left: 3px solid #f56c6c;
}
.sev-chip-major {
  border-left: 3px solid #e6a23c;
}
.sev-chip-minor {
  border-left: 3px solid var(--el-color-primary);
}
.sev-chip-info {
  border-left: 3px solid #909399;
}
.sev-chip-critical.active {
  background: #fef0f0;
  border-color: #f56c6c;
  color: #f56c6c;
}
.sev-chip-major.active {
  background: #fdf6ec;
  border-color: #e6a23c;
  color: #e6a23c;
}
.sev-chip-minor.active {
  background: #ecf5ff;
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}
.sev-chip-info.active {
  background: #f4f4f5;
  border-color: #909399;
  color: #606266;
}

.filter-divider {
  height: 20px;
  margin: 0;
  flex-shrink: 0;
}

.status-group {
  display: flex;
  gap: 0;
  flex-shrink: 0;
  white-space: nowrap;
}

.status-group :deep(.el-checkbox) {
  margin-right: 10px;
  height: 28px;
}

.status-group :deep(.el-checkbox:last-child) {
  margin-right: 0;
}

.time-group {
  flex-shrink: 0;
}

.custom-time-btn {
  flex-shrink: 0;
  max-width: 220px;
  --el-button-bg-color: #fff;
  --el-button-border-color: #dcdfe6;
  --el-button-text-color: #606266;
  --el-button-hover-bg-color: #ecf5ff;
  --el-button-hover-border-color: #c6e2ff;
  --el-button-hover-text-color: var(--el-color-primary);
}

.custom-time-btn :deep(span) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
}

.custom-time-btn-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.custom-time-clear-icon {
  flex-shrink: 0;
  font-size: 14px;
  color: #909399;
  border-radius: 50%;
}

.custom-time-clear-icon:hover {
  color: #f56c6c;
}

.custom-time-btn.is-active-range {
  --el-button-bg-color: #ecf5ff;
  --el-button-border-color: var(--el-color-primary);
  --el-button-text-color: var(--el-color-primary);
  --el-button-hover-bg-color: #ecf5ff;
  --el-button-hover-border-color: var(--el-color-primary);
  --el-button-hover-text-color: var(--el-color-primary);
}

.custom-time-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin: -4px 0 12px;
  padding: 8px 10px;
  background: #f5f7fa;
  border-radius: 6px;
}

.custom-time-row-label {
  font-size: 12px;
  color: #909399;
  flex-shrink: 0;
}

.custom-time-row-sep {
  font-size: 12px;
  color: #909399;
}

.custom-time-row :deep(.el-date-editor) {
  width: 188px;
}

.search-input {
  width: 168px;
  flex-shrink: 0;
  margin-left: 0;
}

.device-filter-tag {
  flex-shrink: 0;
}

/* 按告警级别铺背景色（无左侧色条） */
:deep(.el-table__body tr.sev-critical > td) {
  background-color: #fef0f0 !important;
}
:deep(.el-table__body tr.sev-major > td),
:deep(.el-table__body tr.sev-warning > td) {
  background-color: #fdf6ec !important;
}
:deep(.el-table__body tr.sev-minor > td) {
  background-color: #ecf5ff !important;
}
:deep(.el-table__body tr.sev-info > td) {
  background-color: #f4f4f5 !important;
}

:deep(.el-table__body tr.sev-critical.row-cleared > td),
:deep(.el-table__body tr.sev-major.row-cleared > td),
:deep(.el-table__body tr.sev-warning.row-cleared > td),
:deep(.el-table__body tr.sev-minor.row-cleared > td),
:deep(.el-table__body tr.sev-info.row-cleared > td) {
  opacity: 0.75;
}

:deep(.el-table__body tr:hover > td) {
  filter: brightness(0.98);
}

:deep(.el-table .row-overdue .overdue) {
  color: #f56c6c;
  font-weight: 600;
}

.alarm-title-cell.sev-text-critical {
  color: #c45656;
  font-weight: 600;
}
.alarm-title-cell.sev-text-major,
.alarm-title-cell.sev-text-warning {
  color: #b88230;
  font-weight: 500;
}
.alarm-title-cell.sev-text-minor {
  color: #303133;
}
.alarm-title-cell.sev-text-info {
  color: #606266;
}

.status-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #606266;
}
.status-cell .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #c0c4cc;
}
.status-cell.is-active .dot { background: #f56c6c; }
.status-cell.is-acknowledged .dot { background: #e6a23c; }
.status-cell.is-cleared .dot { background: #67c23a; }

.storm-tag { margin-left: 6px; vertical-align: middle; }
.storm-expand { padding: 8px 12px 12px 36px; }
.storm-expand-title {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
}
.storm-expand.muted {
  padding: 8px 36px;
  color: #c0c4cc;
  font-size: 12px;
}

.ip-text { font-variant-numeric: tabular-nums; color: #606266; }
.ip-text.linkable { color: var(--el-color-primary); cursor: pointer; }
.ip-text.linkable:hover { text-decoration: underline; }
.text-muted { color: #c0c4cc; }
.overdue { color: #f56c6c; }

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #eef0f3;
}

.detail-body { max-height: 62vh; overflow-y: auto; }
.aiops-ctx-block {
  margin: 12px 0 16px;
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}
.aiops-ctx-block h4 { margin: 0 0 8px; font-size: 14px; }
.aiops-ctx-tip { margin: 0 0 8px; font-size: 13px; }
.aiops-ctx-tip.muted { color: var(--el-text-color-secondary); }
.detail-top {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.detail-duration { font-size: 13px; color: #909399; margin-left: 4px; }
.detail-title {
  margin: 0 0 16px;
  font-size: 17px;
  font-weight: 600;
  color: #303133;
}
.life-steps { margin-bottom: 20px; }

.playbook {
  margin-bottom: 16px;
  padding: 12px 14px;
  background: #f5f7fa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}
.playbook-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}
.playbook-list {
  margin: 0 0 12px;
  padding-left: 18px;
  color: #606266;
  font-size: 13px;
  line-height: 1.7;
}
.playbook-actions { display: flex; gap: 8px; flex-wrap: wrap; }

.detail-desc { margin-bottom: 12px; }
.alarm-summary-box {
  margin: 0 0 12px;
  padding: 12px 14px;
  background: #f0f7ff;
  border: 1px solid #d6e8ff;
  border-radius: 6px;
}
.alarm-summary-label {
  font-size: 12px;
  color: var(--el-color-primary);
  font-weight: 600;
  margin-bottom: 6px;
}
.alarm-summary-text {
  margin: 0;
  font-size: 14px;
  color: #303133;
  line-height: 1.65;
}
.adv-collapse { border: none; }
.block-pre {
  margin: 0;
  padding: 10px 12px;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  font-size: 13px;
  color: #606266;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.5;
}
.block-pre.desc-pre { line-height: 1.65; }
.block-pre.mono {
  font-family: Consolas, 'Courier New', monospace;
  font-size: 12px;
}

.alarm-terminal-wrap {
  height: calc(78vh - 120px);
  min-height: 420px;
  border: 1px solid #222;
  border-radius: 6px;
  overflow: hidden;
  background: #000;
}

.alarm-terminal-wrap :deep(.xterm-terminal-container),
.alarm-terminal-wrap :deep(.terminal-body) {
  height: 100%;
}

.term-dlg-header {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-right: 28px;
}

.term-dlg-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.term-dlg-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.term-dlg-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}

.term-dlg-meta .meta-item {
  max-width: 420px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.term-dlg-meta .meta-item.mono {
  font-family: Consolas, 'Courier New', monospace;
}

.term-dlg-meta .meta-sep {
  opacity: 0.5;
}

.term-dlg-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  width: 100%;
}

.term-dlg-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.term-dlg-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-left: auto;
}

@media (max-width: 1100px) {
  .summary-row {
    flex-direction: column;
  }
  .trend-panel {
    width: 100%;
  }
  .trend-chart {
    height: 72px;
  }
  .search-input {
    width: 148px;
  }
  .metric-divider {
    display: none;
  }
}
</style>

<style>
/* append-to-body 后需非 scoped 才能命中弹层 */
.alarm-terminal-dialog {
  max-width: 1280px !important;
}
.alarm-terminal-dialog .el-dialog__body {
  padding-top: 8px;
  padding-bottom: 8px;
}
.alarm-terminal-dialog .el-dialog__footer {
  padding-top: 10px;
}

.mon-card {
  background: #f5f7fa;
  border-radius: 8px;
  padding: 14px 12px;
  text-align: center;
  margin-bottom: 12px;
}
.mon-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}
.mon-value {
  font-size: 22px;
  font-weight: 600;
  color: #303133;
}
.mon-ports-title {
  margin: 8px 0 10px;
  font-size: 14px;
  font-weight: 500;
}
</style>
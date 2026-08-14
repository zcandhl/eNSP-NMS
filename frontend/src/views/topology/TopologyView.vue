<template>
  <div class="topology-container">
    <header class="topo-chrome">
      <div class="topo-cmd">
        <div class="topo-cmd-left">
          <div v-if="auth.hasPermission('topology:write')" class="topo-seg" role="group" aria-label="视图模式">
            <button
              type="button"
              class="topo-seg-btn"
              :class="{ active: viewMode === 'monitor' }"
              @click="setViewMode('monitor')"
            >监视</button>
            <button
              type="button"
              class="topo-seg-btn"
              :class="{ active: viewMode === 'edit' }"
              @click="setViewMode('edit')"
            >编辑</button>
          </div>

          <span class="topo-vsep" />

          <div class="topo-actions">
            <button
              v-if="auth.hasPermission('topology:write')"
              type="button"
              class="topo-action"
              :class="{ busy: discovering }"
              :disabled="discovering"
              @click="handleDiscover"
            >
              <el-icon><Connection /></el-icon>
              <span>扫描链路</span>
            </button>
            <button
              v-if="isEditMode && auth.hasPermission('topology:write')"
              type="button"
              class="topo-action"
              @click="showLinkDialog"
            >
              <el-icon><Plus /></el-icon>
              <span>链路</span>
            </button>
            <button
              type="button"
              class="topo-action"
              :class="{ active: pathPickMode }"
              @click="togglePathPickMode"
            >
              <span>路径</span>
            </button>
            <button
              type="button"
              class="topo-action icon-only"
              :disabled="refreshingTopology"
              title="刷新状态"
              @click="handleRefresh"
            >
              <el-icon :class="{ 'is-loading': refreshingTopology }"><Refresh /></el-icon>
            </button>
          </div>

          <template v-if="isEditMode && auth.hasPermission('topology:write')">
            <span class="topo-vsep" />
            <div class="topo-layout-btns">
              <button
                type="button"
                class="topo-icon-btn"
                :class="{ active: currentLayout === 'force' }"
                title="力导向布局"
                @click="changeLayout('force')"
              ><el-icon><Share /></el-icon></button>
              <button
                type="button"
                class="topo-icon-btn"
                :class="{ active: currentLayout === 'hierarchy' }"
                title="层级布局"
                @click="changeLayout('hierarchy')"
              ><el-icon><Menu /></el-icon></button>
              <button
                type="button"
                class="topo-icon-btn"
                :class="{ active: currentLayout === 'grid' }"
                title="网格布局"
                @click="changeLayout('grid')"
              ><el-icon><Grid /></el-icon></button>
            </div>
          </template>
        </div>

        <div class="topo-cmd-right">
          <div class="topo-search">
            <el-icon class="topo-search-icon"><Search /></el-icon>
            <input
              v-model="searchText"
              type="search"
              placeholder="设备名 / IP"
              @input="handleSearch"
            />
          </div>
          <button
            type="button"
            class="topo-icon-btn"
            :title="sidebarCollapsed ? '展开侧栏' : '收起侧栏'"
            @click="sidebarCollapsed = !sidebarCollapsed"
          >
            <el-icon><component :is="sidebarCollapsed ? DArrowLeft : DArrowRight" /></el-icon>
          </button>
          <el-dropdown trigger="click" @command="handleExport">
            <button type="button" class="topo-icon-btn" title="导出 / 导入">
              <el-icon><Download /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="png">导出 PNG</el-dropdown-item>
                <el-dropdown-item command="json">导出数据</el-dropdown-item>
                <el-dropdown-item command="import" divided>导入布局 JSON</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <input
            ref="layoutImportInput"
            type="file"
            accept="application/json,.json"
            class="layout-import-input"
            @change="onLayoutImportFile"
          />
        </div>
      </div>

      <div class="topo-status">
        <div class="topo-metrics" role="toolbar" aria-label="拓扑筛选">
          <button
            type="button"
            class="topo-metric"
            :class="{ active: canvasFilter === 'all' }"
            @click="setCanvasFilter('all')"
          >
            <span class="topo-metric-label">设备</span>
            <span class="topo-metric-value">{{ devices.length }}</span>
          </button>
          <button
            type="button"
            class="topo-metric is-online"
            :class="{ active: canvasFilter === 'online' }"
            @click="setCanvasFilter('online')"
          >
            <span class="topo-metric-dot on" />
            <span class="topo-metric-label">在线</span>
            <span class="topo-metric-value">{{ onlineCount }}</span>
          </button>
          <button
            type="button"
            class="topo-metric is-offline"
            :class="{ active: canvasFilter === 'offline' }"
            @click="setCanvasFilter('offline')"
          >
            <span class="topo-metric-dot off" />
            <span class="topo-metric-label">离线</span>
            <span class="topo-metric-value">{{ offlineCount }}</span>
          </button>
          <button
            type="button"
            class="topo-metric is-alarm"
            :class="{ active: canvasFilter === 'alarm' }"
            @click="setCanvasFilter('alarm')"
          >
            <span class="topo-metric-label">告警</span>
            <span class="topo-metric-value" :class="{ warn: alertDeviceCount > 0 }">{{ alertDeviceCount }}</span>
          </button>
          <div class="topo-metric readonly">
            <span class="topo-metric-label">链路</span>
            <span class="topo-metric-value">{{ visibleLinkCount }}</span>
          </div>
        </div>

        <div class="topo-status-right">
          <label class="topo-field">
            <span>类型</span>
            <select v-model="typeFilter" @change="onCanvasFilterChange">
              <option value="">全部</option>
              <option value="router">路由器</option>
              <option value="switch">交换机</option>
              <option value="firewall">防火墙</option>
              <option value="ac">AC</option>
              <option value="ap">AP</option>
              <option value="server">服务器</option>
              <option value="pc">虚拟PC</option>
            </select>
          </label>
          <label class="topo-field">
            <span>刷新</span>
            <select :value="refreshPolicyValue" @change="onRefreshPolicyChange">
              <option value="off">手动</option>
              <option value="15">15 秒</option>
              <option value="30">30 秒</option>
              <option value="60">60 秒</option>
              <option value="300">5 分钟</option>
            </select>
          </label>
          <label
            v-if="auth.hasPermission('topology:write')"
            class="topo-field topo-check"
            title="定时重新扫描邻居并写入新链路（与「刷新状态」不同）"
          >
            <input v-model="autoDiscoverEnabled" type="checkbox" @change="onAutoDiscoverToggle" />
            <span>自动扫描</span>
          </label>
          <span v-if="autoRefreshEnabled" class="topo-countdown">{{ nextRefreshIn }}s</span>
        </div>
      </div>
    </header>

    <div class="topology-content">
      <div class="topology-canvas-wrapper">
        <div
          ref="graphContainer"
          class="topology-canvas"
          :class="{ 'is-monitor': isMonitorMode, 'is-edit': isEditMode }"
        ></div>

        <div v-if="topologyLoading" class="topo-overlay">
          <el-icon class="is-loading overlay-icon"><Loading /></el-icon>
          <span>正在加载拓扑...</span>
        </div>

        <div v-else-if="topologyLoadError" class="topo-overlay">
          <el-icon class="overlay-icon"><Warning /></el-icon>
          <span>{{ topologyLoadError }}</span>
          <el-button type="primary" size="small" @click="retryLoadTopology">重试</el-button>
        </div>

        <div v-else-if="!topologyLoading && devices.length === 0" class="topo-overlay">
          <el-empty description="暂无设备，请先在设备管理中添加">
            <el-button
              v-if="auth.hasPermission('devices:write')"
              type="primary"
              size="small"
              @click="goToDevices"
            >去添加设备</el-button>
          </el-empty>
        </div>

        <div v-else-if="!topologyLoading && visibleDevices.length === 0" class="topo-overlay soft">
          <el-empty description="当前筛选下无节点，请调整筛选条件">
            <el-button size="small" type="primary" @click="setCanvasFilter('all')">显示全部</el-button>
          </el-empty>
        </div>

        <div
          v-if="!topologyLoading && devices.length > 0 && links.length === 0 && !discovering"
          class="topo-link-hint"
        >
          <span>尚未发现链路。「刷新」只更新状态；需扫描邻居或手动添加。</span>
          <el-button
            v-if="auth.hasPermission('topology:write')"
            type="primary"
            size="small"
            @click="quickAutoDiscover"
          >立即扫描</el-button>
        </div>

        <div v-if="selectedDevice && !pathPickMode" class="selection-chip">
          <img :src="getDeviceIcon(selectedDevice)" class="selection-chip-icon" alt="" />
          <div class="selection-chip-body">
            <div class="selection-chip-label">当前选中</div>
            <div class="selection-chip-name">{{ selectedDevice.name }}</div>
            <div class="selection-chip-meta">
              <span>{{ selectedDevice.ipAddress || '-' }}</span>
              <el-tag
                :type="selectedDevice.status === 'online' ? 'success' : 'info'"
                size="small"
                effect="plain"
              >{{ selectedDevice.status === 'online' ? '在线' : '离线' }}</el-tag>
            </div>
          </div>
          <div class="selection-chip-actions">
            <el-button
              v-if="auth.hasAnyPermission('aiops:read', 'alarms:read')"
              size="small"
              type="primary"
              link
              @click="askExplainNode"
            >解释此节点</el-button>
            <el-button size="small" type="primary" link @click="focusSelectedOnCanvas">定位</el-button>
            <el-button size="small" link @click="clearDeviceSelection">取消</el-button>
          </div>
        </div>

        <div v-if="pathPickMode || pathHighlight.nodes.length" class="selection-chip path-chip">
          <div class="selection-chip-body">
            <div class="selection-chip-label">{{ pathPickMode ? '路径选择' : '路径高亮' }}</div>
            <div class="selection-chip-name">
              {{ pathEndpointLabel(pathEndpoints[0]) || '点击起点' }}
              <span class="path-arrow">→</span>
              {{ pathEndpointLabel(pathEndpoints[1]) || (pathEndpoints[0] ? '点击终点' : '—') }}
            </div>
            <div v-if="pathHighlight.nodes.length" class="selection-chip-meta">
              {{ pathHighlight.nodes.length }} 跳 · {{ pathHighlight.edges.length }} 条链路
            </div>
          </div>
          <div class="selection-chip-actions">
            <el-button
              v-if="auth.hasAnyPermission('aiops:read', 'alarms:read') && pathEndpoints[0] && pathEndpoints[1]"
              size="small"
              type="primary"
              link
              @click="askExplainPath"
            >解释此路径</el-button>
            <el-button size="small" link @click="clearPathHighlight">清除</el-button>
          </div>
        </div>

        <div
          v-if="edgeHoverTip.visible"
          class="edge-hover-tip"
          :style="{ left: edgeHoverTip.x + 'px', top: edgeHoverTip.y + 'px' }"
        >
          {{ edgeHoverTip.text }}
        </div>

        <div v-if="discovering" class="topo-overlay discover-overlay">
          <el-icon class="is-loading overlay-icon"><Loading /></el-icon>
          <span>{{ discoverProgress.stage }}</span>
          <el-progress
            :percentage="discoverProgress.percent"
            :stroke-width="8"
            style="width: 220px; margin-top: 12px"
          />
          <span class="discover-elapsed">已用时 {{ discoverProgress.elapsed }}s</span>
          <el-button size="small" style="margin-top: 12px" @click="cancelDiscover">取消请求</el-button>
        </div>

        <div class="zoom-control">
          <span class="zoom-label">{{ Math.round(zoomLevel * 100) }}%</span>
          <el-button-group>
            <el-button size="small" @click="zoomOut">
              <el-icon><ZoomOut /></el-icon>
            </el-button>
            <el-button size="small" @click="fitView">
              <el-icon><FullScreen /></el-icon>
            </el-button>
            <el-button size="small" @click="zoomIn">
              <el-icon><ZoomIn /></el-icon>
            </el-button>
          </el-button-group>
        </div>
      </div>

      <div class="topology-sidebar" :class="{ collapsed: sidebarCollapsed }">
        <el-card class="sidebar-card device-card" shadow="never">
          <template #header>
            <div class="card-header">
              <el-icon><List /></el-icon>
              <span>设备</span>
              <span class="device-count">{{ filteredDevices.length }}/{{ visibleDevices.length }}</span>
            </div>
          </template>
          <div class="device-list">
            <el-scrollbar>
              <div
                v-for="device in filteredDevices"
                :key="device.id"
                :ref="(el) => setDeviceItemRef(device.id, el)"
                class="device-item"
                :class="{ 'active': String(selectedDevice?.id) === String(device.id) }"
                @click="selectDevice(device)"
              >
                <img :src="getDeviceIcon(device)" class="device-icon-small" />
                <div class="device-info">
                  <div class="device-name">{{ device.name }}</div>
                  <div class="device-ip">{{ device.ipAddress }}</div>
                </div>
                <div class="device-item-right">
                  <span
                    class="status-dot-inline"
                    :class="device.status === 'online' ? 'on' : 'off'"
                  />
                  <el-badge
                    v-if="(device.alertCount || 0) > 0"
                    :value="device.alertCount > 9 ? '9+' : device.alertCount"
                    :type="device.alertSevere ? 'danger' : 'warning'"
                    class="alert-badge-inline"
                  />
                </div>
              </div>
              <el-empty
                v-if="filteredDevices.length === 0"
                :description="searchText ? '无匹配设备' : '暂无设备'"
                :image-size="56"
              />
            </el-scrollbar>
          </div>
        </el-card>

        <el-card class="sidebar-card legend-card" shadow="never">
          <template #header>
            <div class="card-header">
              <el-icon><View /></el-icon>
              <span>图例</span>
            </div>
          </template>
          <div class="legend-compact">
            <div class="legend-row">
              <span class="legend-line online" />正常 / 低负载
            </div>
            <div class="legend-row">
              <span class="legend-line busy" />利用率 ≥50%
            </div>
            <div class="legend-row">
              <span class="legend-line congested" />利用率 ≥80% / 端口 down
            </div>
            <div class="legend-row">
              <span class="legend-line device-offline" />设备离线
            </div>
            <div class="legend-row">
              <span class="legend-line offline" />链路管理 down
            </div>
            <div class="legend-row">
              <span class="legend-alert" />节点告警
            </div>
            <div class="legend-row">
              <span class="legend-selected" />选中 / 路径高亮
            </div>
          </div>
        </el-card>
      </div>
    </div>

    <!-- 设备运维枢纽抽屉 -->
    <el-drawer
      v-model="deviceDrawerVisible"
      :title="selectedDevice ? `运维枢纽 · ${selectedDevice.name}` : '运维枢纽'"
      size="520px"
      class="hub-drawer"
      destroy-on-close
    >
      <div v-if="selectedDevice" class="device-hub">
        <div class="hub-summary">
          <el-tag :type="selectedDevice.status === 'online' ? 'success' : 'danger'" size="small">
            {{ selectedDevice.status === 'online' ? '在线' : '离线' }}
          </el-tag>
          <span class="hub-ip">{{ selectedDevice.ipAddress }}</span>
          <el-tag size="small" type="info">{{ getDeviceTypeLabel(selectedDevice) }}</el-tag>
          <el-button
            v-if="auth.hasAnyPermission('aiops:read', 'alarms:read')"
            type="primary"
            link
            size="small"
            @click="askExplainNode"
          >解释此节点</el-button>
          <el-button
            v-if="auth.hasPermission('webssh:connect') && deviceCaps(selectedDevice).webssh"
            type="primary"
            link
            size="small"
            @click="openHubTerminal"
          >Web终端</el-button>
          <el-button
            v-if="isEditMode && auth.hasPermission('devices:write')"
            type="danger"
            link
            size="small"
            class="hub-delete"
            @click="deleteDevice"
          >删除设备</el-button>
        </div>

        <el-tabs v-model="hubTab" class="hub-tabs" @tab-change="onHubTabChange">
          <!-- 监视 -->
          <el-tab-pane
            v-if="auth.hasPermission('performance:read') && deviceCaps(selectedDevice).performance"
            label="监视"
            name="monitor"
          >
            <div class="hub-pane">
              <div class="hub-pane-actions">
                <el-button type="success" size="small" :loading="refreshing" @click="refreshDeviceStatus">
                  <el-icon><Refresh /></el-icon>
                  刷新性能
                </el-button>
              </div>
              <div v-if="selectedDeviceStatus" class="status-info">
                <el-descriptions :column="1" border size="small">
                  <el-descriptions-item label="CPU">
                    <el-progress
                      :percentage="Math.round(selectedDeviceStatus.cpuUsage || 0)"
                      :color="getCpuColor(selectedDeviceStatus.cpuUsage)"
                    />
                  </el-descriptions-item>
                  <el-descriptions-item label="内存">
                    <el-progress
                      :percentage="Math.round(selectedDeviceStatus.memoryUsage || 0)"
                      :color="getMemoryColor(selectedDeviceStatus.memoryUsage)"
                    />
                  </el-descriptions-item>
                  <el-descriptions-item label="温度">
                    <span v-if="selectedDeviceStatus.temperature">
                      {{ selectedDeviceStatus.temperature.toFixed(1) }} °C
                    </span>
                    <span v-else style="color: #909399">-</span>
                  </el-descriptions-item>
                  <el-descriptions-item label="更新时间">
                    {{ selectedDeviceStatus.timestamp || '-' }}
                  </el-descriptions-item>
                </el-descriptions>
              </div>
              <el-empty v-else description="暂无性能数据" :image-size="56" />

              <h4 class="hub-section-title">端口 ({{ devicePorts.length }})</h4>
              <el-table v-if="devicePorts.length" :data="devicePorts" size="small" border max-height="220">
                <el-table-column prop="portName" label="端口" min-width="100" />
                <el-table-column label="状态" width="70">
                  <template #default="{ row }">
                    <el-tag :type="row.operStatus === 'up' ? 'success' : 'info'" size="small">
                      {{ row.operStatus === 'up' ? 'UP' : 'DOWN' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="入/出 Mbps" min-width="110">
                  <template #default="{ row }">
                    {{ row.ifInRate ? (row.ifInRate / 1e6).toFixed(2) : '-' }} /
                    {{ row.ifOutRate ? (row.ifOutRate / 1e6).toFixed(2) : '-' }}
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-else description="暂无端口数据" :image-size="48" />
            </div>
          </el-tab-pane>

          <!-- 诊断 -->
          <el-tab-pane v-if="auth.hasPermission('devices:read')" label="诊断" name="diagnose">
            <div class="hub-pane">
              <div class="hub-pane-actions">
                <el-button
                  v-if="auth.hasPermission('devices:write') && deviceCaps(selectedDevice).snmp"
                  type="primary"
                  size="small"
                  :loading="refreshing"
                  @click="runDiagnoseRefresh"
                >
                  <el-icon><Refresh /></el-icon>
                  SNMP 刷新
                </el-button>
                <el-button
                  v-if="auth.hasPermission('devices:write')"
                  size="small"
                  @click="pingDevice"
                >
                  <el-icon><Connection /></el-icon>
                  连通性测试
                </el-button>
              </div>
              <el-descriptions :column="1" border size="small">
                <el-descriptions-item label="设备型号">{{ selectedDevice.model || '-' }}</el-descriptions-item>
                <el-descriptions-item label="厂商">{{ selectedDevice.vendor || '-' }}</el-descriptions-item>
                <el-descriptions-item label="SNMP 端口">{{ selectedDevice.snmpPort || 161 }}</el-descriptions-item>
                <el-descriptions-item label="描述">{{ selectedDevice.description || '-' }}</el-descriptions-item>
              </el-descriptions>

              <h4 class="hub-section-title">连通性</h4>
              <el-descriptions :column="1" border size="small">
                <el-descriptions-item label="状态">
                  <el-tag v-if="pingResult.status" :type="pingResult.reachable ? 'success' : 'danger'">
                    {{ pingResult.reachable ? '可连通' : '不可达' }}
                  </el-tag>
                  <span v-else style="color: #909399">未测试</span>
                </el-descriptions-item>
                <el-descriptions-item v-if="pingResult.status" label="响应">
                  {{ pingResult.responseTime }} ms
                </el-descriptions-item>
                <el-descriptions-item v-if="pingResult.status" label="时间">
                  {{ pingResult.testedAt }}
                </el-descriptions-item>
                <el-descriptions-item v-if="pingResult.message" label="提示">
                  {{ pingResult.message }}
                </el-descriptions-item>
              </el-descriptions>

              <h4 class="hub-section-title">一跳邻居 ({{ deviceNeighbors.length }})</h4>
              <el-table v-if="deviceNeighbors.length" :data="deviceNeighbors" size="small" border max-height="180">
                <el-table-column label="对端" min-width="100">
                  <template #default="{ row }">
                    <el-button type="primary" link @click="selectDeviceById(row.neighborNodeId)">
                      {{ getDeviceName(row.neighborNodeId) }}
                    </el-button>
                  </template>
                </el-table-column>
                <el-table-column prop="localPort" label="本端" width="90" />
                <el-table-column prop="remotePort" label="对端口" width="90" />
                <el-table-column label="状态" width="80">
                  <template #default="{ row }">
                    <el-tag :type="row.status === 'up' ? 'success' : 'danger'" size="small">
                      {{ row.status || '-' }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-else description="暂无邻居链路" :image-size="48" />
            </div>
          </el-tab-pane>

          <!-- 告警 -->
          <el-tab-pane v-if="auth.hasPermission('alarms:read')" label="告警" name="alarms">
            <div class="hub-pane">
              <div class="hub-pane-actions">
                <el-button size="small" :loading="deviceAlarmsLoading" @click="reloadDeviceAlarms">刷新告警</el-button>
                <el-button size="small" type="primary" link @click="gotoAlarmManage">告警管理</el-button>
              </div>
              <el-empty v-if="!deviceAlarmsLoading && !activeDeviceAlarms.length" description="无未清除告警（与告警管理口径一致）" :image-size="56" />
              <el-table v-else-if="activeDeviceAlarms.length" :data="activeDeviceAlarms" size="small" border stripe max-height="420" v-loading="deviceAlarmsLoading">
                <el-table-column label="级别" width="88">
                  <template #default="{ row }">
                    <el-tag
                      :type="row.severity === 'CRITICAL' || row.severity === 'MAJOR' ? 'danger' :
                             row.severity === 'MINOR' || row.severity === 'WARNING' ? 'warning' : 'info'"
                      size="small"
                    >
                      {{ row.severity }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="88">
                  <template #default="{ row }">
                    {{ String(row.status || '').toUpperCase() === 'ACKNOWLEDGED' ? '处理中' : '待处理' }}
                  </template>
                </el-table-column>
                <el-table-column prop="title" label="信息" min-width="120" show-overflow-tooltip />
                <el-table-column label="操作" width="130" fixed="right">
                  <template #default="{ row }">
                    <el-button
                      v-if="String(row.status).toUpperCase() === 'ACTIVE' && auth.hasPermission('alarms:handle')"
                      type="primary"
                      link
                      size="small"
                      @click="ackAlarm(row)"
                    >确认</el-button>
                    <el-button
                      v-if="auth.hasPermission('alarms:handle')"
                      type="danger"
                      link
                      size="small"
                      @click="clearAlarm(row)"
                    >清除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </el-tab-pane>

          <!-- 变更：仅拓扑写 / 配置写可见 -->
          <el-tab-pane v-if="canOpenChangeTab" label="变更" name="change">
            <div class="hub-pane">
              <template v-if="auth.hasPermission('topology:write') && deviceCaps(selectedDevice).topologyDiscover">
              <h4 class="hub-section-title">单设备邻居发现</h4>
              <div class="hub-pane-actions">
                <el-button type="primary" size="small" :loading="deviceDiscovering" @click="discoverDeviceNeighbors">
                  发现邻居
                </el-button>
              </div>
              <el-alert
                v-if="deviceDiscoverResult"
                :title="deviceDiscoverSummary"
                :type="(deviceDiscoverResult.linksCreated || 0) > 0 ? 'success' : 'info'"
                :closable="false"
                show-icon
                style="margin-bottom: 10px"
              />
              <el-table
                v-if="deviceDiscoverNeighbors.length"
                :data="deviceDiscoverNeighbors"
                size="small"
                border
                max-height="140"
              >
                <el-table-column prop="protocol" label="协议" width="70" />
                <el-table-column prop="sysName" label="sysName" min-width="90" show-overflow-tooltip />
                <el-table-column prop="ip" label="IP" width="110" />
                <el-table-column label="匹配" width="80">
                  <template #default="{ row }">
                    <el-tag :type="row.deviceId ? 'success' : 'warning'" size="small">
                      {{ row.deviceId ? '已入库' : '未匹配' }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>

              <h4 class="hub-section-title">本端链路 ({{ deviceNeighbors.length }})</h4>
              <el-table v-if="deviceNeighbors.length" :data="deviceNeighbors" size="small" border max-height="200">
                <el-table-column label="对端" min-width="90">
                  <template #default="{ row }">
                    {{ getDeviceName(row.neighborNodeId) }}
                  </template>
                </el-table-column>
                <el-table-column prop="localPort" label="本端口" width="80" />
                <el-table-column label="状态" width="70">
                  <template #default="{ row }">
                    <el-tag :type="row.status === 'up' ? 'success' : 'danger'" size="small">
                      {{ row.status || '-' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="140" fixed="right">
                  <template #default="{ row }">
                    <el-button
                      type="primary"
                      link
                      size="small"
                      @click="toggleNeighborLinkStatus(row)"
                    >
                      {{ row.status === 'up' ? '置 down' : '置 up' }}
                    </el-button>
                    <el-button
                      v-if="isEditMode"
                      type="danger"
                      link
                      size="small"
                      @click="deleteLink({ id: row.id })"
                    >删</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-else description="暂无链路" :image-size="48" />
              </template>

              <template v-if="auth.hasPermission('configs:write') && deviceCaps(selectedDevice).configBackup">
              <h4 class="hub-section-title">配置备份</h4>
              <div class="hub-pane-actions">
                <el-button type="primary" size="small" :loading="backingUp" @click="backupDeviceConfig">
                  立即备份
                </el-button>
                <el-button size="small" @click="loadDeviceConfigs">刷新列表</el-button>
              </div>
              <el-table v-if="deviceConfigs.length" :data="deviceConfigs" size="small" border max-height="160">
                <el-table-column prop="configVersion" label="版本" min-width="90" show-overflow-tooltip />
                <el-table-column prop="configType" label="类型" width="80" />
                <el-table-column label="时间" min-width="140">
                  <template #default="{ row }">
                    {{ row.createdAt || row.backupTime || '-' }}
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-else description="暂无备份，可点「立即备份」" :image-size="48" />
              </template>
            </div>
          </el-tab-pane>

          <el-tab-pane label="运维辅助" name="ops" lazy>
            <div class="hub-pane hub-pane-ops">
              <p class="hub-ops-tip">网管工具在这里：点「诊断」会自动采证据，结论下方出现可确认的动作（探测、备份、导航拓扑等）。日常排障可先用「监视 / 告警」。</p>
              <OpsAssistantPanel embedded />
            </div>
          </el-tab-pane>

        </el-tabs>
      </div>
    </el-drawer>

    <el-dialog v-model="linkDetailVisible" title="链路枢纽" width="520px">
      <el-descriptions v-if="selectedLink" :column="1" border>
        <el-descriptions-item label="源设备">
          <el-button type="primary" link @click="focusLinkEndpoint(selectedLink.sourceNodeId)">
            {{ getDeviceName(selectedLink.sourceNodeId) }}
          </el-button>
        </el-descriptions-item>
        <el-descriptions-item label="源端口">{{ selectedLink.sourcePort || '-' }}</el-descriptions-item>
        <el-descriptions-item label="目标设备">
          <el-button type="primary" link @click="focusLinkEndpoint(selectedLink.targetNodeId)">
            {{ getDeviceName(selectedLink.targetNodeId) }}
          </el-button>
        </el-descriptions-item>
        <el-descriptions-item label="目标端口">{{ selectedLink.targetPort || '-' }}</el-descriptions-item>
        <el-descriptions-item label="带宽">{{ selectedLink.bandwidth || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="getLinkStatusType(selectedLink)">
            {{ getLinkStatusText(selectedLink) }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button
          v-if="selectedLink && auth.hasPermission('topology:write')"
          type="primary"
          @click="toggleSelectedLinkStatus"
        >
          {{ selectedLink.status === 'up' ? '置为 down' : '置为 up' }}
        </el-button>
        <el-button @click="highlightLinkEndpoints">定位两端</el-button>
        <el-button v-if="isEditMode" @click="editLink(selectedLink)">编辑</el-button>
        <el-button v-if="isEditMode" type="danger" @click="deleteLink(selectedLink)">删除</el-button>
        <el-button @click="linkDetailVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="linkDialogVisible"
      title="链路管理"
      width="800px"
    >
      <div class="link-management">
        <div class="link-toolbar">
          <el-button v-if="isEditMode" type="primary" @click="showAddLinkDialog">
            <el-icon><Plus /></el-icon>
            添加链路
          </el-button>
          <el-button @click="loadLinks">
            <el-icon><Refresh /></el-icon>
            刷新
          </el-button>
        </div>

        <el-table v-loading="linksLoading" :data="links" stripe border>
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column label="源设备" width="150">
            <template #default="{ row }">
              {{ getDeviceName(row.sourceNodeId) }}
            </template>
          </el-table-column>
          <el-table-column label="目标设备" width="150">
            <template #default="{ row }">
              {{ getDeviceName(row.targetNodeId) }}
            </template>
          </el-table-column>
          <el-table-column prop="sourcePort" label="源端口" width="100" />
          <el-table-column prop="targetPort" label="目标端口" width="100" />
          <el-table-column prop="bandwidth" label="带宽" width="100" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="getLinkStatusType(row)" size="small">
                {{ getLinkStatusText(row) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="isEditMode" label="操作" fixed="right" width="150">
            <template #default="{ row }">
              <el-button type="primary" size="small" link @click="editLink(row)">
                <el-icon><Edit /></el-icon>
                编辑
              </el-button>
              <el-button type="danger" size="small" link @click="deleteLink(row)">
                <el-icon><Delete /></el-icon>
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>

    <el-dialog
      v-model="addLinkDialogVisible"
      :title="editingLink.id ? '编辑链路' : '添加链路'"
      width="500px"
    >
      <el-form :model="linkForm" label-width="100px">
        <el-form-item label="源设备" required>
          <el-select v-model="linkForm.sourceNodeId" placeholder="请选择源设备" @change="onSourceDeviceChange" clearable>
            <el-option
              v-for="device in devices"
              :key="device.id"
              :label="device.name + ' (' + device.ipAddress + ')'"
              :value="device.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="源端口">
          <el-select
            v-model="linkForm.sourcePort"
            placeholder="请先选择源设备"
            :disabled="!linkForm.sourceNodeId || loadingSourcePorts"
            clearable
            filterable
            allow-create
            :loading="loadingSourcePorts"
          >
            <el-option
              v-for="port in sourcePorts"
              :key="port.id || port.portName"
              :label="port.portName"
              :value="port.portName"
            >
              <span>{{ port.portName }}</span>
              <el-tag
                v-if="port.operStatus"
                :type="port.operStatus === 'up' ? 'success' : 'danger'"
                size="small"
                style="margin-left: 8px"
              >
                {{ port.operStatus === 'up' ? 'UP' : 'DOWN' }}
              </el-tag>
            </el-option>
            <template #loading>
              <el-icon class="is-loading"><Loading /></el-icon>
            </template>
          </el-select>
          <div v-if="loadingSourcePorts" style="font-size: 12px; color: #909399; margin-top: 4px">
            正在加载端口列表...
          </div>
        </el-form-item>
        <el-form-item label="目标设备" required>
          <el-select v-model="linkForm.targetNodeId" placeholder="请选择目标设备" @change="onTargetDeviceChange" clearable>
            <el-option
              v-for="device in devices.filter(d => d.id !== linkForm.sourceNodeId)"
              :key="device.id"
              :label="device.name + ' (' + device.ipAddress + ')'"
              :value="device.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标端口">
          <el-select
            v-model="linkForm.targetPort"
            placeholder="请先选择目标设备"
            :disabled="!linkForm.targetNodeId || loadingTargetPorts"
            clearable
            filterable
            allow-create
            :loading="loadingTargetPorts"
          >
            <el-option
              v-for="port in targetPorts"
              :key="port.id || port.portName"
              :label="port.portName"
              :value="port.portName"
            >
              <span>{{ port.portName }}</span>
              <el-tag
                v-if="port.operStatus"
                :type="port.operStatus === 'up' ? 'success' : 'danger'"
                size="small"
                style="margin-left: 8px"
              >
                {{ port.operStatus === 'up' ? 'UP' : 'DOWN' }}
              </el-tag>
            </el-option>
            <template #loading>
              <el-icon class="is-loading"><Loading /></el-icon>
            </template>
          </el-select>
          <div v-if="loadingTargetPorts" style="font-size: 12px; color: #909399; margin-top: 4px">
            正在加载端口列表...
          </div>
        </el-form-item>
        <el-form-item label="带宽">
          <el-select v-model="linkForm.bandwidth" placeholder="选择带宽">
            <el-option label="10Mbps" value="10Mbps" />
            <el-option label="100Mbps" value="100Mbps" />
            <el-option label="1Gbps" value="1Gbps" />
            <el-option label="10Gbps" value="10Gbps" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="linkForm.status">
            <el-radio label="up">正常</el-radio>
            <el-radio label="down">中断</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <el-alert
        v-if="!linkForm.sourceNodeId || !linkForm.targetNodeId"
        title="提示：选择设备后会自动加载端口列表"
        type="info"
        :closable="false"
        show-icon
        style="margin-top: 10px"
      />
      <template #footer>
        <el-button @click="addLinkDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveLink" :loading="saving">保存</el-button>
      </template>
    </el-dialog>

    <!-- 拓扑发现对话框 -->
    <el-dialog
      v-model="discoverDialogVisible"
      title="扫描拓扑链路"
      width="600px"
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
        title="说明"
        description="通过 SNMP 读取 LLDP/CDP/华为 LLDP；若无结果（eNSP 常见）会自动回退 ARP。邻居须已在设备库中。「刷新」只更新状态，不会扫描链路。"
      />
      <el-form :model="discoverForm" label-width="100px">
        <el-form-item label="发现方式">
          <el-radio-group v-model="discoverForm.method">
            <el-radio label="lldp">优先 LLDP（空则回退 ARP）</el-radio>
            <el-radio label="arp">仅 ARP</el-radio>
            <el-radio label="both">混合（推荐）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="发现范围">
          <div class="discover-scope-actions">
            <el-button link type="primary" size="small" @click="selectAllDiscoverDevices">全选</el-button>
            <el-button link type="primary" size="small" @click="clearDiscoverDevices">清空</el-button>
          </div>
          <el-checkbox-group v-model="discoverForm.devices">
            <el-checkbox
              v-for="device in devices"
              :key="device.id"
              :label="device.id"
            >
              {{ device.name }} ({{ device.ipAddress }})
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="discovering" @click="discoverDialogVisible = false">关闭</el-button>
        <el-button v-if="discovering" @click="cancelDiscover">取消请求</el-button>
        <el-button type="primary" @click="startDiscover" :loading="discovering">
          开始扫描
        </el-button>
      </template>
    </el-dialog>

    <!-- 发现结果说明 -->
    <el-dialog v-model="discoverResultVisible" title="拓扑发现结果" width="720px">
      <template v-if="discoverResult">
        <el-alert
          :title="discoverResult.message || '发现完成'"
          :type="(discoverResult.discoveredLinks || 0) > 0 ? 'success' : 'warning'"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
        />
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="设备总数">{{ discoverResult.totalDevices ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="在线扫描">{{ discoverResult.scannedOnline ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="离线跳过">{{ discoverResult.skippedOffline ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="新发现链路">{{ discoverResult.discoveredLinks ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="发现方式">{{ discoverResult.primaryMethod || '-' }}</el-descriptions-item>
        </el-descriptions>

        <h4 style="margin: 16px 0 8px">设备明细</h4>
        <el-table :data="discoverResult.deviceResults || []" size="small" max-height="240" border>
          <el-table-column prop="deviceName" label="设备" min-width="120" />
          <el-table-column prop="ip" label="IP" width="130" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag
                size="small"
                :type="row.status === 'ok' ? 'success' : row.status === 'skipped' ? 'info' : 'danger'"
              >
                {{ row.status === 'ok' ? '成功' : row.status === 'skipped' ? '跳过' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="邻居/新链路" width="110">
            <template #default="{ row }">
              {{ row.neighborCount ?? '-' }} / {{ row.linksCreated ?? '-' }}
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="180">
            <template #default="{ row }">
              {{ row.reason || row.hint || (row.sources || []).join('/') || '-' }}
            </template>
          </el-table-column>
        </el-table>

        <template v-if="(discoverResult.failures || []).length">
          <h4 style="margin: 16px 0 8px">失败</h4>
          <el-table :data="discoverResult.failures" size="small" border>
            <el-table-column prop="deviceName" label="设备" />
            <el-table-column prop="ip" label="IP" />
            <el-table-column prop="reason" label="原因" />
          </el-table>
        </template>

        <template v-if="(discoverResult.unmatchedNeighbors || []).length">
          <h4 style="margin: 16px 0 8px">未匹配邻居（需先入库）</h4>
          <el-table :data="discoverResult.unmatchedNeighbors" size="small" max-height="160" border>
            <el-table-column prop="fromDevice" label="本端" />
            <el-table-column prop="neighborIp" label="邻居 IP" />
            <el-table-column prop="sysName" label="sysName" />
            <el-table-column prop="reason" label="原因" />
          </el-table>
        </template>
      </template>
      <template #footer>
        <el-button type="primary" @click="discoverResultVisible = false">知道了</el-button>
      </template>
    </el-dialog>

    <!-- 右键运维菜单 -->
    <teleport to="body">
      <div
        v-show="ctxMenu.visible"
        class="topo-ctx-menu"
        :style="{ left: ctxMenu.x + 'px', top: ctxMenu.y + 'px' }"
        @click.stop
      >
        <div class="ctx-title">{{ ctxMenu.device?.name || '设备' }}</div>
        <div v-if="auth.hasPermission('performance:read')" class="ctx-item" @click="ctxAction('monitor')">监视 · 性能</div>
        <div v-if="auth.hasPermission('devices:read')" class="ctx-item" @click="ctxAction('diagnose')">诊断</div>
        <div v-if="auth.hasPermission('alarms:read')" class="ctx-item" @click="ctxAction('alarms')">告警处置</div>
        <div v-if="auth.hasAnyPermission('topology:write', 'configs:write')" class="ctx-item" @click="ctxAction('change')">变更 · 链路/备份</div>
        <div
          v-if="auth.hasPermission('webssh:connect') && deviceCaps(ctxMenu.device).webssh"
          class="ctx-item"
          @click="ctxAction('terminal')"
        >WebSSH 终端</div>
        <div v-if="isEditMode && auth.hasPermission('devices:write')" class="ctx-item danger" @click="ctxAction('delete')">删除设备</div>
      </div>
    </teleport>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import G6 from '@antv/g6'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ZoomIn, ZoomOut, FullScreen, Refresh, Connection, Search, View, Warning,
  Delete, List, Download, Edit, Plus, Menu, Grid, Loading, Share, DArrowLeft, DArrowRight
} from '@element-plus/icons-vue'
import { deviceApi, alarmApi, performanceApi, configApi } from '@/api/device'
import { topologyApi } from '@/api/topology'
import { useAuthStore } from '@/stores/auth'
import { openWebTerminal } from '@/composables/webTerminalBus'
import { askOpsAssistant, syncOpsAssistantFocus } from '@/composables/askOpsAssistant'
import { clearPageContext, setPageContext, usePageOpsBus } from '@/composables/pageOpsBus'
import OpsAssistantPanel from '@/components/ops-assistant/OpsAssistantPanel.vue'
import { readThemePrimary, useThemeSync } from '@/composables/useThemeColors'
import {
  normalizeLink,
  deriveNeighbors,
  mapPortMetrics,
  applyAlertSummaryToDevices,
  findPortMetric,
  resolveLinkVisual,
  findShortestPath
} from '@/utils/topologyHelpers'
import routerIcon from '@/assets/icons/router.svg'
import switchIcon from '@/assets/icons/switch.svg'
import firewallIcon from '@/assets/icons/firewall.svg'
import serverIcon from '@/assets/icons/server.svg'
import pcIcon from '@/assets/icons/pc.svg'
import acIcon from '@/assets/icons/ac.svg'
import apIcon from '@/assets/icons/ap.svg'
import { resolveCapabilities } from '@/utils/deviceCapabilities'

const themePrimary = () => readThemePrimary()
const softPrimary = () =>
  getComputedStyle(document.documentElement).getPropertyValue('--nms-primary-soft').trim() || '#eaf2fd'

const router = useRouter()
const route = useRoute()
const { sink: pageSink } = usePageOpsBus()
const graphContainer = ref(null)
let graph = null

const hubTab = ref('monitor')
const deviceDiscovering = ref(false)
const deviceDiscoverResult = ref(null)
const deviceDiscoverNeighbors = ref([])
const deviceConfigs = ref([])
const backingUp = ref(false)
const linkDetailVisible = ref(false)
const selectedLink = ref(null)
const deviceAlarms = ref([])
const devicePorts = ref([])
const deviceNeighbors = ref([])
const pingResult = ref({
  status: false,
  reachable: false,
  responseTime: 0,
  testedAt: '',
  message: ''
})

const devices = ref([])
const canvasFilter = ref('all')
const typeFilter = ref('')
const sidebarCollapsed = ref(false)
const links = ref([])
const alerts = ref([])
const selectedDevice = ref(null)
const deviceDrawerVisible = ref(false)
const selectedDeviceStatus = ref(null)
const refreshing = ref(false)
const linkDialogVisible = ref(false)
const addLinkDialogVisible = ref(false)
const editingLink = ref({})
const saving = ref(false)
const discoverDialogVisible = ref(false)
const discovering = ref(false)
const discoverResultVisible = ref(false)
const discoverResult = ref(null)
const discoverProgress = ref({ stage: '准备发现...', percent: 8, elapsed: 0 })
let discoverAbortController = null
let discoverElapsedTimer = null
const portMetricsByDevice = ref({})
const edgeHoverTip = ref({ visible: false, x: 0, y: 0, text: '' })
const pathPickMode = ref(false)
const pathEndpoints = ref([null, null])
const pathHighlight = ref({ nodes: [], edges: [] })
const layoutImportInput = ref(null)
const topologyLoading = ref(false)
const topologyLoadError = ref('')
const refreshingTopology = ref(false)
const linksLoading = ref(false)
const ctxMenu = ref({
  visible: false,
  x: 0,
  y: 0,
  device: null
})
const searchText = ref('')
const zoomLevel = ref(1)
const currentLayout = ref('force')

// 监视 / 编辑：默认监视（刷新查看）；编辑才可拖节点、布局、发现、改链路
const VIEW_MODE_KEY = 'topology-view-mode'
const viewMode = ref(localStorage.getItem(VIEW_MODE_KEY) === 'edit' ? 'edit' : 'monitor')
const auth = useAuthStore()
if (!auth.hasPermission('topology:write')) {
  viewMode.value = 'monitor'
}
const isEditMode = computed(() => viewMode.value === 'edit' && auth.hasPermission('topology:write'))
const isMonitorMode = computed(() => !isEditMode.value)
const canOpenChangeTab = computed(() =>
  auth.hasAnyPermission('topology:write', 'configs:write')
)

const resolveHubTab = (tab) => {
  const t = tab || 'monitor'
  const caps = deviceCaps(selectedDevice.value)
  if (t === 'change' && !canOpenChangeTab.value) {
    return auth.hasPermission('alarms:read') ? 'alarms' : 'diagnose'
  }
  if (t === 'terminal') {
    // 终端已迁至导航栏浮窗，不再作为抽屉 Tab
    return auth.hasPermission('devices:read') ? 'diagnose' : (auth.hasPermission('alarms:read') ? 'alarms' : 'monitor')
  }
  if (t === 'alarms' && !auth.hasPermission('alarms:read')) return 'diagnose'
  if (t === 'diagnose' && !auth.hasPermission('devices:read')) return 'monitor'
  if (t === 'monitor' && (!auth.hasPermission('performance:read') || !caps.performance)) {
    if (auth.hasPermission('devices:read')) return 'diagnose'
    if (auth.hasPermission('alarms:read')) return 'alarms'
    return 'diagnose'
  }
  if (t === 'ops') return 'ops'
  return t
}

const applyGraphInteractionMode = () => {
  if (!graph) return
  try {
    graph.setMode(isEditMode.value ? 'edit' : 'monitor')
  } catch (e) {
    console.warn('切换图交互模式失败', e)
  }
}

const onViewModeChange = (mode) => {
  localStorage.setItem(VIEW_MODE_KEY, mode)
  applyGraphInteractionMode()
}

const setViewMode = (mode) => {
  if (viewMode.value === mode) return
  viewMode.value = mode
  onViewModeChange(mode)
}

// --- 自动刷新 ---
const autoRefreshEnabled = ref(false)
const autoRefreshInterval = ref(30)            // 秒：15 / 30 / 60 / 300
const nextRefreshIn = ref(30)                  // 倒计时显示
let topologyTimerId = null                      // 主拓扑轮询定时器
let countdownTimerId = null                     // 1s 倒计时定时器
let lastAutoRefreshErrorAt = 0                  // 避免连续报错刷屏
/** 前端定时扫描链路（与后端 nms.topology.auto-discover 互补） */
const AUTO_DISCOVER_LS_KEY = 'topo-auto-discover-enabled'
const AUTO_DISCOVER_ONCE_KEY = 'topo-auto-scan-once-at'
const autoDiscoverEnabled = ref(localStorage.getItem(AUTO_DISCOVER_LS_KEY) === '1')
let autoDiscoverTimerId = null
let autoDiscoverOnceTried = false

const refreshPolicyValue = computed(() =>
  autoRefreshEnabled.value ? String(autoRefreshInterval.value) : 'off'
)

const onRefreshPolicyChange = (e) => {
  const val = e?.target?.value ?? e
  if (val === 'off') {
    autoRefreshEnabled.value = false
    toggleAutoRefresh(false)
    return
  }
  const sec = Number(val)
  autoRefreshInterval.value = sec
  autoRefreshEnabled.value = true
  handleIntervalChange(sec)
  if (!topologyTimerId) {
    toggleAutoRefresh(true)
  } else {
    startTopologyTimer()
  }
}

// 设备详情抽屉打开后，会加快该设备的性能/端口数据轮询
let deviceMonitorTimerId = null

const linkForm = ref({
  sourceNodeId: null,
  targetNodeId: null,
  sourcePort: '',
  targetPort: '',
  bandwidth: '1Gbps',
  status: 'up'
})

const sourcePorts = ref([])
const targetPorts = ref([])
const loadingSourcePorts = ref(false)
const loadingTargetPorts = ref(false)

const discoverForm = ref({
  method: 'both',
  devices: []
})

const onlineCount = computed(() => devices.value.filter(d => d.status === 'online').length)
const offlineCount = computed(() => devices.value.filter(d => d.status !== 'online').length)
const linkCount = computed(() => links.value.length)
const alertCount = computed(() =>
  devices.value.reduce((sum, d) => sum + (Number(d.alertCount) || 0), 0)
)
const alertDeviceCount = computed(() =>
  devices.value.filter(d => (Number(d.alertCount) || 0) > 0).length
)

const visibleDevices = computed(() => {
  let list = devices.value
  if (canvasFilter.value === 'online') list = list.filter(d => d.status === 'online')
  else if (canvasFilter.value === 'offline') list = list.filter(d => d.status !== 'online')
  else if (canvasFilter.value === 'alarm') list = list.filter(d => (Number(d.alertCount) || 0) > 0)
  if (typeFilter.value) {
    list = list.filter(d => getDeviceType(d) === typeFilter.value)
  }
  return list
})

const visibleLinkCount = computed(() => {
  const ids = new Set(visibleDevices.value.map(d => String(d.id)))
  return links.value.filter(l =>
    ids.has(String(l.sourceNodeId)) && ids.has(String(l.targetNodeId))
  ).length
})

const filteredDevices = computed(() => {
  const q = searchText.value.trim().toLowerCase()
  let list = visibleDevices.value
  if (!q) return list
  return list.filter(d =>
    (d.name || '').toLowerCase().includes(q) ||
    (d.ipAddress || '').toLowerCase().includes(q)
  )
})

const setCanvasFilter = (key) => {
  canvasFilter.value = key
  onCanvasFilterChange()
}

const onCanvasFilterChange = () => {
  if (!graph) return
  updateGraphData()
}
const activeDeviceAlarms = computed(() =>
  (deviceAlarms.value || []).filter(a => {
    const s = String(a.status || 'ACTIVE').toUpperCase()
    return s === 'ACTIVE' || s === 'ACKNOWLEDGED'
  })
)
const deviceDiscoverSummary = computed(() => {
  const r = deviceDiscoverResult.value
  if (!r) return ''
  const total = r.totalNeighbors ?? deviceDiscoverNeighbors.value.length
  const matched = deviceDiscoverNeighbors.value.filter(n => n.deviceId).length
  const created = r.linksCreated ?? 0
  return `发现 ${total} 个邻居，入库匹配 ${matched}，新建链路 ${created}`
})

const getDeviceType = (device) => {
  const explicit = (device?.deviceType || device?.iconType || device?.type || '').toLowerCase()
  const known = ['router', 'switch', 'firewall', 'ac', 'ap', 'pc', 'server', 'other']
  if (known.includes(explicit) && explicit !== 'other') return explicit

  const lowerName = (device?.name || '').toLowerCase()
  const lowerType = explicit
  if (lowerName.includes('firewall') || lowerName.includes('防火墙') || lowerName.includes('usg')) return 'firewall'
  if (lowerName.includes('access controller') || lowerName.includes('无线控制器') || lowerType === 'ac') return 'ac'
  if (lowerName.includes('access point') || lowerName.includes(' ap') || lowerType === 'ap') return 'ap'
  if (lowerName.includes('router') || lowerName.includes('路由')) return 'router'
  if (lowerName.includes('switch') || lowerName.includes('交换')) return 'switch'
  if (lowerName.includes('server') || lowerName.includes('服务器')) return 'server'
  if (lowerName.includes('pc') || lowerName.includes('终端') || lowerType === 'pc') return 'pc'
  if (explicit === 'other') return 'other'
  return 'switch'
}

const getDeviceTypeLabel = (device) => {
  const type = getDeviceType(device)
  const labels = {
    router: '路由器',
    switch: '交换机',
    firewall: '防火墙',
    ac: 'AC',
    ap: 'AP',
    pc: '虚拟PC',
    server: '服务器',
    other: '其他'
  }
  return labels[type] || '未知'
}

const deviceCaps = (device) => resolveCapabilities(device || {})

const getDeviceIcon = (device) => {
  const type = getDeviceType(device)
  const icons = {
    router: routerIcon,
    switch: switchIcon,
    firewall: firewallIcon,
    server: serverIcon,
    pc: pcIcon,
    ac: acIcon,
    ap: apIcon,
    other: switchIcon
  }
  return icons[type] || switchIcon
}

const getNodeColor = (device, alertCount = 0, alertSevere = false) => {
  const type = getDeviceType(device)
  const isOnline = device && device.status === 'online'

  if (alertSevere) {
    return {
      stroke: '#f56c6c',
      fill: '#fef0f0'
    }
  }

  if (alertCount > 0) {
    return {
      stroke: '#e6a23c',
      fill: '#fdf6ec'
    }
  }
  
  if (!isOnline) {
    return {
      stroke: '#909399',
      fill: '#f5f7fa'
    }
  }
  
  const colors = {
    router: { stroke: themePrimary(), fill: softPrimary() },
    switch: { stroke: '#67c23a', fill: '#f0f9eb' },
    firewall: { stroke: '#f56c6c', fill: '#fef0f0' },
    server: { stroke: '#e6a23c', fill: '#fdf6ec' },
    ac: { stroke: '#e6a23c', fill: '#fdf6ec' },
    ap: { stroke: '#67c23a', fill: '#f0f9eb' },
    pc: { stroke: '#909399', fill: '#f4f4f5' },
    other: { stroke: '#909399', fill: '#f5f7fa' }
  }
  
  return colors[type] || colors.switch
}

const isActiveAlert = (alert) => {
  if (!alert) return false
  const s = String(alert.status || 'ACTIVE').toUpperCase()
  // 拓扑上展示未关闭告警（待处理 + 处理中）
  return s === 'ACTIVE' || s === 'ACKNOWLEDGED' || s === ''
}

const isSevereSeverity = (severity) => {
  const s = String(severity || '').toUpperCase()
  return s === 'CRITICAL' || s === 'MAJOR'
}

/** 统一 IP 格式，便于告警 deviceIp 与设备 ipAddress 匹配 */
const normalizeIp = (ip) => {
  if (!ip) return ''
  let s = String(ip).trim()
  if (!s) return ''
  if (s.toLowerCase().startsWith('udp:')) s = s.slice(4).trim()
  while (s.startsWith('/')) s = s.slice(1)
  const slash = s.indexOf('/')
  if (slash > 0) s = s.slice(0, slash)
  if (s.startsWith('[') && s.includes(']')) {
    s = s.slice(1, s.indexOf(']'))
  }
  return s.trim()
}

/** 把告警解析到设备 id（优先 deviceId，其次规范化 IP） */
const resolveAlarmDeviceId = (alert, ipToDeviceIdMap) => {
  if (!alert) return null
  if (alert.deviceId != null && alert.deviceId !== '') {
    return String(alert.deviceId)
  }
  if (alert.device && typeof alert.device === 'object' && alert.device.id != null) {
    return String(alert.device.id)
  }
  const ip = normalizeIp(alert.deviceIp)
  if (ip && ipToDeviceIdMap[ip]) {
    return ipToDeviceIdMap[ip]
  }
  return null
}

const initGraph = () => {
  if (!graphContainer.value) return
  
  // 注册自定义节点（全局只注册一次，避免重复注册警告）
  if (!G6.__enspDeviceNodeRegistered) {
  G6.registerNode('device-node', {
    draw(cfg, group) {
      const device = cfg.device || {}
      const colors = getNodeColor(device, cfg.alertCount || 0, cfg.alertSevere)
      const iconUrl = getDeviceIcon(device)
      const nodeRadius = 32
      const iconSize = 36
      const iconOffset = -iconSize / 2
      
      // 外层圆形（keyShape）；子图形 capture:false，避免挡住拖拽
      const circle = group.addShape('circle', {
        attrs: {
          x: 0,
          y: 0,
          r: nodeRadius,
          fill: colors.fill,
          stroke: colors.stroke,
          lineWidth: cfg.alertSevere ? 3 : 2,
          cursor: 'grab',
          shadowColor: cfg.alertSevere ? 'rgba(245, 108, 108, 0.35)' : 'rgba(0, 0, 0, 0.08)',
          shadowBlur: cfg.alertSevere ? 12 : 6,
          shadowOffsetY: 2
        },
        name: 'outer-circle',
        draggable: true
      })
      
      // 设备图标
      if (iconUrl) {
        group.addShape('image', {
          attrs: {
            x: iconOffset,
            y: iconOffset - 2,
            width: iconSize,
            height: iconSize,
            img: iconUrl,
            opacity: device.status === 'online' ? 1 : 0.45,
            cursor: 'grab'
          },
          name: 'device-icon',
          draggable: true,
          capture: false
        })
      }
      
      // 状态点
      const statusColor = device.status === 'online' ? '#67c23a' : '#909399'
      group.addShape('circle', {
        attrs: {
          x: -nodeRadius + 10,
          y: nodeRadius - 10,
          r: 6,
          fill: statusColor,
          stroke: '#fff',
          lineWidth: 1.5,
          cursor: 'grab'
        },
        name: 'status-dot',
        capture: false
      })
      
      // 告警角标（始终创建，便于静默刷新显隐）
      const alertVisible = (cfg.alertCount || 0) > 0
      const alertFill = cfg.alertSevere ? '#f56c6c' : '#e6a23c'
      group.addShape('circle', {
        attrs: {
          x: nodeRadius - 10,
          y: -nodeRadius + 10,
          r: 8,
          fill: alertFill,
          stroke: '#fff',
          lineWidth: 1.5,
          cursor: 'grab',
          opacity: alertVisible ? 1 : 0
        },
        name: 'alert-dot',
        capture: false
      })

      group.addShape('text', {
        attrs: {
          x: nodeRadius - 10,
          y: -nodeRadius + 10,
          text: (cfg.alertCount || 0) > 9 ? '9+' : String(cfg.alertCount || 0),
          fontSize: 9,
          fill: '#fff',
          textAlign: 'center',
          textBaseline: 'middle',
          fontWeight: 'bold',
          cursor: 'grab',
          opacity: alertVisible ? 1 : 0
        },
        name: 'alert-count',
        capture: false
      })
      
      // 设备名称
      group.addShape('text', {
        attrs: {
          x: 0,
          y: nodeRadius + 8,
          text: device.name,
          fontSize: 12,
          fill: '#303133',
          textAlign: 'center',
          textBaseline: 'top',
          fontWeight: 500,
          cursor: 'grab'
        },
        name: 'device-name',
        capture: false
      })
      
      // IP地址
      if (device.ipAddress) {
        group.addShape('text', {
          attrs: {
            x: 0,
            y: nodeRadius + 24,
            text: device.ipAddress,
            fontSize: 11,
            fill: '#909399',
            textAlign: 'center',
            textBaseline: 'top',
            cursor: 'grab'
          },
          name: 'device-ip',
          capture: false
        })
      }
      
      return circle
    },
    setState(name, value, item) {
      const group = item.getContainer()
      const circle = group.find(e => e.get('name') === 'outer-circle') || group.get('children')[0]
      const nameShape = group.find(e => e.get('name') === 'device-name')
      let selectRing = group.find(e => e.get('name') === 'select-ring')

      if (name === 'selected') {
        if (value) {
          if (!selectRing) {
            selectRing = group.addShape('circle', {
              attrs: {
                x: 0,
                y: 0,
                r: 40,
                fill: 'transparent',
                stroke: themePrimary(),
                lineWidth: 3,
                lineDash: [6, 4],
                cursor: 'grab'
              },
              name: 'select-ring',
              capture: false
            })
          }
          selectRing.show()
          selectRing.attr({
            stroke: themePrimary(),
            lineWidth: 3,
            lineDash: [6, 4],
            opacity: 1
          })
          if (circle) {
            circle.attr({
              r: 34,
              stroke: themePrimary(),
              lineWidth: 4,
              shadowColor: 'rgba(64, 158, 255, 0.45)',
              shadowBlur: 18
            })
          }
          if (nameShape) {
            nameShape.attr({ fill: themePrimary(), fontWeight: 700 })
          }
        } else {
          if (selectRing) selectRing.hide()
          if (circle) {
            circle.attr({
              r: 32,
              lineWidth: 2,
              shadowColor: 'rgba(0, 0, 0, 0.08)',
              shadowBlur: 6
            })
          }
          if (nameShape) {
            nameShape.attr({ fill: '#303133', fontWeight: 500 })
          }
        }
        return
      }

      if (name === 'highlight') {
        if (value) {
          if (circle) {
            circle.attr({
              r: 34,
              stroke: '#e6a23c',
              lineWidth: 3,
              shadowColor: 'rgba(230, 162, 60, 0.35)',
              shadowBlur: 12
            })
          }
          if (nameShape && !item.hasState('selected')) {
            nameShape.attr({ fill: '#e6a23c', fontWeight: 600 })
          }
        } else if (!item.hasState('selected') && !item.hasState('hover')) {
          if (circle) {
            circle.attr({
              r: 32,
              lineWidth: 2,
              shadowColor: 'rgba(0, 0, 0, 0.08)',
              shadowBlur: 6
            })
          }
          if (nameShape) {
            nameShape.attr({ fill: '#303133', fontWeight: 500 })
          }
        }
        return
      }

      if (name === 'hover') {
        if (value && !item.hasState('selected')) {
          if (circle) {
            circle.attr({
              r: 34,
              stroke: '#79bbff',
              lineWidth: 3,
              shadowColor: 'rgba(64, 158, 255, 0.25)',
              shadowBlur: 12
            })
          }
        } else if (!value && !item.hasState('selected') && !item.hasState('highlight')) {
          if (circle) {
            circle.attr({
              r: 32,
              lineWidth: 2,
              shadowColor: 'rgba(0, 0, 0, 0.08)',
              shadowBlur: 6
            })
          }
          if (nameShape) {
            nameShape.attr({ fill: '#303133', fontWeight: 500 })
          }
        }
      }
    },
    getAnchorPoints() {
      return [
        [0.5, 0], // 上
        [1, 0.5], // 右
        [0.5, 1], // 下
        [0, 0.5]  // 左
      ]
    }
  }, 'single-node')
  G6.__enspDeviceNodeRegistered = true
  }
  
  // 初始化图
  const minimap = new G6.Minimap({
    size: [168, 108],
    className: 'topo-minimap',
    type: 'delegate'
  })

  graph = new G6.Graph({
    container: graphContainer.value,
    width: graphContainer.value.offsetWidth,
    height: graphContainer.value.offsetHeight,
    fitView: false,
    fitViewPadding: 40,
    plugins: [minimap],
    modes: {
      // 监视：可拖动画布/缩放/点选，不可拖节点
      monitor: ['drag-canvas', 'zoom-canvas', 'click-select'],
      // 编辑：可拖节点改布局
      edit: [
        {
          type: 'drag-node',
          enableDelegate: false
        },
        'drag-canvas',
        'zoom-canvas',
        'click-select'
      ],
      // G6 初始模式名；随后立即切到 viewMode
      default: ['drag-canvas', 'zoom-canvas', 'click-select']
    },
    defaultNode: {
      type: 'device-node',
      size: 64
    },
    defaultEdge: {
      type: 'cubic',
      style: {
        stroke: themePrimary(),
        lineWidth: 2,
        lineAppendWidth: 12,
        curveOffset: 0
      },
      labelCfg: {
        autoRotate: false,
        style: {
          fill: '#606266',
          fontSize: 11,
          fontWeight: 400,
          background: {
            fill: '#ffffff',
            padding: [2, 6, 2, 6],
            radius: 2,
            stroke: '#e4e7ed',
            lineWidth: 1
          }
        }
      }
    },
    layout: {
      type: 'force',
      preventOverlap: true,
      nodeStrength: -280,
      edgeStrength: 0.15,
      nodeSize: 72,
      collideStrength: 0.9,
      animate: false,
      // 关键：changeData / 刷新时不要自动重跑布局（否则自动刷新会排成两排）
      relayoutAtChangeData: false
    },
    nodeStateStyles: {
      selected: {
        stroke: themePrimary(),
        lineWidth: 4,
        shadowColor: 'rgba(64, 158, 255, 0.3)',
        shadowBlur: 20
      },
      highlight: {
        stroke: '#e6a23c',
        lineWidth: 4
      },
      hover: {
        stroke: themePrimary(),
        lineWidth: 3,
        shadowColor: 'rgba(64, 158, 255, 0.2)',
        shadowBlur: 15,
        shadowOffsetY: 5
      }
    },
    edgeStateStyles: {
      selected: {
        lineWidth: 5,
        shadowColor: 'rgba(64, 158, 255, 0.4)',
        shadowBlur: 12
      },
      highlight: {
        lineWidth: 5,
        shadowColor: 'rgba(230, 162, 60, 0.4)',
        shadowBlur: 12
      },
      hover: {
        lineWidth: 4,
        shadowColor: 'rgba(64, 158, 255, 0.3)',
        shadowBlur: 10
      }
    }
  })
  
  // 绑定事件
  bindEvents()

  // 加载数据
  loadTopology()
}

const bindEvents = () => {
  if (!graph) return
  
  // 节点悬停
  graph.on('node:mouseenter', (e) => {
    const item = e.item
    if (!item) return
    const nodeId = item.getID()

    graph.setItemState(item, 'hover', true)

    // G6 4.x：邻居用 getNeighbors(id)，关联边用 node.getEdges()
    let neighbors = []
    try {
      neighbors = graph.getNeighbors(nodeId) || []
    } catch (err) {
      neighbors = []
    }
    neighbors.forEach(neighbor => {
      if (neighbor) graph.setItemState(neighbor, 'highlight', true)
    })

    let edges = []
    try {
      edges = typeof item.getEdges === 'function'
        ? item.getEdges()
        : graph.getEdges().filter(edge => {
            const m = edge.getModel()
            const s = String(m.source)
            const t = String(m.target)
            const id = String(nodeId)
            return s === id || t === id
          })
    } catch (err) {
      edges = []
    }
    edges.forEach(edge => {
      if (edge) graph.setItemState(edge, 'highlight', true)
    })
  })
  
  // 节点离开悬停
  graph.on('node:mouseleave', (e) => {
    const item = e.item
    if (item) graph.setItemState(item, 'hover', false)
    // 清除临时悬停高亮，但保留当前选中设备的选中/邻居高亮
    graph.getNodes().forEach(node => {
      if (node.hasState('selected')) return
      graph.clearItemStates(node)
    })
    graph.getEdges().forEach(edge => {
      graph.clearItemStates(edge)
    })
    if (pathHighlight.value.nodes.length) {
      applyPathHighlight()
    } else if (selectedDevice.value) {
      highlightDeviceNeighbors(selectedDevice.value.id)
    }
  })
  
  // 节点点击
  graph.on('node:click', (e) => {
    const item = e.item
    const model = item.getModel()
    if (pathPickMode.value) {
      handlePathNodePick(model.device)
      return
    }
    openDeviceHub(model.device, 'monitor')
  })

  // 边点击 - 显示链路枢纽
  graph.on('edge:click', (e) => {
    const item = e.item
    const model = item.getModel()
    const linkData = model.link

    // 如果边 model 中没有 link，从 links 中查找匹配的链路
    if (linkData && linkData.id !== undefined) {
      selectedLink.value = linkData
    } else {
      // 通过 source/target 来匹配
      const source = model.source || (e.item && e.item.getModel && e.item.getModel().source)
      const target = model.target || (e.item && e.item.getModel && e.item.getModel().target)
      const foundLink = links.value.find(
        l => String(l.sourceNodeId) === String(source) && String(l.targetNodeId) === String(target) ||
             String(l.sourceNodeId) === String(target) && String(l.targetNodeId) === String(source)
      )
      if (foundLink) {
        selectedLink.value = foundLink
      } else {
        return
      }
    }

    linkDetailVisible.value = true
  })
  
  // 节点双击
  graph.on('node:dblclick', (e) => {
    const item = e.item
    const model = item.getModel()
    focusDevice(model.device)
  })
  
  // 边悬停
  graph.on('edge:mouseenter', (e) => {
    const item = e.item
    graph.setItemState(item, 'hover', true)
    const model = item.getModel()
    const tip = model.tip || (model.link
      ? `${model.link.sourcePort || '?'} ↔ ${model.link.targetPort || '?'}`
      : '链路')
    const evt = e.originalEvent || e
    const wrapper = graphContainer.value?.closest('.topology-canvas-wrapper')
    const rect = wrapper?.getBoundingClientRect()
    const clientX = evt.clientX || 0
    const clientY = evt.clientY || 0
    edgeHoverTip.value = {
      visible: true,
      x: rect ? clientX - rect.left + 12 : clientX + 12,
      y: rect ? clientY - rect.top + 12 : clientY + 12,
      text: tip
    }
  })

  graph.on('edge:mousemove', (e) => {
    if (!edgeHoverTip.value.visible) return
    const evt = e.originalEvent || e
    const wrapper = graphContainer.value?.closest('.topology-canvas-wrapper')
    const rect = wrapper?.getBoundingClientRect()
    const clientX = evt.clientX || 0
    const clientY = evt.clientY || 0
    edgeHoverTip.value = {
      ...edgeHoverTip.value,
      x: rect ? clientX - rect.left + 12 : clientX + 12,
      y: rect ? clientY - rect.top + 12 : clientY + 12
    }
  })
  
  graph.on('edge:mouseleave', (e) => {
    const item = e.item
    graph.setItemState(item, 'hover', false)
    edgeHoverTip.value = { visible: false, x: 0, y: 0, text: '' }
  })
  
  // 画布点击取消选中
  graph.on('canvas:click', () => {
    hideCtxMenu()
    edgeHoverTip.value = { visible: false, x: 0, y: 0, text: '' }
    if (pathPickMode.value) return
    graph.getNodes().forEach(node => {
      graph.clearItemStates(node)
    })
    graph.getEdges().forEach(edge => {
      graph.clearItemStates(edge)
    })
    if (pathHighlight.value.nodes.length) {
      applyPathHighlight()
    }
  })

  // 拖拽时固定节点，防止力导向把节点拉回（仅编辑模式）
  graph.on('node:dragstart', (e) => {
    if (!isEditMode.value) return
    const item = e.item
    if (!item) return
    try {
      graph.stopLayoutAnimation()
    } catch (ex) {}
    const model = item.getModel()
    item.getModel().fx = model.x
    item.getModel().fy = model.y
  })

  graph.on('node:drag', (e) => {
    if (!isEditMode.value) return
    const item = e.item
    if (!item) return
    const model = item.getModel()
    model.fx = model.x
    model.fy = model.y
  })

  // 拖拽结束：保持固定，写入 localStorage + 后端
  graph.on('node:dragend', (e) => {
    if (!isEditMode.value) return
    const item = e.item
    if (!item) return
    const model = item.getModel()
    model.fx = model.x
    model.fy = model.y
    if (model.device && model.device.id) {
      persistNodePosition(model.device.id, model.x, model.y)
    }
  })

  // 右键运维菜单
  graph.on('node:contextmenu', (e) => {
    e.preventDefault()
    const evt = e.originalEvent || e
    if (evt && typeof evt.preventDefault === 'function') evt.preventDefault()
    const model = e.item && e.item.getModel()
    if (!model || !model.device) return
    const clientX = (evt && evt.clientX) || 0
    const clientY = (evt && evt.clientY) || 0
      // 防右键菜单被紧随其后的 click 立刻关掉
      setTimeout(() => openCtxMenu(model.device, clientX, clientY), 0)
  })
  
  // 缩放事件
  graph.on('viewportchange', () => {
    zoomLevel.value = graph.getZoom()
  })

  applyGraphInteractionMode()
}

const loadTopology = async (silent = false) => {
  try {
    if (!silent) {
      topologyLoading.value = true
      topologyLoadError.value = ''
    }
    // ===== 1. 获取原始数据 =====
    const topologyRes = await topologyApi.getFullTopology()

    let nodes = []
    let linkList = []
    if (topologyRes && topologyRes.nodes) {
      nodes = topologyRes.nodes
    } else if (Array.isArray(topologyRes)) {
      nodes = topologyRes
    }
    if (topologyRes && topologyRes.links) {
      linkList = topologyRes.links
    }

    // ===== 2. 规范化设备和链路 =====
    const nodeList = nodes.map(n => ({
      id: n.id,
      name: n.name || n.deviceName || 'Unknown',
      ipAddress: n.ipAddress || n.ip || '',
      status: n.status || 'offline',
      model: n.model || '',
      vendor: n.vendor || '',
      description: n.description || '',
      alertCount: Number(n.alertCount) || 0,
      alertSevere: !!n.alertSevere,
      ...n
    }))
    devices.value = applyAlertSummaryToDevices(nodeList, topologyRes?.alertSummary)
    links.value = linkList.map(normalizeLink)

    // ===== 3. 告警：静默刷新不覆盖真实告警列表（角标以节点 alertCount 为准）=====
    if (!silent) {
      try {
        const alarmsRes = await alarmApi.queryAlarms({
          status: ['ACTIVE', 'ACKNOWLEDGED'],
          page: 0,
          size: 200,
          sort: 'occurredAt,desc'
        })
        const list = Array.isArray(alarmsRes?.content)
          ? alarmsRes.content
          : (Array.isArray(alarmsRes) ? alarmsRes : [])
        const ipMap = {}
        devices.value.forEach(d => {
          const nip = normalizeIp(d.ipAddress)
          if (nip) ipMap[nip] = String(d.id)
        })
        alerts.value = list.map(a => {
          if (!a) return a
          const deviceId = resolveAlarmDeviceId(a, ipMap)
          return {
            ...a,
            deviceId: deviceId != null ? Number(deviceId) || deviceId : a.deviceId,
            deviceIp: normalizeIp(a.deviceIp) || a.deviceIp
          }
        }).filter(isActiveAlert)
      } catch (e) {
        console.warn('获取告警失败:', e)
        alerts.value = []
      }
    }

    // ===== 4. G6 渲染 =====
    const existingSelected = selectedDevice.value
    const existingDrawerOpen = deviceDrawerVisible.value
    const existingDrawerDeviceId = existingSelected ? existingSelected.id : null

    if (silent && graph) {
      updateGraphDataSilent()
    } else if (graph) {
      updateGraphData()
    }
    ensureAlertPulse()

    // 刷新后恢复当前选中高亮
    if (selectedDevice.value?.id != null) {
      const stillExists = devices.value.some(d => String(d.id) === String(selectedDevice.value.id))
      if (stillExists) {
        highlightDeviceNeighbors(selectedDevice.value.id)
      }
    }

    // ===== 5. 恢复选中的设备 / 链路 / 抽屉状态 =====
    if (existingDrawerOpen && existingDrawerDeviceId) {
      const stillExists = devices.value.some(d => String(d.id) === String(existingDrawerDeviceId))
      if (stillExists) {
        const d = devices.value.find(x => String(x.id) === String(existingDrawerDeviceId))
        if (d) {
          selectedDevice.value = d
          deviceDrawerVisible.value = true
          if (hubTab.value === 'alarms' && auth.hasPermission('alarms:read')) {
            await loadDeviceAlarms(d.id)
          }
          deviceNeighbors.value = deriveNeighbors(d.id, links.value)
        }
      }
    }
    if (selectedLink.value && selectedLink.value.id != null) {
      const stillExists = links.value.some(l => String(l.id) === String(selectedLink.value.id))
      if (stillExists) {
        selectedLink.value = links.value.find(l => String(l.id) === String(selectedLink.value.id))
      }
    }
    topologyLoadError.value = ''
    // 端口遥测异步加载，完成后静默刷新边样式
    loadLinkTelemetry().catch(() => {})
    if (!silent) {
      await nextTick()
      applyRouteDeviceFocus()
      applyRcaHighlightFromRoute()
      maybeAutoScanWhenEmpty()
    }
  } catch (error) {
    console.error('加载拓扑失败:', error)
    if (!silent) {
      topologyLoadError.value = '加载拓扑失败: ' + (error && error.message ? error.message : String(error))
      ElMessage.error(topologyLoadError.value)
    }
  } finally {
    if (!silent) topologyLoading.value = false
  }
}

const retryLoadTopology = () => loadTopology(false)
const goToDevices = () => router.push('/devices')

// ===== 解析节点坐标：优先画布当前坐标，然后后端，最后 localStorage =====
const resolveNodePosition = (deviceId, device) => {
  if (graph) {
    try {
      const item = graph.findById(String(deviceId))
      if (item) {
        const m = item.getModel()
        if (typeof m.x === 'number' && typeof m.y === 'number' && !Number.isNaN(m.x) && !Number.isNaN(m.y)) {
          return { x: m.x, y: m.y, fixed: true }
        }
      }
    } catch (e) {}
  }

  // 后端坐标：排除 (0,0) 默认占位
  if (device && device.x != null && device.y != null) {
    const x = Number(device.x)
    const y = Number(device.y)
    if (!Number.isNaN(x) && !Number.isNaN(y) && !(x === 0 && y === 0)) {
      return { x, y, fixed: true }
    }
  }

  try {
    const savedPos = localStorage.getItem(`node-position-${deviceId}`)
    if (savedPos) {
      const pos = JSON.parse(savedPos)
      if (typeof pos.x === 'number' && typeof pos.y === 'number') {
        return { x: pos.x, y: pos.y, fixed: true }
      }
    }
  } catch (e) {}

  return { x: undefined, y: undefined, fixed: false }
}

// ===== 内部工具：根据当前 devices / links / alerts 构建 G6 nodes + edges =====
const buildGraphData = () => {
  // 创建设备 IP 到 ID 的映射（用于告警匹配）
  const ipToDeviceIdMap = {}
  devices.value.forEach(device => {
    const nip = normalizeIp(device.ipAddress)
    if (nip) {
      ipToDeviceIdMap[nip] = String(device.id)
    }
  })

  // 1. 告警映射：优先使用节点上的 alertCount/alertSevere
  const alertMap = {}
  const severeMap = {}
  devices.value.forEach(device => {
    const key = String(device.id)
    if (device.alertCount > 0) {
      alertMap[key] = device.alertCount
      if (device.alertSevere) severeMap[key] = true
    }
  })
  if (Object.keys(alertMap).length === 0) {
    alerts.value.forEach(alert => {
      if (!isActiveAlert(alert)) return
      const key = resolveAlarmDeviceId(alert, ipToDeviceIdMap)
      if (key) {
        alertMap[key] = (alertMap[key] || 0) + 1
        if (isSevereSeverity(alert.severity)) {
          severeMap[key] = true
        }
      }
    })
  }

  // 2. 节点（应用画布筛选）
  const sourceDevices = visibleDevices.value
  const nodes = sourceDevices.map(device => {
    const count = alertMap[String(device.id)] || device.alertCount || 0
    const alertSevere = !!severeMap[String(device.id)] || !!device.alertSevere
    const alertCount = count
    const { x, y, fixed } = resolveNodePosition(device.id, device)

    const node = {
      id: String(device.id),
      label: device.name,
      device: device,
      alertCount: alertCount,
      alertSevere: alertSevere,
      draggable: true,
      x,
      y
    }
    if (fixed) {
      node.fx = x
      node.fy = y
    }
    return node
  })

  // 3. 边（并行边 + 端口健康 / 利用率着色）
  const nodeIds = new Set(sourceDevices.map(d => String(d.id)))
  const pairCount = {}
  const edgePairs = []
  links.value.forEach(link => {
    const sourceId = String(link.sourceNodeId)
    const targetId = String(link.targetNodeId)
    if (!nodeIds.has(sourceId) || !nodeIds.has(targetId)) return
    if (sourceId === targetId) return
    const key = [sourceId, targetId].sort().join('-')
    if (!pairCount[key]) pairCount[key] = 0
    edgePairs.push({ link, sourceId, targetId, pairKey: key })
    pairCount[key] += 1
  })

  const deviceIdMap = {}
  sourceDevices.forEach(d => { deviceIdMap[String(d.id)] = d })

  const pairIndex = {}
  const edges = edgePairs.map(({ link, sourceId, targetId, pairKey }) => {
    const idx = pairIndex[pairKey] = (pairIndex[pairKey] || 0) + 1
    const total = pairCount[pairKey]
    const offset = (idx - (total + 1) / 2) * 16

    const sourceDevice = deviceIdMap[sourceId]
    const targetDevice = deviceIdMap[targetId]
    const sourceOnline = sourceDevice ? sourceDevice.status === 'online' : true
    const targetOnline = targetDevice ? targetDevice.status === 'online' : true

    const srcPorts = portMetricsByDevice.value[sourceId] || []
    const tgtPorts = portMetricsByDevice.value[targetId] || []
    const sourcePortMetric = findPortMetric(srcPorts, link.sourcePort)
    const targetPortMetric = findPortMetric(tgtPorts, link.targetPort)
    const visual = resolveLinkVisual({
      link,
      sourceOnline,
      targetOnline,
      sourcePortMetric,
      targetPortMetric
    })

    let lineDash = visual.lineDash || []
    if ((!lineDash || !lineDash.length) && total > 1 && idx > 1) {
      lineDash = [4, 4]
    }

    let label = ''
    if (link.sourcePort || link.targetPort) {
      const src = link.sourcePort || '?'
      const tgt = link.targetPort || '?'
      label = `${src} → ${tgt}`
      if (link.bandwidth) label += ` (${link.bandwidth})`
    } else if (link.bandwidth) {
      label = link.bandwidth
    }
    if (visual.utilPct > 0 && visual.maxRate > 0) {
      label = label ? `${label} · ${visual.utilPct.toFixed(0)}%` : `${visual.utilPct.toFixed(0)}%`
    }

    return {
      id: String(link.id),
      source: sourceId,
      target: targetId,
      label: label,
      tip: visual.tip,
      linkHealth: visual.health,
      style: {
        stroke: visual.strokeColor,
        lineWidth: visual.lineWidth,
        lineDash: lineDash,
        lineAppendWidth: 12
      },
      labelCfg: {
        autoRotate: false,
        style: {
          fill: visual.strokeColor,
          fontSize: 11,
          fontWeight: 500,
          background: {
            fill: '#ffffff',
            padding: [2, 4, 2, 4],
            radius: 2
          }
        }
      },
      curveOffset: total > 1 ? offset : 0,
      link: link
    }
  })

  return { nodes, edges }
}

/** 仅更新节点外观，绝不改动坐标 / 布局 */
const patchNodeAppearance = (item, newNode) => {
  if (!item || !newNode) return
  try {
    const group = item.getContainer()
    const model = item.getModel()
    const curX = model.x
    const curY = model.y

    const labelShape = group.find(e => e.get('name') === 'device-name')
    if (labelShape) labelShape.attr('text', newNode.label)

    const ipShape = group.find(e => e.get('name') === 'device-ip')
    if (ipShape && newNode.device?.ipAddress) {
      ipShape.attr('text', newNode.device.ipAddress)
    }

    const colors = getNodeColor(newNode.device, newNode.alertCount || 0, newNode.alertSevere)
    const circle = group.find(e => e.get('name') === 'outer-circle')
    if (circle) {
      circle.attr('fill', colors.fill)
      circle.attr('stroke', colors.stroke)
      if (!newNode.alertSevere) {
        circle.attr('lineWidth', 2)
        circle.attr('shadowBlur', 6)
        circle.attr('shadowColor', 'rgba(0, 0, 0, 0.08)')
      }
    }

    const icon = group.find(e => e.get('name') === 'device-icon')
    if (icon) {
      icon.attr('opacity', newNode.device?.status === 'online' ? 1 : 0.45)
    }

    const statusDot = group.find(e => e.get('name') === 'status-dot')
    if (statusDot) {
      statusDot.attr('fill', newNode.device?.status === 'online' ? '#67c23a' : '#909399')
    }

    const alertDot = group.find(e => e.get('name') === 'alert-dot')
    const alertCountShape = group.find(e => e.get('name') === 'alert-count')
    const alertVisible = (newNode.alertCount || 0) > 0
    const alertFill = newNode.alertSevere ? '#f56c6c' : '#e6a23c'
    if (alertDot) {
      alertDot.attr('opacity', alertVisible ? 1 : 0)
      alertDot.attr('fill', alertFill)
    }
    if (alertCountShape) {
      alertCountShape.attr('opacity', alertVisible ? 1 : 0)
      alertCountShape.attr('text', (newNode.alertCount || 0) > 9 ? '9+' : String(newNode.alertCount || 0))
    }

    // 只改业务字段，强制保留坐标，避免触发布局
    graph.updateItem(item, {
      label: newNode.label,
      device: newNode.device,
      alertCount: newNode.alertCount,
      alertSevere: newNode.alertSevere,
      x: curX,
      y: curY,
      fx: curX,
      fy: curY
    })
  } catch (e) {
    console.warn('节点外观刷新失败:', item.getID(), e)
  }
}

// ===== 全量刷新（首次加载/手动切换布局后）——尽量保留坐标 =====
const isFirstLoad = ref(true)
const updateGraphData = () => {
  if (!graph) return
  try {
    const { nodes, edges } = buildGraphData()
    const needLayout = nodes.some(n => n.fx == null || n.fy == null)

    // changeData 默认不重布局（relayoutAtChangeData: false）
    graph.changeData({ nodes, edges })

    if (needLayout) {
      // 仅当存在无坐标节点时，主动跑一次当前布局
      try {
        graph.layout()
      } catch (e) {}
      setTimeout(() => {
        if (!graph) return
        graph.getNodes().forEach(node => {
          const model = node.getModel()
          model.fx = model.x
          model.fy = model.y
          if (model.device?.id != null) {
            persistNodePosition(model.device.id, model.x, model.y)
          }
        })
      }, 400)
    }

    if (isFirstLoad.value) {
      isFirstLoad.value = false
      // 初始比例 100%，居中显示（不用 fitView 缩小）
      applyZoom100()
    }
  } catch (e) {
    console.error('G6 全量刷新失败:', e)
  }
}

// ===== 静默刷新（自动刷新）——只改状态样式，绝对不重跑布局 =====
const updateGraphDataSilent = () => {
  if (!graph) return
  try {
    const { nodes: newNodes, edges: newEdges } = buildGraphData()
    const nodeMap = {}
    newNodes.forEach(n => { nodeMap[String(n.id)] = n })
    const edgeMap = {}
    newEdges.forEach(e => { edgeMap[String(e.id)] = e })

    const existingNodeIds = new Set(graph.getNodes().map(n => String(n.getID())))
    const existingEdgeIds = new Set(graph.getEdges().map(e => String(e.getID())))
    const newNodeIds = new Set(newNodes.map(n => String(n.id)))
    const newEdgeIds = new Set(newEdges.map(e => String(e.id)))

    // 1) 更新已有节点外观（坐标不变）
    graph.getNodes().forEach(item => {
      const id = String(item.getID())
      if (nodeMap[id]) {
        patchNodeAppearance(item, nodeMap[id])
      }
    })

    // 2) 更新已有边样式
    graph.getEdges().forEach(item => {
      const id = String(item.getID())
      const e = edgeMap[id]
      if (!e) return
      try {
        graph.updateItem(item, {
          label: e.label,
          style: e.style,
          labelCfg: e.labelCfg,
          link: e.link,
          tip: e.tip,
          linkHealth: e.linkHealth,
          curveOffset: e.curveOffset
        })
      } catch (ex) {}
    })

    // 3) 删除已不存在的节点/边（不触发布局）
    ;[...existingEdgeIds].forEach(id => {
      if (!newEdgeIds.has(id)) {
        try { graph.removeItem(id) } catch (e) {}
      }
    })
    ;[...existingNodeIds].forEach(id => {
      if (!newNodeIds.has(id)) {
        try { graph.removeItem(id) } catch (e) {}
      }
    })

    // 4) 新增节点：放在画布中心附近，固定坐标，不跑力导向
    const cx = graph.getWidth() / 2
    const cy = graph.getHeight() / 2
    let addIndex = 0
    newNodes.forEach(n => {
      const id = String(n.id)
      if (existingNodeIds.has(id)) return
      const x = n.x != null ? n.x : cx + (addIndex % 4) * 100 - 150
      const y = n.y != null ? n.y : cy + Math.floor(addIndex / 4) * 100 - 50
      addIndex++
      try {
        graph.addItem('node', {
          ...n,
          x,
          y,
          fx: x,
          fy: y
        })
        if (n.device?.id != null) {
          persistNodePosition(n.device.id, x, y)
        }
      } catch (e) {
        console.warn('添加节点失败', id, e)
      }
    })

    // 5) 新增边
    newEdges.forEach(e => {
      const id = String(e.id)
      if (existingEdgeIds.has(id)) return
      try {
        graph.addItem('edge', e)
      } catch (err) {
        console.warn('添加边失败', id, err)
      }
    })
  } catch (e) {
    console.error('G6 静默刷新失败:', e)
    // 失败时也不要 graph.read 重排，避免布局跳动
  }
}

const handleDiscover = () => {
  discoverForm.value.devices = devices.value.map(d => d.id)
  discoverForm.value.method = discoverForm.value.method || 'both'
  discoverDialogVisible.value = true
}

const runDiscoverScan = async (opts = {}) => {
  const {
    method = 'both',
    deviceIds = devices.value.map(d => d.id),
    silent = false,
    showResult = true
  } = opts
  if (!deviceIds.length) {
    if (!silent) ElMessage.warning('没有可扫描的设备')
    return null
  }
  if (discovering.value) return null
  try {
    discovering.value = true
    discoverAbortController = new AbortController()
    if (!silent) startDiscoverProgress()
    const res = await topologyApi.discoverTopology(
      { method, deviceIds },
      { signal: discoverAbortController.signal }
    )
    if (!silent) {
      discoverProgress.value = {
        stage: '发现完成，刷新拓扑...',
        percent: 100,
        elapsed: discoverProgress.value.elapsed
      }
    }
    if (res) {
      if (showResult) {
        discoverResult.value = res
        discoverResultVisible.value = true
      }
      const discoveredLinks = res.discoveredLinks || 0
      if (!silent) {
        if (discoveredLinks > 0) {
          ElMessage.success(res.message || `发现了 ${discoveredLinks} 条连接`)
        } else {
          ElMessage.warning(res.message || '未发现新连接')
        }
      } else if (discoveredLinks > 0) {
        ElMessage.success(`自动扫描新增 ${discoveredLinks} 条链路`)
      }
      await loadTopology(true)
    }
    return res
  } catch (error) {
    if (error?.code === 'ERR_CANCELED' || error?.name === 'CanceledError' || error?.name === 'AbortError') {
      return null
    }
    if (!silent) {
      console.error('拓扑发现失败:', error)
      ElMessage.error('拓扑发现失败: ' + (error.response?.data?.message || error.message))
    }
    return null
  } finally {
    stopDiscoverProgressTimer()
    discovering.value = false
    discoverAbortController = null
  }
}

/** 无链路时自动扫描一次（30 分钟冷却） */
const maybeAutoScanWhenEmpty = () => {
  if (autoDiscoverOnceTried) return
  if (!auth.hasPermission('topology:write')) return
  if (discovering.value) return
  if (links.value.length > 0) return
  const online = devices.value.filter(d => d.status === 'online').length
  if (online < 2) return
  const last = Number(localStorage.getItem(AUTO_DISCOVER_ONCE_KEY) || 0)
  if (Date.now() - last < 30 * 60 * 1000) return
  autoDiscoverOnceTried = true
  localStorage.setItem(AUTO_DISCOVER_ONCE_KEY, String(Date.now()))
  ElMessage.info('拓扑尚无链路，正在自动扫描邻居…')
  runDiscoverScan({ method: 'both', silent: false, showResult: true })
}

const quickAutoDiscover = () => {
  discoverForm.value.devices = devices.value.map(d => d.id)
  discoverForm.value.method = 'both'
  return runDiscoverScan({ method: 'both', showResult: true })
}

const stopAutoDiscoverTimer = () => {
  if (autoDiscoverTimerId) {
    clearInterval(autoDiscoverTimerId)
    autoDiscoverTimerId = null
  }
}

const startAutoDiscoverTimer = () => {
  stopAutoDiscoverTimer()
  // 前端每 5 分钟扫一次，避免与状态刷新 15s 冲突过频
  autoDiscoverTimerId = setInterval(() => {
    if (document.hidden) return
    if (discovering.value) return
    if (!auth.hasPermission('topology:write')) return
    runDiscoverScan({ method: 'both', silent: true, showResult: false })
  }, 5 * 60 * 1000)
}

const onAutoDiscoverToggle = () => {
  localStorage.setItem(AUTO_DISCOVER_LS_KEY, autoDiscoverEnabled.value ? '1' : '0')
  if (autoDiscoverEnabled.value) {
    startAutoDiscoverTimer()
    ElMessage.success('已开启自动扫描链路（约每 5 分钟）')
    runDiscoverScan({ method: 'both', silent: true, showResult: false })
  } else {
    stopAutoDiscoverTimer()
    ElMessage.info('已关闭自动扫描')
  }
}

const selectAllDiscoverDevices = () => {
  discoverForm.value.devices = devices.value.map(d => d.id)
}

const clearDiscoverDevices = () => {
  discoverForm.value.devices = []
}

const stopDiscoverProgressTimer = () => {
  if (discoverElapsedTimer) {
    clearInterval(discoverElapsedTimer)
    discoverElapsedTimer = null
  }
}

const startDiscoverProgress = () => {
  stopDiscoverProgressTimer()
  const startedAt = Date.now()
  discoverProgress.value = { stage: '正在扫描邻居信息...', percent: 12, elapsed: 0 }
  discoverElapsedTimer = setInterval(() => {
    const elapsed = Math.floor((Date.now() - startedAt) / 1000)
    const percent = Math.min(92, 12 + elapsed * 4)
    let stage = '正在扫描邻居信息...'
    if (elapsed > 8) stage = '解析 LLDP/ARP 邻接...'
    if (elapsed > 20) stage = '写入发现链路...'
    if (elapsed > 45) stage = '仍在发现中，大型拓扑可能较慢...'
    discoverProgress.value = { stage, percent, elapsed }
  }, 500)
}

const cancelDiscover = () => {
  if (discoverAbortController) {
    discoverAbortController.abort()
    discoverAbortController = null
  }
  stopDiscoverProgressTimer()
  discovering.value = false
  discoverProgress.value = { stage: '已取消', percent: 0, elapsed: discoverProgress.value.elapsed }
  ElMessage.info('已取消发现请求（服务端若已开始扫描可能仍会完成）')
}

const startDiscover = async () => {
  if (!discoverForm.value.devices.length) {
    ElMessage.warning('请至少选择一台设备进行发现')
    return
  }
  discoverDialogVisible.value = false
  return runDiscoverScan({
    method: discoverForm.value.method,
    deviceIds: discoverForm.value.devices,
    showResult: true
  })
}

/** 批量拉取链路相关设备的端口指标，用于边着色 */
const loadLinkTelemetry = async () => {
  if (!auth.hasPermission('performance:read')) return
  const ids = new Set()
  links.value.forEach((l) => {
    if (l.sourceNodeId != null) ids.add(String(l.sourceNodeId))
    if (l.targetNodeId != null) ids.add(String(l.targetNodeId))
  })
  if (!ids.size) return

  const entries = await Promise.all(
    [...ids].map(async (id) => {
      try {
        const res = await performanceApi.getLatestPortMetrics(id)
        return [id, mapPortMetrics(res)]
      } catch {
        return [id, []]
      }
    })
  )
  const next = { ...portMetricsByDevice.value }
  entries.forEach(([id, ports]) => {
    next[id] = ports
  })
  portMetricsByDevice.value = next
  if (graph) updateGraphDataSilent()
}

const pathEndpointLabel = (deviceId) => {
  if (deviceId == null) return ''
  const d = devices.value.find((x) => String(x.id) === String(deviceId))
  return d ? d.name : String(deviceId)
}

const togglePathPickMode = () => {
  if (pathPickMode.value) {
    pathPickMode.value = false
    ElMessage.info('已退出路径选择')
    return
  }
  pathPickMode.value = true
  pathEndpoints.value = [null, null]
  pathHighlight.value = { nodes: [], edges: [] }
  clearGraphHighlight()
  ElMessage.info('请依次点击起点与终点设备')
}

const applyPathHighlight = () => {
  if (!graph) return
  const nodeSet = new Set(pathHighlight.value.nodes.map(String))
  const edgeSet = new Set(pathHighlight.value.edges.map(String))
  if (!nodeSet.size) return

  clearGraphHighlight()
  graph.getNodes().forEach((node) => {
    const nid = String(node.getID())
    if (nodeSet.has(nid)) {
      try { graph.setItemState(node, 'highlight', true) } catch (e) {}
      return
    }
    try {
      const group = node.getContainer()
      group.get('children')?.forEach((shape) => {
        const name = shape.get('name')
        if (name === 'select-ring' || name === 'alert-dot' || name === 'alert-count') return
        const opacity = shape.attr('opacity')
        if (opacity === 0) return
        if (name === 'device-icon') shape.attr('opacity', 0.28)
        else if (opacity != null) shape.attr('opacity', 0.22)
      })
    } catch (e) {}
  })
  graph.getEdges().forEach((edge) => {
    const eid = String(edge.getID())
    if (edgeSet.has(eid)) {
      try { graph.setItemState(edge, 'highlight', true) } catch (e) {}
    } else {
      try {
        const keyShape = edge.getKeyShape?.()
        if (keyShape) keyShape.attr('opacity', 0.18)
      } catch (e) {}
    }
  })
}

const clearPathHighlight = () => {
  pathPickMode.value = false
  pathEndpoints.value = [null, null]
  pathHighlight.value = { nodes: [], edges: [] }
  clearGraphHighlight()
  if (selectedDevice.value) {
    highlightDeviceNeighbors(selectedDevice.value.id)
  }
}

const handlePathNodePick = (device) => {
  if (!device?.id) return
  const id = String(device.id)
  const [a, b] = pathEndpoints.value
  if (!a) {
    pathEndpoints.value = [id, null]
    clearGraphHighlight()
    try {
      const item = graph?.findById(id)
      if (item) graph.setItemState(item, 'selected', true)
    } catch (e) {}
    ElMessage.success(`起点：${device.name}，请点击终点`)
    return
  }
  if (String(a) === id) {
    ElMessage.warning('终点不能与起点相同')
    return
  }
  pathEndpoints.value = [a, id]
  const path = findShortestPath(links.value, a, id)
  pathPickMode.value = false
  if (!path.nodes.length) {
    pathHighlight.value = { nodes: [], edges: [] }
    ElMessage.warning('两设备之间无可达路径')
    return
  }
  pathHighlight.value = path
  applyPathHighlight()
  ElMessage.success(`已高亮路径（${path.nodes.length - 1} 跳）`)
}

const handleRefresh = async () => {
  refreshingTopology.value = true
  try {
    await loadTopology(true)
    ElMessage.success('拓扑状态已刷新')
  } catch (e) {
    ElMessage.error('刷新失败: ' + (e?.message || '未知错误'))
  } finally {
    refreshingTopology.value = false
  }
}

// === 自动刷新控制 ===
const startTopologyTimer = () => {
  stopTopologyTimer()
  nextRefreshIn.value = autoRefreshInterval.value
  topologyTimerId = setInterval(async () => {
    if (document.hidden) return
    try {
      await loadTopology(true)
      nextRefreshIn.value = autoRefreshInterval.value
    } catch (e) {
      const now = Date.now()
      if (now - lastAutoRefreshErrorAt > 180000) {
        lastAutoRefreshErrorAt = now
        ElMessage.warning('自动刷新失败，请检查网络连接')
      }
    }
  }, autoRefreshInterval.value * 1000)
  countdownTimerId = setInterval(() => {
    if (document.hidden) return
    if (nextRefreshIn.value > 1) {
      nextRefreshIn.value -= 1
    }
  }, 1000)
}

const stopTopologyTimer = () => {
  if (topologyTimerId) {
    clearInterval(topologyTimerId)
    topologyTimerId = null
  }
  if (countdownTimerId) {
    clearInterval(countdownTimerId)
    countdownTimerId = null
  }
  nextRefreshIn.value = autoRefreshInterval.value
}

const toggleAutoRefresh = (enabled) => {
  if (enabled) {
    startTopologyTimer()
    loadTopology(true).catch(() => {})
    ElMessage.success({ message: `自动刷新已开（${autoRefreshInterval.value}s）`, duration: 1500 })
  } else {
    stopTopologyTimer()
    ElMessage.info({ message: '自动刷新已关', duration: 1200 })
  }
}

const handleIntervalChange = (val) => {
  nextRefreshIn.value = val
  if (autoRefreshEnabled.value) {
    startTopologyTimer()
  }
}

// === 设备性能数据加速轮询（抽屉打开时） ===
const startDeviceMonitorTimer = (deviceId) => {
  stopDeviceMonitorTimer()
  if (!deviceId || hubTab.value !== 'monitor') return
  deviceMonitorTimerId = setInterval(async () => {
    if (!deviceDrawerVisible.value || !selectedDevice.value || String(selectedDevice.value.id) !== String(deviceId)) {
      stopDeviceMonitorTimer()
      return
    }
    if (hubTab.value !== 'monitor') {
      stopDeviceMonitorTimer()
      return
    }
    try {
      await loadMonitorData(deviceId)
    } catch (e) {
      // 静默忽略，避免刷屏
    }
  }, 15000)
}

const stopDeviceMonitorTimer = () => {
  if (deviceMonitorTimerId) {
    clearInterval(deviceMonitorTimerId)
    deviceMonitorTimerId = null
  }
}

const zoomIn = () => {
  if (!graph) return
  const next = Math.min(graph.getZoom() * 1.2, 3)
  graph.zoomTo(next)
  zoomLevel.value = graph.getZoom()
}

const zoomOut = () => {
  if (!graph) return
  const next = Math.max(graph.getZoom() / 1.2, 0.2)
  graph.zoomTo(next)
  zoomLevel.value = graph.getZoom()
}

const fitView = () => {
  if (!graph) return
  graph.fitView(40)
  zoomLevel.value = graph.getZoom()
}

/** 固定 100% 缩放并尽量居中 */
const applyZoom100 = () => {
  if (!graph) return
  try {
    if (typeof graph.fitCenter === 'function') {
      graph.fitCenter()
    }
  } catch (e) {}
  graph.zoomTo(1)
  zoomLevel.value = 1
}

const zoomTo100 = () => {
  applyZoom100()
  ElMessage.success('已切换到 100% 显示')
}

let searchDebounceTimer = null

const runSearch = () => {
  if (!graph) return
  const q = searchText.value.trim().toLowerCase()
  if (!q) {
    if (selectedDevice.value) {
      highlightDeviceNeighbors(selectedDevice.value.id)
    } else {
      clearGraphHighlight()
    }
    return
  }

  let firstMatch = null
  graph.getNodes().forEach(node => {
    const model = node.getModel()
    const matches =
      (model.device?.name || '').toLowerCase().includes(q) ||
      (model.device?.ipAddress || '').toLowerCase().includes(q)

    if (matches) {
      graph.setItemState(node, 'highlight', true)
      if (!firstMatch) firstMatch = node
    } else {
      graph.clearItemStates(node)
    }
  })
  if (firstMatch) graph.focusItem(firstMatch)
}

// 搜索设备并定位（防抖）
const handleSearch = () => {
  clearTimeout(searchDebounceTimer)
  searchDebounceTimer = setTimeout(runSearch, 300)
}

/** 持久化节点坐标：localStorage + 后端（失败不影响前端） */
const persistNodePosition = (deviceId, x, y) => {
  if (deviceId == null || typeof x !== 'number' || typeof y !== 'number') return
  const px = Math.round(x)
  const py = Math.round(y)
  try {
    localStorage.setItem(`node-position-${deviceId}`, JSON.stringify({ x: px, y: py }))
  } catch (e) {}
  topologyApi.saveNodePosition(deviceId, px, py).catch(err => {
    console.warn('保存节点位置失败', err)
  })
}

let alertPulseTimer = null
let alertPulseOn = false

const stopAlertPulse = () => {
  if (alertPulseTimer) {
    clearInterval(alertPulseTimer)
    alertPulseTimer = null
  }
  alertPulseOn = false
}

const ensureAlertPulse = () => {
  stopAlertPulse()
  if (!graph) return
  const hasSevere = graph.getNodes().some(n => n.getModel().alertSevere)
  if (!hasSevere) return
  alertPulseTimer = setInterval(() => {
    if (!graph) return
    alertPulseOn = !alertPulseOn
    graph.getNodes().forEach(node => {
      const model = node.getModel()
      if (!model.alertSevere) return
      const circle = node.getContainer().find(e => e.get('name') === 'outer-circle')
      if (!circle) return
      if (alertPulseOn) {
        circle.attr('lineWidth', 4)
        circle.attr('shadowBlur', 18)
        circle.attr('shadowColor', 'rgba(245, 108, 108, 0.55)')
      } else {
        circle.attr('lineWidth', 2)
        circle.attr('shadowBlur', 8)
        circle.attr('shadowColor', 'rgba(245, 108, 108, 0.25)')
      }
    })
  }, 700)
}

const hideCtxMenu = () => {
  if (Date.now() - ctxOpenedAt < 280) return
  ctxMenu.value.visible = false
}

let ctxOpenedAt = 0

const openCtxMenu = (device, clientX, clientY) => {
  ctxOpenedAt = Date.now()
  ctxMenu.value = {
    visible: true,
    x: clientX,
    y: clientY,
    device
  }
}

const ctxAction = async (action) => {
  const device = ctxMenu.value.device
  hideCtxMenu()
  if (!device) return
  if (action === 'delete') {
    if (!auth.hasPermission('devices:write')) {
      ElMessage.warning('无权限删除设备')
      return
    }
    selectedDevice.value = device
    await deleteDevice()
    return
  }
  if (action === 'change' && !canOpenChangeTab.value) {
    ElMessage.warning('无权限进行变更操作')
    return
  }
  if (action === 'terminal') {
    if (!auth.hasPermission('webssh:connect')) {
      ElMessage.warning('无权限使用 WebSSH')
      return
    }
    openWebTerminal({
      deviceId: device.id,
      deviceName: device.name || device.ipAddress || `设备#${device.id}`
    })
    return
  }
  const tabMap = {
    monitor: 'monitor',
    diagnose: 'diagnose',
    alarms: 'alarms',
    change: 'change'
  }
  const tab = resolveHubTab(tabMap[action] || 'monitor')
  openDeviceHub(device, tab)
  if (action === 'diagnose' && auth.hasPermission('devices:write')) {
    await nextTick()
    runDiagnoseRefresh()
  }
}

const hubTabsLoaded = ref(new Set())
const deviceAlarmsLoading = ref(false)

const resetHubDataState = () => {
  hubTabsLoaded.value = new Set()
  deviceAlarms.value = []
  selectedDeviceStatus.value = null
  devicePorts.value = []
  deviceConfigs.value = []
  pingResult.value = { status: false, reachable: false, responseTime: 0, testedAt: '', message: '' }
}

const loadDeviceAlarms = async (deviceId) => {
  deviceAlarmsLoading.value = true
  try {
    // 与告警管理默认筛选一致：ACTIVE + ACKNOWLEDGED；deviceId 过滤口径由后端统一
    const alarmRes = await alarmApi.queryAlarms({
      deviceId,
      status: ['ACTIVE', 'ACKNOWLEDGED'],
      page: 0,
      size: 200,
      sort: 'occurredAt,desc'
    })
    const list = Array.isArray(alarmRes?.content)
      ? alarmRes.content
      : (Array.isArray(alarmRes) ? alarmRes : [])
    deviceAlarms.value = list
  } catch (e) {
    deviceAlarms.value = []
  } finally {
    deviceAlarmsLoading.value = false
  }
}

const loadMonitorData = async (deviceId) => {
  try {
    const perfRes = await performanceApi.getLatestPerformance(deviceId)
    const perf = perfRes && typeof perfRes === 'object' && perfRes.data ? perfRes.data : perfRes
    if (perf) {
      selectedDeviceStatus.value = {
        cpuUsage: Number(perf.cpuUsage) || 0,
        memoryUsage: Number(perf.memoryUsage) || 0,
        temperature: Number(perf.temperature) || null,
        timestamp: perf.timestamp || new Date().toLocaleString('zh-CN')
      }
    } else {
      selectedDeviceStatus.value = null
    }
  } catch (e) {
    selectedDeviceStatus.value = null
  }

  try {
    const portRes = await performanceApi.getLatestPortMetrics(deviceId)
    devicePorts.value = mapPortMetrics(portRes)
  } catch (e) {
    devicePorts.value = []
  }
}

const loadHubTabData = async (tab, deviceId, force = false) => {
  if (!deviceId) return
  const key = `${deviceId}:${tab}`
  // 告警每次进入都刷新；其它 Tab 仍走缓存，除非 force
  const mustReload = force || tab === 'alarms'
  if (!mustReload && hubTabsLoaded.value.has(key)) return

  deviceNeighbors.value = deriveNeighbors(deviceId, links.value)

  switch (tab) {
    case 'monitor':
      if (auth.hasPermission('performance:read')) {
        await loadMonitorData(deviceId)
      }
      break
    case 'alarms':
      if (auth.hasPermission('alarms:read')) {
        await loadDeviceAlarms(deviceId)
      }
      break
    case 'change':
      if (auth.hasPermission('configs:write')) {
        await loadDeviceConfigs()
      }
      break
    default:
      break
  }
  hubTabsLoaded.value.add(key)
}

const openDeviceHub = (device, tab = 'monitor') => {
  if (!device) return
  const deviceChanged = selectedDevice.value?.id !== device.id
  selectedDevice.value = device
  const safeTab = resolveHubTab(tab)
  hubTab.value = safeTab
  deviceDrawerVisible.value = true
  deviceDiscoverResult.value = null
  deviceDiscoverNeighbors.value = []

  if (deviceChanged) {
    resetHubDataState()
    stopDeviceMonitorTimer()
  }

  syncOpsAssistantFocus({
    deviceId: device.id,
    title: device.name || `设备 #${device.id}`,
    deviceName: device.name || device.ipAddress || '',
    source: 'topology'
  })
  setPageContext({
    page: 'topology',
    deviceId: device.id,
    title: device.name || `设备 #${device.id}`,
    deviceName: device.name || device.ipAddress || '',
    scenario: 'TOPOLOGY'
  })

  loadHubTabData(safeTab, device.id)
  if (safeTab === 'monitor' && auth.hasPermission('performance:read')) {
    startDeviceMonitorTimer(device.id)
  }
  highlightDeviceNeighbors(device.id)
  scrollSelectedDeviceIntoSidebar(device.id)
  try {
    if (graph) {
      const item = graph.findById(String(device.id))
      if (item) graph.focusItem(item, true, { easing: 'easeCubic', duration: 350 })
    }
  } catch (e) {}
}

const handleExport = (type) => {
  if (type === 'png') {
    exportTopology('png')
  } else if (type === 'json') {
    exportTopologyData()
  } else if (type === 'import') {
    layoutImportInput.value?.click()
  }
}

const onLayoutImportFile = async (ev) => {
  const file = ev?.target?.files?.[0]
  if (layoutImportInput.value) layoutImportInput.value.value = ''
  if (!file) return
  try {
    const text = await file.text()
    const data = JSON.parse(text)
    const list = Array.isArray(data?.devices) ? data.devices : (Array.isArray(data) ? data : [])
    if (!list.length) {
      ElMessage.warning('JSON 中未找到设备坐标数据')
      return
    }
    let applied = 0
    list.forEach((d) => {
      const id = d?.id
      const x = Number(d?.x ?? d?.posX ?? d?.positionX)
      const y = Number(d?.y ?? d?.posY ?? d?.positionY)
      if (id == null || !Number.isFinite(x) || !Number.isFinite(y)) return
      const exists = devices.value.some((dev) => String(dev.id) === String(id))
      if (!exists) return
      persistNodePosition(id, x, y)
      if (graph) {
        try {
          const item = graph.findById(String(id))
          if (item) {
            graph.updateItem(item, { x, y, fx: x, fy: y })
          }
        } catch (e) {}
      }
      applied++
    })
    if (applied === 0) {
      ElMessage.warning('未匹配到当前拓扑中的设备坐标')
      return
    }
    ElMessage.success(`已导入 ${applied} 个节点布局`)
    if (graph) {
      try { graph.refreshPositions() } catch (e) {}
    }
  } catch (e) {
    ElMessage.error('导入失败: ' + (e?.message || 'JSON 无效'))
  }
}

const clearGraphHighlight = () => {
  if (!graph) return
  graph.getNodes().forEach(node => {
    try { graph.clearItemStates(node) } catch (e) {}
    restoreNodeOpacity(node)
  })
  graph.getEdges().forEach(edge => {
    try { graph.clearItemStates(edge) } catch (e) {}
    try {
      const keyShape = edge.getKeyShape?.()
      if (keyShape) keyShape.attr('opacity', 1)
    } catch (e) {}
  })
}

const restoreNodeOpacity = (node) => {
  if (!node) return
  try {
    const model = node.getModel()
    const group = node.getContainer()
    group.get('children')?.forEach(shape => {
      const name = shape.get('name')
      if (name === 'select-ring') {
        shape.hide()
        return
      }
      if (name === 'alert-dot' || name === 'alert-count') {
        // 告警角标显隐由业务字段控制，不在这里改
        return
      }
      if (name === 'device-icon') {
        shape.attr('opacity', model.device?.status === 'online' ? 1 : 0.45)
      } else {
        const opacity = shape.attr('opacity')
        if (opacity != null && opacity > 0) shape.attr('opacity', 1)
      }
    })
    const nameShape = group.find(e => e.get('name') === 'device-name')
    if (nameShape) nameShape.attr({ fill: '#303133', fontWeight: 500 })
  } catch (e) {}
}

/** 高亮选中设备及其一跳邻居 */
const highlightDeviceNeighbors = (deviceId) => {
  if (!graph || deviceId == null) return
  clearGraphHighlight()
  const id = String(deviceId)
  const center = graph.findById(id)
  if (!center) return

  const neighborIds = new Set()
  links.value.forEach(l => {
    const s = String(l.sourceNodeId)
    const t = String(l.targetNodeId)
    if (s === id) neighborIds.add(t)
    if (t === id) neighborIds.add(s)
  })

  graph.getNodes().forEach(node => {
    const nid = String(node.getID())
    if (nid === id || neighborIds.has(nid)) return
    try {
      const group = node.getContainer()
      group.get('children')?.forEach(shape => {
        const name = shape.get('name')
        if (name === 'select-ring' || name === 'alert-dot' || name === 'alert-count') return
        const opacity = shape.attr('opacity')
        if (opacity === 0) return
        if (name === 'device-icon') {
          shape.attr('opacity', 0.28)
        } else {
          shape.attr('opacity', 0.4)
        }
      })
    } catch (e) {}
  })

  graph.setItemState(center, 'selected', true)
  neighborIds.forEach(nid => {
    const n = graph.findById(nid)
    if (n) graph.setItemState(n, 'highlight', true)
  })
  graph.getEdges().forEach(edge => {
    const m = edge.getModel()
    const s = String(m.source)
    const t = String(m.target)
    if (s === id || t === id) {
      graph.setItemState(edge, 'highlight', true)
    }
  })
}

const deviceItemRefs = new Map()

const setDeviceItemRef = (id, el) => {
  if (el) deviceItemRefs.set(String(id), el)
  else deviceItemRefs.delete(String(id))
}

const scrollSelectedDeviceIntoSidebar = (deviceId) => {
  nextTick(() => {
    const el = deviceItemRefs.get(String(deviceId))
    if (el && typeof el.scrollIntoView === 'function') {
      el.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
    }
  })
}

const focusSelectedOnCanvas = () => {
  if (!selectedDevice.value || !graph) return
  const item = graph.findById(String(selectedDevice.value.id))
  if (!item) return
  highlightDeviceNeighbors(selectedDevice.value.id)
  graph.focusItem(item, true, { easing: 'easeCubic', duration: 400 })
}

const clearDeviceSelection = () => {
  selectedDevice.value = null
  deviceDrawerVisible.value = false
  stopDeviceMonitorTimer()
  clearGraphHighlight()
  setPageContext({ page: 'topology' })
}

const askExplainNode = () => {
  const d = selectedDevice.value
  if (!d?.id) {
    ElMessage.warning('请先选中拓扑节点')
    return
  }
  askOpsAssistant({
    deviceId: d.id,
    title: d.name || `设备 #${d.id}`,
    deviceName: d.name || d.ipAddress || '',
    source: 'topology',
    primaryToolLabel: '拓扑邻居',
    recommendedTools: [
      { name: 'get_topo_neighbors', label: '拓扑邻居', needConfirm: false, args: { deviceId: d.id } },
      { name: 'get_device_summary', label: '设备摘要', needConfirm: false, args: { deviceId: d.id } },
      { name: 'list_active_alarms_for_device', label: '活动告警', needConfirm: false, args: { deviceId: d.id } }
    ],
    expand: false,
    autoAsk: true,
    autoAskQuestion: `诊断拓扑节点邻居与风险：${d.name || d.ipAddress || d.id}`
  })
}

const askExplainPath = () => {
  const [fromId, toId] = pathEndpoints.value || []
  if (!fromId || !toId) {
    ElMessage.warning('请先选定路径起点与终点')
    return
  }
  askOpsAssistant({
    deviceId: fromId,
    title: `路径 ${pathEndpointLabel(fromId) || fromId} → ${pathEndpointLabel(toId) || toId}`,
    deviceName: pathEndpointLabel(fromId) || '',
    source: 'topology',
    primaryToolLabel: '路径提示',
    recommendedTools: [
      {
        name: 'run_path_hint',
        label: '路径提示',
        needConfirm: false,
        args: { deviceId: fromId, targetDeviceId: toId }
      },
      { name: 'get_topo_neighbors', label: '起点邻居', needConfirm: false, args: { deviceId: fromId } }
    ],
    expand: false,
    autoAsk: true,
    autoAskQuestion: `路径提示：#${fromId} → #${toId}`
  })
}

/** 从告警页等入口：?deviceId=&tab=alarms */
const applyRouteDeviceFocus = () => {
  const deviceId = route.query.deviceId
  if (deviceId == null || deviceId === '') return
  const device = devices.value.find(d => String(d.id) === String(deviceId))
  if (!device) return
  const tab = typeof route.query.tab === 'string' && route.query.tab
    ? route.query.tab
    : 'alarms'
  openDeviceHub(device, tab)
  try {
    if (graph) {
      const item = graph.findById(String(deviceId))
      if (item) {
        graph.focusItem(item, true, { easing: 'easeCubic', duration: 400 })
      }
    }
  } catch (e) {}
}

/** 智能运维 RCA：?rca=1&highlight=1,2,3 */
const applyRcaHighlightFromRoute = () => {
  if (route.query.rca !== '1' && !route.query.highlight) return
  if (!graph) return
  const raw = typeof route.query.highlight === 'string' ? route.query.highlight : ''
  const ids = raw.split(',').map(s => s.trim()).filter(Boolean)
  if (!ids.length) return
  try {
    clearGraphHighlight()
    let focused = false
    ids.forEach((id, idx) => {
      const item = graph.findById(String(id))
      if (!item) return
      graph.setItemState(item, idx === 0 ? 'selected' : 'highlight', true)
      if (!focused) {
        graph.focusItem(item, true, { easing: 'easeCubic', duration: 400 })
        focused = true
      }
    })
    ElMessage.info(`已高亮根因影响设备 ${ids.length} 台`)
  } catch (e) {
    console.warn('RCA 高亮失败', e)
  }
}

const onHubTabChange = (name) => {
  const safe = resolveHubTab(name)
  if (safe !== name) {
    hubTab.value = safe
    return
  }
  if (selectedDevice.value) {
    loadHubTabData(safe, selectedDevice.value.id)
    if (safe === 'monitor' && auth.hasPermission('performance:read')) {
      startDeviceMonitorTimer(selectedDevice.value.id)
    } else {
      stopDeviceMonitorTimer()
    }
    highlightDeviceNeighbors(selectedDevice.value.id)
  }
}

// 选择设备（侧栏列表点击）
const selectDevice = (device) => {
  openDeviceHub(device, 'monitor')
}

// 布局切换 —— 唯一会主动重排的入口（仅编辑模式）
const changeLayout = (type) => {
  if (!graph) return
  if (!isEditMode.value) {
    ElMessage.warning('请先切换到「编辑」模式再调整布局')
    return
  }

  currentLayout.value = type
  ElMessage.info('正在切换布局...')

  // 解除固定，允许重排
  graph.getNodes().forEach(node => {
    const model = node.getModel()
    delete model.fx
    delete model.fy
  })

  const layoutCfg =
    type === 'hierarchy'
      ? { type: 'dagre', rankdir: 'TB', nodesep: 60, ranksep: 80, relayoutAtChangeData: false }
      : type === 'grid'
        ? {
            type: 'grid',
            begin: [50, 50],
            width: Math.max(graph.getWidth() - 100, 600),
            height: Math.max(graph.getHeight() - 100, 400),
            sortBy: 'degree',
            relayoutAtChangeData: false
          }
        : {
            type: 'force',
            preventOverlap: true,
            nodeStrength: -400,
            edgeStrength: 0.2,
            nodeSize: 80,
            collideStrength: 0.8,
            animate: false,
            relayoutAtChangeData: false
          }

  graph.updateLayout(layoutCfg)
  try {
    graph.layout()
  } catch (e) {}

  setTimeout(() => {
    if (!graph) return
    graph.getNodes().forEach(node => {
      const model = node.getModel()
      model.fx = model.x
      model.fy = model.y
      if (model.device?.id != null) {
        persistNodePosition(model.device.id, model.x, model.y)
      }
    })
    graph.fitView(40)
    zoomLevel.value = graph.getZoom()
  }, 450)

  ElMessage.success('布局已更新，可拖动微调')
}

// 导出拓扑视图
const showLinkDialog = () => {
  if (!isEditMode.value) {
    ElMessage.warning('请先切换到「编辑」模式')
    return
  }
  linkDialogVisible.value = true
  loadLinks()
}

const loadLinks = async () => {
  linksLoading.value = true
  try {
    const res = await topologyApi.getLinks()
    let linkList = []
    if (Array.isArray(res)) {
      linkList = res
    } else if (res && Array.isArray(res.data)) {
      linkList = res.data
    } else if (res && Array.isArray(res.links)) {
      linkList = res.links
    }
    links.value = linkList.map(normalizeLink)
  } catch (error) {
    console.error('加载链路列表失败:', error)
    ElMessage.error('加载链路列表失败')
  } finally {
    linksLoading.value = false
  }
}

const showAddLinkDialog = () => {
  if (!isEditMode.value) {
    ElMessage.warning('请先切换到「编辑」模式')
    return
  }
  editingLink.value = {}
  linkForm.value = {
    sourceNodeId: null,
    targetNodeId: null,
    sourcePort: '',
    targetPort: '',
    bandwidth: '1Gbps',
    status: 'up'
  }
  // 清空端口列表
  sourcePorts.value = []
  targetPorts.value = []
  addLinkDialogVisible.value = true
}

const editLink = (link) => {
  if (!isEditMode.value) {
    ElMessage.warning('请先切换到「编辑」模式')
    return
  }
  editingLink.value = link
  linkForm.value = {
    sourceNodeId: link.sourceNodeId,
    targetNodeId: link.targetNodeId,
    sourcePort: link.sourcePort || '',
    targetPort: link.targetPort || '',
    bandwidth: link.bandwidth || '1Gbps',
    status: link.status || 'up'
  }
  
  // 加载源设备和目标设备的端口列表
  if (link.sourceNodeId) {
    loadDevicePorts(link.sourceNodeId, 'source')
  }
  if (link.targetNodeId) {
    loadDevicePorts(link.targetNodeId, 'target')
  }
  
  addLinkDialogVisible.value = true
}

const saveLink = async () => {
  if (!linkForm.value.sourceNodeId || !linkForm.value.targetNodeId) {
    ElMessage.warning('请选择源设备和目标设备')
    return
  }
  
  try {
    saving.value = true
    if (editingLink.value.id) {
      await topologyApi.updateLink(editingLink.value.id, linkForm.value)
      ElMessage.success('链路更新成功')
    } else {
      await topologyApi.addLink(linkForm.value)
      ElMessage.success('链路添加成功')
    }
    addLinkDialogVisible.value = false
    await loadLinks()
    await loadTopology()
  } catch (error) {
    console.error('保存链路失败:', error)
    
    // 解析后端返回的错误消息
    const errorMsg = error.response?.data?.message || 
                     error.message || 
                     '保存失败'
    
    // 判断错误类型并给出友好提示
    if (errorMsg.includes('连接已存在') || errorMsg.includes('链路已存在')) {
      ElMessage.warning('该连接已存在，请勿重复添加')
    } else if (errorMsg.includes('不存在')) {
      ElMessage.error('设备不存在，请刷新页面后重试')
    } else {
      ElMessage.error(errorMsg)
    }
  } finally {
    saving.value = false
  }
}

const deleteLink = async (link) => {
  if (!isEditMode.value) {
    ElMessage.warning('请先切换到「编辑」模式')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定要删除该链路吗？`,
      '确认删除',
      { type: 'warning' }
    )
    await topologyApi.deleteLink(link.id)
    ElMessage.success('删除成功')
    await loadLinks()
    await loadTopology()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('删除失败: ' + (error.message || '未知错误'))
    }
  }
}

const getDeviceName = (deviceId) => {
  if (deviceId === null || deviceId === undefined) return '-'
  const device = devices.value.find(d => Number(d.id) === Number(deviceId))
  return device ? device.name : `设备 #${deviceId}`
}

const getDeviceStatus = (deviceId) => {
  if (deviceId === null || deviceId === undefined) return 'unknown'
  const device = devices.value.find(d => Number(d.id) === Number(deviceId))
  return device ? device.status : 'unknown'
}

// 判断链路的真实连通状态：link.status === 'up' 且两端设备都在线
const getLinkEffectiveStatus = (link) => {
  if (!link) return 'unknown'
  if (link.status !== 'up') return 'down'
  const sourceStatus = getDeviceStatus(link.sourceNodeId)
  const targetStatus = getDeviceStatus(link.targetNodeId)
  if (sourceStatus !== 'online' || targetStatus !== 'online') return 'offline'
  return 'up'
}

const getLinkStatusText = (link) => {
  const status = getLinkEffectiveStatus(link)
  if (status === 'up') return '正常'
  if (status === 'down') return '中断'
  if (status === 'offline') return '设备离线'
  return '未知'
}

const getLinkStatusType = (link) => {
  const status = getLinkEffectiveStatus(link)
  if (status === 'up') return 'success'
  if (status === 'offline') return 'info'
  return 'danger'
}

const onSourceDeviceChange = () => {
  linkForm.value.targetNodeId = null
  linkForm.value.sourcePort = ''
  sourcePorts.value = []
  
  if (linkForm.value.sourceNodeId) {
    loadDevicePorts(linkForm.value.sourceNodeId, 'source')
  }
}

const onTargetDeviceChange = () => {
  linkForm.value.targetPort = ''
  targetPorts.value = []
  
  if (linkForm.value.targetNodeId) {
    loadDevicePorts(linkForm.value.targetNodeId, 'target')
  }
}

// 加载设备端口列表 - 使用性能监控采集到的真实端口数据
const loadDevicePorts = async (deviceId, type) => {
  if (!deviceId) return

  const setLoading = (val) => {
    if (type === 'source') loadingSourcePorts.value = val
    else loadingTargetPorts.value = val
  }

  const setPorts = (ports) => {
    if (type === 'source') sourcePorts.value = ports
    else targetPorts.value = ports
  }

  try {
    setLoading(true)
    // 从性能监控接口获取真实设备端口（SNMP 采集或虚拟数据）
    const res = await performanceApi.getLatestPortMetrics(deviceId)

    // 处理各种响应格式
    let portList = []
    if (Array.isArray(res)) {
      portList = res
    } else if (res && Array.isArray(res.data)) {
      portList = res.data
    } else if (res && res.ports) {
      portList = res.ports
    }

    // 规范化端口对象 - 基于 PerformanceData 的字段
    const normalizedPorts = portList
      .filter(p => p && p.portName)  // 只保留有真实端口名的记录
      .map(p => ({
        id: p.id || p.portIndex,
        portName: p.portName,
        // 有流量表示 up，无流量或为0表示 down
        operStatus: ((p.ifInRate && p.ifInRate > 0) || (p.ifOutRate && p.ifOutRate > 0)) ? 'up' : 'up',
        // 根据速率估算带宽
        speed: p.ifInRate || p.ifOutRate || 0,
        ifInRate: p.ifInRate,
        ifOutRate: p.ifOutRate,
        ...p
      }))

    setPorts(normalizedPorts)

    if (normalizedPorts.length === 0) {
      ElMessage.info('该设备暂未检测到端口数据，请手动输入端口名')
    }
  } catch (error) {
    console.error('获取设备端口失败:', error)
    ElMessage.warning('获取设备端口失败，请手动输入端口名')
    // 不提供硬编码 fallback 端口，保持端口列表为空
    // 用户可通过下拉框的手动输入功能（allow-create）输入端口名
    setPorts([])
  } finally {
    setLoading(false)
  }
}

const refreshDeviceStatus = async () => {
  if (!selectedDevice.value) return
  try {
    refreshing.value = true
    await loadMonitorData(selectedDevice.value.id)
    hubTabsLoaded.value.delete(`${selectedDevice.value.id}:monitor`)
    hubTabsLoaded.value.add(`${selectedDevice.value.id}:monitor`)
    ElMessage.success('性能数据已刷新')
  } catch (e) {
    ElMessage.error('刷新失败: ' + (e.message || '未知错误'))
  } finally {
    refreshing.value = false
  }
}

const formatUpTime = (seconds) => {
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  
  if (days > 0) {
    return `${days}天 ${hours}小时 ${minutes}分钟`
  } else if (hours > 0) {
    return `${hours}小时 ${minutes}分钟`
  } else {
    return `${minutes}分钟`
  }
}

const getCpuColor = (usage) => {
  if (usage < 50) return '#67c23a'
  if (usage < 80) return '#e6a23c'
  return '#f56c6c'
}

const getMemoryColor = (usage) => {
  if (usage < 60) return '#67c23a'
  if (usage < 85) return '#e6a23c'
  return '#f56c6c'
}

const focusDevice = (device) => {
  if (!device) return
  openDeviceHub(device, 'monitor')
  if (graph) {
    const node = graph.findById(String(device.id))
    if (node) {
      graph.focusItem(node, true, { easing: 'easeCubicOut', duration: 500 })
    }
  }
}

const runDiagnoseRefresh = async () => {
  if (!selectedDevice.value) return
  refreshing.value = true
  try {
    await deviceApi.refreshDevice(selectedDevice.value.id)
    ElMessage.success('已触发 SNMP/状态刷新')
    await loadTopology(true)
    await loadHubTabData('monitor', selectedDevice.value.id, true)
    await pingDevice()
  } catch (e) {
    ElMessage.error('刷新失败: ' + (e.message || '未知错误'))
  } finally {
    refreshing.value = false
  }
}

const pingDevice = async () => {
  if (!selectedDevice.value) return
  const deviceId = selectedDevice.value.id

  try {
    ElMessage.info(`正在测试 ${selectedDevice.value.name} 的连通性...`)
    const startTime = Date.now()
    const res = await deviceApi.refreshDevice(deviceId)
    const responseTime = Date.now() - startTime

    pingResult.value = {
      status: true,
      reachable: res && (res.status === 'online' || res.status !== 'offline'),
      responseTime: responseTime,
      testedAt: new Date().toLocaleString('zh-CN'),
      message: res && res.status ? `设备状态: ${res.status}` : '测试完成'
    }

    await loadTopology(true)
    ElMessage.success('连通性测试完成')
  } catch (error) {
    pingResult.value = {
      status: true,
      reachable: false,
      responseTime: 0,
      testedAt: new Date().toLocaleString('zh-CN'),
      message: error.message || '测试失败，请检查设备配置'
    }
    ElMessage.warning('设备连通性测试失败: ' + (error.message || '未知错误'))
  }
}

const gotoAlarmManage = async () => {
  if (!selectedDevice.value) return
  try {
    await ElMessageBox.confirm('将打开告警中心并筛选该设备告警，是否继续？', '打开告警中心', {
      type: 'info', confirmButtonText: '前往', cancelButtonText: '取消'
    })
  } catch {
    return
  }
  router.push({
    path: '/alarms',
    query: { deviceId: String(selectedDevice.value.id) }
  })
}

const openHubTerminal = () => {
  const device = selectedDevice.value
  if (!device) return
  openWebTerminal({
    deviceId: device.id,
    deviceName: device.name || device.ipAddress || `设备#${device.id}`
  })
}

const reloadDeviceAlarms = async () => {
  if (!selectedDevice.value) return
  await loadHubTabData('alarms', selectedDevice.value.id, true)
  ElMessage.success('告警已刷新')
}

const ackAlarm = async (row) => {
  if (!row?.id) return
  try {
    await alarmApi.acknowledgeAlarm(row.id)
    ElMessage.success('告警已进入处理中')
    await reloadAfterAlarmChange()
  } catch (e) {
    ElMessage.error('确认失败: ' + (e.message || '未知错误'))
  }
}

const clearAlarm = async (row) => {
  if (!row?.id) return
  try {
    await alarmApi.clearAlarm(row.id)
    ElMessage.success('告警已关闭')
    await reloadAfterAlarmChange()
  } catch (e) {
    ElMessage.error('清除失败: ' + (e.message || '未知错误'))
  }
}

const reloadAfterAlarmChange = async () => {
  if (selectedDevice.value) {
    await loadHubTabData('alarms', selectedDevice.value.id, true)
  }
  await loadTopology(true)
}

const discoverDeviceNeighbors = async () => {
  if (!selectedDevice.value) return
  deviceDiscovering.value = true
  try {
    const res = await topologyApi.discoverDeviceNeighbors(selectedDevice.value.id)
    const neighbors = Array.isArray(res?.neighbors) ? res.neighbors : []
    deviceDiscoverNeighbors.value = neighbors
    let linksCreated = 0
    const selfId = Number(selectedDevice.value.id)
    for (const n of neighbors) {
      if (!n.deviceId || Number(n.deviceId) === selfId) continue
      const exists = links.value.some(l =>
        (Number(l.sourceNodeId) === selfId && Number(l.targetNodeId) === Number(n.deviceId)) ||
        (Number(l.targetNodeId) === selfId && Number(l.sourceNodeId) === Number(n.deviceId))
      )
      if (exists) continue
      try {
        await topologyApi.addLink({
          sourceNodeId: selfId,
          targetNodeId: Number(n.deviceId),
          sourcePort: n.portId || '',
          targetPort: '',
          bandwidth: '1Gbps',
          status: 'up'
        })
        linksCreated++
      } catch (err) {
        // 已存在等错误忽略
      }
    }
    deviceDiscoverResult.value = {
      ...res,
      linksCreated,
      totalNeighbors: neighbors.length
    }
    await loadTopology(true)
    if (selectedDevice.value) {
      deviceNeighbors.value = deriveNeighbors(selectedDevice.value.id, links.value)
      highlightDeviceNeighbors(selectedDevice.value.id)
    }
    ElMessage.success(linksCreated > 0 ? `发现完成，新建 ${linksCreated} 条链路` : '发现完成')
  } catch (e) {
    ElMessage.error('邻居发现失败: ' + (e.message || '未知错误'))
  } finally {
    deviceDiscovering.value = false
  }
}

const toggleNeighborLinkStatus = async (row) => {
  if (!row?.id) return
  const next = row.status === 'up' ? 'down' : 'up'
  try {
    await topologyApi.updateLinkStatus(row.id, next)
    ElMessage.success(`链路已置为 ${next}`)
    await loadTopology(true)
    if (selectedDevice.value) {
      deviceNeighbors.value = deriveNeighbors(selectedDevice.value.id, links.value)
      highlightDeviceNeighbors(selectedDevice.value.id)
    }
  } catch (e) {
    ElMessage.error('更新链路失败: ' + (e.message || '未知错误'))
  }
}

const toggleSelectedLinkStatus = async () => {
  if (!selectedLink.value?.id) return
  const next = selectedLink.value.status === 'up' ? 'down' : 'up'
  try {
    await topologyApi.updateLinkStatus(selectedLink.value.id, next)
    selectedLink.value = { ...selectedLink.value, status: next }
    ElMessage.success(`链路已置为 ${next}`)
    await loadTopology(true)
  } catch (e) {
    ElMessage.error('更新链路失败: ' + (e.message || '未知错误'))
  }
}

const highlightLinkEndpoints = () => {
  if (!selectedLink.value || !graph) return
  clearGraphHighlight()
  const s = String(selectedLink.value.sourceNodeId)
  const t = String(selectedLink.value.targetNodeId)
  ;[s, t].forEach(id => {
    const n = graph.findById(id)
    if (n) {
      graph.setItemState(n, 'highlight', true)
      graph.focusItem(n)
    }
  })
  graph.getEdges().forEach(edge => {
    const m = edge.getModel()
    if (String(m.id) === String(selectedLink.value.id) ||
      (String(m.source) === s && String(m.target) === t) ||
      (String(m.source) === t && String(m.target) === s)) {
      graph.setItemState(edge, 'selected', true)
    }
  })
  ElMessage.success('已定位链路两端')
}

const focusLinkEndpoint = (deviceId) => {
  linkDetailVisible.value = false
  selectDeviceById(deviceId)
}

const loadDeviceConfigs = async () => {
  if (!selectedDevice.value) return
  try {
    const res = await configApi.getConfigsByDeviceId(selectedDevice.value.id)
    const list = Array.isArray(res) ? res : (res?.data && Array.isArray(res.data) ? res.data : [])
    deviceConfigs.value = list.slice(0, 20)
  } catch (e) {
    deviceConfigs.value = []
  }
}

const backupDeviceConfig = async () => {
  if (!selectedDevice.value) return
  backingUp.value = true
  try {
    await configApi.backupConfig({ deviceId: selectedDevice.value.id })
    ElMessage.success('备份已触发')
    await loadDeviceConfigs()
  } catch (e) {
    ElMessage.error('备份失败: ' + (e.message || '请确认设备已配置 SSH'))
  } finally {
    backingUp.value = false
  }
}

const selectDeviceById = (deviceId) => {
  const device = devices.value.find(d => Number(d.id) === Number(deviceId))
  if (device) {
    selectDevice(device)
  }
}

const deleteDevice = async () => {
  if (!selectedDevice.value) return
  if (!isEditMode.value) {
    ElMessage.warning('请先切换到「编辑」模式')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确定要删除设备 ${selectedDevice.value.name} 吗？`,
      '确认删除',
      { type: 'warning' }
    )
    
    await deviceApi.deleteDevice(selectedDevice.value.id)
    ElMessage.success('删除成功')
    deviceDrawerVisible.value = false
    loadTopology()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('删除失败: ' + (error.message || '未知错误'))
    }
  }
}

/** dataURL → Blob */
const dataUrlToBlob = (dataUrl) => {
  const parts = dataUrl.split(',')
  const mime = (parts[0].match(/:(.*?);/) || [])[1] || 'image/png'
  const binary = atob(parts[1] || '')
  const len = binary.length
  const bytes = new Uint8Array(len)
  for (let i = 0; i < len; i++) bytes[i] = binary.charCodeAt(i)
  return new Blob([bytes], { type: mime })
}

/** 弹出系统「另存为」；不支持则回退为浏览器下载 */
const saveBlobAs = async (blob, suggestedName, acceptTypes) => {
  if (typeof window.showSaveFilePicker === 'function') {
    try {
      const handle = await window.showSaveFilePicker({
        suggestedName,
        types: acceptTypes
      })
      const writable = await handle.createWritable()
      await writable.write(blob)
      await writable.close()
      return { ok: true, mode: 'picker', name: handle.name || suggestedName }
    } catch (e) {
      // 用户取消
      if (e && (e.name === 'AbortError' || e.name === 'NotAllowedError')) {
        return { ok: false, cancelled: true }
      }
      throw e
    }
  }

  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = suggestedName
  a.click()
  URL.revokeObjectURL(url)
  return { ok: true, mode: 'download', name: suggestedName }
}

// 导出拓扑视图
const exportTopology = async (type) => {
  if (!graph) {
    ElMessage.warning('拓扑图未初始化')
    return
  }

  const ext = type === 'jpg' ? 'jpg' : 'png'
  const mime = ext === 'jpg' ? 'image/jpeg' : 'image/png'
  const fileName = `topology-${new Date().toISOString().slice(0, 10)}.${ext}`

  try {
    const dataUrl = await new Promise((resolve, reject) => {
      try {
        if (typeof graph.toFullDataURL === 'function') {
          graph.toFullDataURL(
            (res) => resolve(res),
            mime,
            { backgroundColor: '#f5f7fa', padding: [20, 20, 20, 20] }
          )
        } else {
          // 少数环境下仅有 toDataURL
          const res = graph.toDataURL(mime, '#f5f7fa')
          resolve(res)
        }
      } catch (err) {
        reject(err)
      }
    })

    if (!dataUrl) {
      ElMessage.error('生成图片失败')
      return
    }

    const blob = dataUrlToBlob(dataUrl)
    const result = await saveBlobAs(blob, fileName, [
      {
        description: ext === 'jpg' ? 'JPEG 图片' : 'PNG 图片',
        accept: { [mime]: [`.${ext}`] }
      }
    ])

    if (result.cancelled) {
      ElMessage.info('已取消导出')
      return
    }
    if (result.mode === 'picker') {
      ElMessage.success(`已保存到：${result.name}`)
    } else {
      ElMessage.success(`已导出 ${result.name}（当前浏览器不支持选择路径，已写入下载目录）`)
    }
  } catch (error) {
    console.error('导出 PNG 失败:', error)
    ElMessage.error('导出失败: ' + (error.message || '未知错误'))
  }
}

// 导出拓扑数据
const exportTopologyData = async () => {
  const devicesWithPos = devices.value.map((d) => {
    let x
    let y
    if (graph) {
      try {
        const item = graph.findById(String(d.id))
        if (item) {
          const m = item.getModel()
          x = m.x
          y = m.y
        }
      } catch (e) {}
    }
    if (x == null || y == null) {
      try {
        const saved = localStorage.getItem(`node-position-${d.id}`)
        if (saved) {
          const pos = JSON.parse(saved)
          x = pos.x
          y = pos.y
        }
      } catch (e) {}
    }
    return {
      ...d,
      x: typeof x === 'number' ? Math.round(x) : undefined,
      y: typeof y === 'number' ? Math.round(y) : undefined
    }
  })

  const data = {
    devices: devicesWithPos,
    links: links.value,
    exportTime: new Date().toISOString(),
    version: '1.0'
  }

  const fileName = `topology-data-${new Date().toISOString().slice(0, 10)}.json`
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })

  try {
    const result = await saveBlobAs(blob, fileName, [
      {
        description: 'JSON 文件',
        accept: { 'application/json': ['.json'] }
      }
    ])
    if (result.cancelled) {
      ElMessage.info('已取消导出')
      return
    }
    if (result.mode === 'picker') {
      ElMessage.success(`已保存到：${result.name}`)
    } else {
      ElMessage.success(`拓扑数据已导出（当前浏览器不支持选择路径，已写入下载目录）`)
    }
  } catch (error) {
    ElMessage.error('导出失败: ' + (error.message || '未知错误'))
  }
}

const handleResize = () => {
  if (!graph || !graphContainer.value) return
  const w = graphContainer.value.offsetWidth
  const h = graphContainer.value.offsetHeight
  if (w > 0 && h > 0) {
    graph.changeSize(w, h)
  }
}

watch(sidebarCollapsed, () => {
  setTimeout(handleResize, 220)
})

// 关闭抽屉时停止设备性能数据轮询，但保留画布选中态
watch(deviceDrawerVisible, (visible) => {
  if (!visible) {
    stopDeviceMonitorTimer()
    setPageContext({ page: 'topology' })
    if (pathHighlight.value.nodes.length) {
      applyPathHighlight()
    } else if (selectedDevice.value) {
      highlightDeviceNeighbors(selectedDevice.value.id)
    }
  }
})

watch(
  () => [route.query.deviceId, route.query.tab],
  () => {
    if (devices.value.length) applyRouteDeviceFocus()
  }
)

watch(
  () => [route.query.rca, route.query.highlight],
  () => {
    if (devices.value.length && graph) applyRcaHighlightFromRoute()
  }
)

/** 助手工具结果回写本页 */
watch(
  () => pageSink.token,
  async (token) => {
    if (!token || !pageSink.last) return
    const { result, tool, source } = pageSink.last
    if (source === 'topology') return
    if (!result || result.ok === false) return
    const detail = result.detail || {}
    const name = tool || result.tool || ''
    if (detail.navigate && detail.path) {
      if (source === 'assistant') return
      if (detail.openWorkbench || name === 'open_workbench_event') {
        const { offerToolNavigate } = await import('@/composables/useSafeNavigate')
        await offerToolNavigate(router, route, { toolName: name, detail })
        return
      }
      const { offerToolNavigate } = await import('@/composables/useSafeNavigate')
      await offerToolNavigate(router, route, {
        toolName: name,
        detail,
        onSamePage: async (d) => {
          // 同页高亮由拓扑自身逻辑处理
          if (d?.highlight || d?.query?.highlight) {
            /* highlight handled via query if we stay */
          }
        }
      })
      return
    }
    if (['inspect', 'refresh_device', 'refresh_offline', 'ack_noise', 'dispose_incident', 'ack_alarm', 'ping_check', 'probe_device', 'backup', 'pull_live_config'].includes(name)) {
      await loadTopology(true)
      const did = detail.deviceId != null ? Number(detail.deviceId) : Number(selectedDevice.value?.id)
      if (Number.isFinite(did) && selectedDevice.value?.id === did && deviceDrawerVisible.value) {
        hubTabsLoaded.value.delete(`${did}:${hubTab.value}`)
        await loadHubTabData(hubTab.value, did, true)
      }
    }
  }
)

onMounted(async () => {
  setPageContext({ page: 'topology' })
  await nextTick()
  initGraph()
  window.addEventListener('resize', handleResize)
  window.addEventListener('click', hideCtxMenu)
  if (autoDiscoverEnabled.value && auth.hasPermission('topology:write')) {
    startAutoDiscoverTimer()
  }
})

useThemeSync(() => {
  if (!graph) return
  const nodes = graph.getNodes?.() || []
  nodes.forEach((item) => {
    const model = item.getModel?.()
    if (model) patchNodeAppearance(item, model)
  })
  graph.paint?.()
})

onUnmounted(() => {
  clearPageContext()
  clearTimeout(searchDebounceTimer)
  window.removeEventListener('resize', handleResize)
  window.removeEventListener('click', hideCtxMenu)
  stopTopologyTimer()
  stopAutoDiscoverTimer()
  stopDeviceMonitorTimer()
  stopAlertPulse()
  stopDiscoverProgressTimer()
  if (discoverAbortController) {
    discoverAbortController.abort()
    discoverAbortController = null
  }
  if (graph) {
    graph.destroy()
    graph = null
  }
})
</script>

<style scoped>
.topology-container {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 56px);
  margin: -20px -22px;
  background: var(--nms-bg);
  overflow: hidden;
}

/* —— 商业拓扑顶栏：命令行 + 状态条 —— */
.topo-chrome {
  flex-shrink: 0;
  background: #fff;
  border-bottom: 1px solid #dfe5ee;
  box-shadow: 0 1px 0 rgba(16, 24, 40, 0.04);
}

.topo-cmd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: 40px;
  padding: 0 14px;
  background: linear-gradient(180deg, #fbfcfe 0%, #f4f6fa 100%);
  border-bottom: 1px solid #e8edf4;
}

.topo-cmd-left,
.topo-cmd-right {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.topo-cmd-right {
  margin-left: auto;
}

.topo-seg {
  display: inline-flex;
  padding: 2px;
  background: #e8edf4;
  border-radius: 6px;
}

.topo-seg-btn {
  appearance: none;
  border: 0;
  background: transparent;
  color: #5a6a7e;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.02em;
  padding: 4px 12px;
  border-radius: 4px;
  cursor: pointer;
  line-height: 1.2;
  font-family: inherit;
}

.topo-seg-btn.active {
  background: #fff;
  color: #1a2332;
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.1);
}

.topo-vsep {
  width: 1px;
  height: 16px;
  background: #d5dde8;
  flex-shrink: 0;
}

.topo-actions,
.topo-layout-btns {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.topo-action {
  appearance: none;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding: 0 10px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: #3d4b5c;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  font-family: inherit;
}

.topo-action:hover {
  background: rgba(26, 35, 50, 0.06);
  color: #1a2332;
}

.topo-action.active {
  background: var(--nms-primary-soft, #eaf2fd);
  color: var(--nms-primary, #2f6fed);
}

.topo-action.busy,
.topo-action:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.topo-action.icon-only {
  width: 28px;
  padding: 0;
  justify-content: center;
}

.topo-icon-btn {
  appearance: none;
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: #5a6a7e;
  cursor: pointer;
  padding: 0;
}

.topo-icon-btn:hover {
  background: rgba(26, 35, 50, 0.06);
  color: #1a2332;
}

.topo-icon-btn.active {
  background: var(--nms-primary-soft, #eaf2fd);
  color: var(--nms-primary, #2f6fed);
}

.topo-search {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  width: 180px;
  padding: 0 10px;
  background: #fff;
  border: 1px solid #d5dde8;
  border-radius: 5px;
}

.topo-search:focus-within {
  border-color: var(--nms-primary, #2f6fed);
  box-shadow: 0 0 0 2px rgba(47, 111, 237, 0.12);
}

.topo-search-icon {
  color: #8a97a8;
  font-size: 14px;
  flex-shrink: 0;
}

.topo-search input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: none;
  background: transparent;
  font-size: 12px;
  color: #1a2332;
  font-family: inherit;
}

.topo-search input::placeholder {
  color: #9aa6b5;
}

.topo-status {
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  gap: 12px;
  min-height: 36px;
  padding: 0 8px 0 6px;
  background: #f7f8fb;
}

.topo-metrics {
  display: flex;
  align-items: stretch;
  min-width: 0;
  overflow-x: auto;
}

.topo-metric {
  appearance: none;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 36px;
  padding: 0 14px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: #5a6a7e;
  cursor: pointer;
  font-family: inherit;
  white-space: nowrap;
}

.topo-metric.readonly {
  cursor: default;
  pointer-events: none;
}

.topo-metric + .topo-metric {
  border-left: 1px solid #e4e9f0;
}

.topo-metric:hover:not(.readonly) {
  background: rgba(255, 255, 255, 0.7);
  color: #1a2332;
}

.topo-metric.active {
  background: #fff;
  border-bottom-color: var(--nms-primary, #2f6fed);
  color: #1a2332;
}

.topo-metric-label {
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: inherit;
  opacity: 0.78;
}

.topo-metric-value {
  font-size: 14px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  font-family: 'IBM Plex Mono', Consolas, monospace;
  color: #1a2332;
  line-height: 1;
}

.topo-metric-value.warn {
  color: #c73a2e;
}

.topo-metric-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

.topo-metric-dot.on {
  background: #1f9d6a;
}

.topo-metric-dot.off {
  background: #a0aaba;
}

.topo-status-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
  padding-right: 4px;
}

.topo-field {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: #6b7a8d;
  font-weight: 500;
}

.topo-check {
  cursor: pointer;
  user-select: none;
}
.topo-check input {
  margin: 0;
  accent-color: var(--nms-primary, #2f6fed);
}
.topo-empty-hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  max-width: 320px;
  line-height: 1.5;
}
.topo-link-hint {
  position: absolute;
  left: 50%;
  bottom: 16px;
  transform: translateX(-50%);
  z-index: 6;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 14px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid var(--el-border-color);
  box-shadow: 0 4px 16px rgba(15, 35, 60, 0.12);
  font-size: 12px;
  color: var(--el-text-color-regular);
  max-width: min(520px, 92%);
}

.topo-field select {
  height: 26px;
  padding: 0 22px 0 8px;
  border: 1px solid #d5dde8;
  border-radius: 4px;
  background: #fff url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6'%3E%3Cpath d='M0 0l5 6 5-6z' fill='%238a97a8'/%3E%3C/svg%3E") no-repeat right 8px center;
  appearance: none;
  font-size: 12px;
  color: #1a2332;
  font-family: inherit;
  cursor: pointer;
  min-width: 72px;
}

.topo-field select:focus {
  outline: none;
  border-color: var(--nms-primary, #2f6fed);
}

.topo-countdown {
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  font-family: 'IBM Plex Mono', Consolas, monospace;
  color: var(--nms-primary, #2f6fed);
  min-width: 28px;
}

.topology-canvas.is-monitor {
  cursor: grab;
}

.discover-scope-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.topology-content {
  display: flex;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.topology-canvas-wrapper {
  flex: 1;
  position: relative;
  min-width: 0;
  background: #fff;
  background-image:
    linear-gradient(rgba(48, 65, 86, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(48, 65, 86, 0.04) 1px, transparent 1px);
  background-size: 24px 24px;
  border-right: 1px solid #e6e6e6;
}

.topology-canvas {
  width: 100%;
  height: 100%;
  cursor: default;
}

.zoom-control {
  position: absolute;
  right: 16px;
  bottom: 16px;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  background: rgba(255, 255, 255, 0.95);
  border: 1px solid #e6e6e6;
  border-radius: 6px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.selection-chip {
  position: absolute;
  left: 16px;
  bottom: 16px;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 240px;
  max-width: 360px;
  padding: 10px 12px;
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid var(--el-color-primary);
  border-radius: 10px;
  box-shadow: 0 4px 14px rgba(64, 158, 255, 0.18);
}

.selection-chip-icon {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
}

.selection-chip-body {
  flex: 1;
  min-width: 0;
}

.selection-chip-label {
  font-size: 11px;
  color: var(--el-color-primary);
  font-weight: 600;
  letter-spacing: 0.02em;
}

.selection-chip-name {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.selection-chip-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 2px;
  font-size: 12px;
  color: #606266;
  font-family: Consolas, monospace;
}

.selection-chip-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  flex-shrink: 0;
}

.path-chip {
  top: 72px;
}

.path-arrow {
  margin: 0 4px;
  color: #909399;
  font-weight: 400;
}

.edge-hover-tip {
  position: absolute;
  z-index: 20;
  max-width: 320px;
  padding: 6px 10px;
  border-radius: 6px;
  background: rgba(20, 28, 40, 0.88);
  color: #fff;
  font-size: 12px;
  line-height: 1.45;
  pointer-events: none;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.18);
}

.layout-import-input {
  display: none;
}

.discover-overlay {
  flex-direction: column;
  gap: 4px;
}

.discover-elapsed {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}

.zoom-label {
  font-size: 12px;
  color: #606266;
  min-width: 40px;
  text-align: center;
}

.topology-sidebar {
  width: 260px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px;
  overflow-y: auto;
  background: #f4f6f9;
  border-left: 1px solid var(--nms-border-soft, #e8eef5);
  transition: width 0.2s ease, padding 0.2s ease, opacity 0.2s ease;
}

.topology-sidebar.collapsed {
  width: 0;
  padding: 0;
  opacity: 0;
  overflow: hidden;
  border-left: none;
  pointer-events: none;
}

.sidebar-card {
  border: 1px solid var(--nms-border-soft, #e8eef5);
  box-shadow: none;
  border-radius: 8px;
}

.device-card {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.device-card :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  padding: 8px 10px;
}

.legend-card :deep(.el-card__body) {
  padding: 10px 12px;
}

.legend-compact {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.legend-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--nms-text-secondary, #5c6b7f);
}

.status-dot-inline {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.status-dot-inline.on {
  background: var(--nms-success, #1f9d6a);
  box-shadow: 0 0 0 2px rgba(31, 157, 106, 0.18);
}
.status-dot-inline.off {
  background: #c0c8d4;
}

.alert-badge-inline {
  margin-left: 2px;
}

.topo-overlay.soft {
  background: rgba(244, 246, 249, 0.72);
}

:deep(.topo-minimap) {
  position: absolute !important;
  right: 14px;
  bottom: 64px;
  z-index: 6;
  background: rgba(255, 255, 255, 0.96) !important;
  border: 1px solid var(--nms-border-soft, #e8eef5) !important;
  border-radius: 8px !important;
  box-shadow: var(--nms-shadow);
  overflow: hidden;
}

.sidebar-card :deep(.el-card__header) {
  padding: 12px 14px;
  border-bottom: 1px solid #ebeef5;
  background: #fff;
}

.sidebar-card :deep(.el-card__body) {
  padding: 12px 14px;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  color: #303133;
  font-size: 14px;
}

.device-count {
  margin-left: auto;
  font-weight: 400;
  color: #909399;
  font-size: 12px;
}

.minimap-canvas {
  width: 100%;
  height: 160px;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  overflow: hidden;
}

.device-tree {
  max-height: 280px;
  overflow-y: auto;
}

.tree-node-content {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
}

.device-icon-small {
  width: 18px;
  height: 18px;
}

.device-name {
  flex: 1;
  font-size: 13px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.legend-content {
  padding: 0;
}

.legend-group {
  margin-bottom: 10px;
}

.legend-group:last-child {
  margin-bottom: 0;
}

.legend-title {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
  font-weight: 500;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 13px;
  color: #606266;
}

.legend-icon {
  width: 22px;
  height: 22px;
}

.legend-line {
  width: 28px;
  height: 3px;
  border-radius: 2px;
}

.line-up {
  background: var(--el-color-primary);
}

.line-down {
  background: #f56c6c;
}

.line-warning {
  background: #e6a23c;
}

.device-list {
  max-height: 320px;
}

.device-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
  border: 1px solid transparent;
}

.device-item:hover {
  background: #f5f7fa;
}

.device-item.active {
  background: #ecf5ff;
  border-color: var(--el-color-primary);
  box-shadow: inset 3px 0 0 var(--el-color-primary);
}

.device-item.active .device-name {
  color: var(--el-color-primary);
  font-weight: 600;
}

.device-item-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  flex-shrink: 0;
}

.device-item .device-icon-small {
  width: 28px;
  height: 28px;
}

.device-item-info {
  flex: 1;
  min-width: 0;
}

.device-item-name {
  font-size: 13px;
  color: #303133;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.device-item-ip {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.device-detail {
  padding: 0;
}

.device-hub {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.hub-summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.hub-ip {
  font-size: 13px;
  color: #606266;
  font-family: Consolas, monospace;
}

.hub-delete {
  margin-left: auto;
}

.hub-tabs :deep(.el-tabs__content) {
  overflow: auto;
  max-height: calc(100vh - 200px);
}

.hub-pane {
  padding: 4px 0 12px;
}

.hub-pane-ops {
  min-height: 360px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.hub-ops-tip {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.hub-pane-ops :deep(.oap) {
  flex: 1;
  min-height: 360px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.hub-pane-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.hub-section-title {
  margin: 14px 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}

.device-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
  flex-wrap: wrap;
}

.device-monitor {
  margin-top: 16px;
}

.device-monitor h4 {
  margin: 0 0 12px 0;
  color: #303133;
  font-size: 14px;
}

.status-info {
  margin-top: 12px;
}

.link-management {
  padding: 0;
}

.link-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.device-info {
  flex: 1;
  min-width: 0;
}

.device-info .device-name {
  font-size: 13px;
  font-weight: 500;
  color: #303133;
  margin-bottom: 2px;
}

.device-info .device-ip {
  font-size: 11px;
  color: #909399;
}

.tips-card .tips-list {
  font-size: 12px;
  line-height: 1.8;
  color: #606266;
}

.tip-item {
  margin-bottom: 4px;
}

.legend-list .legend-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  font-size: 13px;
  color: #606266;
}

.legend-line.online {
  background: #1f9d6a;
}

.legend-line.busy {
  background: #d48806;
}

.legend-line.congested {
  background: #d4380d;
}

.legend-line.device-offline {
  background: repeating-linear-gradient(
    90deg,
    #c0c4cc 0,
    #c0c4cc 4px,
    transparent 4px,
    transparent 8px
  );
}

.legend-line.offline {
  background: repeating-linear-gradient(
    90deg,
    #d4380d 0,
    #d4380d 4px,
    transparent 4px,
    transparent 8px
  );
}

.legend-alert {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #e6a23c;
  display: inline-block;
  flex-shrink: 0;
}

.legend-alert.severe {
  background: #f56c6c;
  box-shadow: 0 0 0 3px rgba(245, 108, 108, 0.25);
}

.legend-selected {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 3px solid var(--el-color-primary);
  background: #ecf5ff;
  display: inline-block;
  flex-shrink: 0;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.2);
}

.legend-neighbor {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 3px solid #e6a23c;
  background: #fdf6ec;
  display: inline-block;
  flex-shrink: 0;
}

.demo-script-title {
  font-size: 12px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 6px;
}

.topo-overlay {
  position: absolute;
  inset: 0;
  z-index: 8;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  background: rgba(255, 255, 255, 0.82);
  color: #606266;
  font-size: 14px;
}

.overlay-icon {
  font-size: 28px;
  color: var(--el-color-primary);
}

.topo-ctx-menu {
  position: fixed;
  z-index: 4000;
  min-width: 168px;
  padding: 6px 0;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.12);
}

.topo-ctx-menu .ctx-title {
  padding: 6px 14px 8px;
  font-size: 12px;
  color: #909399;
  border-bottom: 1px solid #f0f2f5;
  margin-bottom: 4px;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topo-ctx-menu .ctx-item {
  padding: 8px 14px;
  font-size: 13px;
  color: #303133;
  cursor: pointer;
}

.topo-ctx-menu .ctx-item:hover {
  background: #f5f7fa;
  color: var(--el-color-primary);
}

.topo-ctx-menu .ctx-item.danger {
  color: #f56c6c;
}

.topo-ctx-menu .ctx-item.danger:hover {
  background: #fef0f0;
}
</style>


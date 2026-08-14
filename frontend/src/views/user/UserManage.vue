<template>
  <div class="nms-page user-manage">
    <div class="nms-page-header">
      <div class="nms-page-title-block">
        <h1 class="nms-page-title">用户权限</h1>
        <p class="nms-page-subtitle">账号治理 · 角色授权 · 权限矩阵 · 变更可审计</p>
      </div>
      <div class="nms-page-actions">
        <el-button
          v-if="auth.hasPermission('audit:read')"
          size="small"
          @click="goAuditLog"
        >
          变更日志
        </el-button>
        <el-button size="small" :loading="refreshing" @click="refreshAll">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button
          v-if="activeTab === 'users' && auth.hasPermission('users:manage')"
          type="primary"
          @click="showUserDialog(null)"
        >
          <el-icon><Plus /></el-icon>
          新增用户
        </el-button>
        <el-button
          v-else-if="activeTab === 'roles' && auth.hasPermission('roles:manage')"
          type="primary"
          @click="showRoleDialog(null)"
        >
          <el-icon><Plus /></el-icon>
          新增角色
        </el-button>
        <el-button
          v-else-if="activeTab === 'matrix' && auth.hasPermission('roles:manage')"
          type="primary"
          @click="exportMatrixCsv"
        >
          导出 CSV
        </el-button>
      </div>
    </div>

    <div v-if="auth.hasPermission('users:manage')" class="stat-row">
      <div class="stat-item">
        <span class="stat-num">{{ users.length }}</span>
        <span class="stat-label">用户总数</span>
      </div>
      <div class="stat-item success">
        <span class="stat-num">{{ activeUserCount }}</span>
        <span class="stat-label">启用</span>
      </div>
      <div class="stat-item warn">
        <span class="stat-num">{{ lockedUserCount }}</span>
        <span class="stat-label">锁定</span>
      </div>
      <div class="stat-item">
        <span class="stat-num">{{ roles.length }}</span>
        <span class="stat-label">角色数</span>
      </div>
    </div>

    <el-card class="content-card" shadow="never">
      <el-tabs v-model="activeTab">
        <!-- 账号 -->
        <el-tab-pane v-if="auth.hasPermission('users:manage')" label="账号" name="users">
          <div class="toolbar">
            <el-input
              v-model="userFilter.keyword"
              clearable
              placeholder="用户名 / 姓名 / 邮箱"
              style="width: 200px"
            />
            <el-select v-model="userFilter.status" clearable placeholder="状态" style="width: 110px">
              <el-option label="启用" value="active" />
              <el-option label="禁用" value="disabled" />
              <el-option label="锁定" value="locked" />
            </el-select>
            <el-select v-model="userFilter.roleId" clearable filterable placeholder="角色" style="width: 160px">
              <el-option
                v-for="role in roles"
                :key="role.id"
                :label="role.displayName || role.name"
                :value="role.id"
              />
            </el-select>
          </div>

          <el-table v-loading="userLoading" :data="filteredUsers" style="width: 100%; margin-top: 12px">
            <el-table-column prop="username" label="用户名" width="130">
              <template #default="{ row }">
                <span>{{ row.username }}</span>
                <el-tag v-if="isSelf(row)" size="small" type="warning" class="self-tag">当前</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="realName" label="姓名" width="100" />
            <el-table-column prop="email" label="邮箱" min-width="140" show-overflow-tooltip />
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag v-if="row.locked" size="small" type="danger">锁定</el-tag>
                <el-switch
                  v-else
                  :model-value="row.status === 'active'"
                  :disabled="isSelf(row) || isBuiltinAdmin(row)"
                  inline-prompt
                  active-text="启"
                  inactive-text="禁"
                  @change="(v) => toggleUserStatus(row, v)"
                />
              </template>
            </el-table-column>
            <el-table-column label="角色" min-width="180">
              <template #default="{ row }">
                <span v-if="!row.roles?.length">-</span>
                <el-tag
                  v-for="role in (row.roles || [])"
                  :key="role.id"
                  size="small"
                  :type="role.name === 'ADMIN' ? 'danger' : 'info'"
                  class="role-tag"
                >
                  {{ role.displayName || role.name }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="最近登录" width="160">
              <template #default="{ row }">{{ formatDate(row.lastLoginAt) }}</template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" width="160">
              <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="280" fixed="right">
              <template #default="{ row }">
                <el-button size="small" link @click="showEffectivePerms(row)">生效权限</el-button>
                <el-button size="small" link @click="showUserDialog(row)">编辑</el-button>
                <el-button size="small" link type="primary" @click="showPasswordDialog(row)">重置密码</el-button>
                <el-button
                  v-if="row.locked"
                  size="small"
                  link
                  type="warning"
                  @click="handleUnlock(row)"
                >
                  解锁
                </el-button>
                <el-button
                  size="small"
                  link
                  type="danger"
                  :disabled="isSelf(row) || isBuiltinAdmin(row)"
                  @click="handleDeleteUser(row)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!userLoading && !filteredUsers.length" description="无匹配用户" />
        </el-tab-pane>

        <!-- 角色 -->
        <el-tab-pane v-if="auth.hasPermission('roles:manage')" label="角色" name="roles">
          <el-alert
            type="info"
            :closable="false"
            show-icon
            class="role-hint"
            title="系统内置角色不可删除。新建角色可一次提交权限；修改前将提示影响账号数。系统管理员必须保留「用户管理」「角色权限」。"
          />
          <div class="toolbar" style="margin-top: 12px">
            <el-input
              v-model="roleFilter.keyword"
              clearable
              placeholder="角色编码 / 名称"
              style="width: 220px"
            />
          </div>
          <el-table v-loading="roleLoading" :data="filteredRoles" style="width: 100%; margin-top: 12px">
            <el-table-column prop="name" label="角色编码" width="150">
              <template #default="{ row }">
                <span>{{ row.name }}</span>
                <el-tag v-if="row.preset" size="small" type="warning" class="self-tag">系统内置</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="displayName" label="显示名称" width="130" />
            <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
            <el-table-column label="关联用户" width="90">
              <template #default="{ row }">{{ row.userCount ?? 0 }}</template>
            </el-table-column>
            <el-table-column label="权限完整度" width="130">
              <template #default="{ row }">
                <span>{{ rolePermCount(row) }}/{{ totalPermissionCount }}</span>
                <span class="perm-pct">({{ roleCompleteness(row) }}%)</span>
              </template>
            </el-table-column>
            <el-table-column label="权限预览" min-width="240">
              <template #default="{ row }">
                <el-tag
                  v-for="p in (row.permissions || []).slice(0, 3)"
                  :key="p.id"
                  size="small"
                  type="info"
                  class="role-tag"
                >
                  {{ p.displayName || p.name }}
                </el-tag>
                <span v-if="(row.permissions?.length || 0) > 3" class="more-perm">
                  +{{ row.permissions.length - 3 }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="220" fixed="right">
              <template #default="{ row }">
                <el-button size="small" link type="primary" @click="showRoleDialog(row)">配置权限</el-button>
                <el-button size="small" link @click="copyRoleAsNew(row)">复制</el-button>
                <el-button
                  size="small"
                  link
                  type="danger"
                  :disabled="row.preset || (row.userCount || 0) > 0"
                  @click="handleDeleteRole(row)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!roleLoading && !filteredRoles.length" description="无匹配角色" />
        </el-tab-pane>

        <!-- 权限矩阵 -->
        <el-tab-pane
          v-if="auth.hasPermission('roles:manage')"
          label="权限矩阵"
          name="matrix"
        >
          <div class="toolbar">
            <el-select
              v-model="matrixFilter.resource"
              clearable
              placeholder="按资源筛选"
              style="width: 180px"
            >
              <el-option
                v-for="g in permissionGroups"
                :key="g.resource"
                :label="g.label || g.resource"
                :value="g.resource"
              />
            </el-select>
            <el-button size="small" @click="exportMatrixCsv">导出 CSV</el-button>
            <span class="matrix-hint">勾选为只读对照；变更请在「角色」中编辑。系统内置角色已标注。</span>
          </div>
          <div v-loading="roleLoading || permLoading" class="matrix-wrap">
            <table class="perm-matrix">
              <thead>
                <tr>
                  <th class="sticky-col">权限 / 角色</th>
                  <th
                    v-for="role in matrixRoles"
                    :key="role.id"
                    class="role-col"
                  >
                    <div>{{ role.displayName || role.name }}</div>
                    <el-tag v-if="role.preset" size="small" type="warning">系统内置</el-tag>
                  </th>
                </tr>
              </thead>
              <tbody>
                <template v-for="group in filteredMatrixGroups" :key="group.resource">
                  <tr class="group-row">
                    <td :colspan="matrixRoles.length + 1">{{ group.label || group.resource }}</td>
                  </tr>
                  <tr v-for="p in group.permissions" :key="p.id">
                    <td class="sticky-col perm-cell">
                      <div class="perm-name">{{ p.displayName || p.name }}</div>
                      <div class="perm-code">{{ p.name }}</div>
                    </td>
                    <td v-for="role in matrixRoles" :key="role.id" class="check-cell">
                      <el-checkbox :model-value="roleHasPerm(role, p.id)" disabled />
                    </td>
                  </tr>
                </template>
              </tbody>
            </table>
            <el-empty v-if="!filteredMatrixGroups.length" description="无权限数据" />
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 生效权限抽屉 -->
    <el-drawer v-model="effDrawerVisible" :title="`生效权限 — ${effUser?.username || ''}`" size="480px">
      <p class="eff-summary">合并全部角色权限（去重），只读展示</p>
      <div v-loading="effLoading">
        <div v-for="g in effGroups" :key="g.resource" class="eff-group">
          <div class="eff-group-title">{{ g.label || g.resource }}</div>
          <div v-for="p in g.permissions" :key="p.id" class="eff-perm">
            <span class="perm-name">{{ p.displayName || p.name }}</span>
            <span class="perm-code">{{ p.name }}</span>
            <el-tooltip v-if="p.description" :content="p.description" placement="top">
              <span class="eff-desc">说明</span>
            </el-tooltip>
          </div>
        </div>
        <el-empty v-if="!effLoading && !effGroups.length" description="无生效权限" />
      </div>
    </el-drawer>

    <!-- 用户编辑 -->
    <el-dialog
      v-model="userDialogVisible"
      :title="editingUser ? '编辑用户' : '新增用户'"
      width="600px"
      destroy-on-close
    >
      <el-form ref="userFormRef" :model="userForm" :rules="userRules" label-width="90px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="userForm.username" :disabled="!!editingUser" placeholder="登录用户名" />
        </el-form-item>
        <el-form-item v-if="!editingUser" label="密码" prop="password">
          <el-input
            v-model="userForm.password"
            type="password"
            show-password
            placeholder="至少 8 位，须含字母与数字"
          />
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="userForm.realName" placeholder="真实姓名" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="userForm.email" placeholder="可选" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="userForm.phone" placeholder="可选" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="userForm.status" :disabled="editingUser && isSelf(editingUser)">
            <el-radio value="active">启用</el-radio>
            <el-radio value="disabled">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="角色" prop="roleIds">
          <el-select v-model="userForm.roleIds" multiple placeholder="为账号分配角色" style="width: 100%">
            <el-option
              v-for="role in roles"
              :key="role.id"
              :label="role.displayName || role.name"
              :value="role.id"
            >
              <span>{{ role.displayName || role.name }}</span>
              <span class="option-desc">{{ role.description }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="userForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submitUser">确定</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="passwordDialogVisible" title="重置密码" width="460px" destroy-on-close>
      <el-form ref="passwordFormRef" :model="passwordForm" :rules="passwordRules" label-width="90px">
        <el-form-item label="用户名">
          <el-input :model-value="currentUser?.username" disabled />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input
            v-model="passwordForm.newPassword"
            type="password"
            show-password
            placeholder="至少 8 位，须含字母与数字"
          />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="passwordForm.confirmPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submitPassword">确定</el-button>
      </template>
    </el-dialog>

    <!-- 角色权限 -->
    <el-dialog
      v-model="roleDialogVisible"
      :title="editingRole ? `配置权限 — ${editingRole.displayName || editingRole.name}` : '新增角色'"
      width="780px"
      destroy-on-close
    >
      <el-form ref="roleFormRef" :model="roleForm" :rules="roleRules" label-width="100px">
        <el-form-item v-if="!editingRole" label="角色编码" prop="name">
          <el-input v-model="roleForm.name" placeholder="英文大写，如 CUSTOM_OPS" />
        </el-form-item>
        <el-form-item label="显示名称" prop="displayName">
          <el-input v-model="roleForm.displayName" placeholder="如：值班班长" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="roleForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="套用基线">
          <el-select
            v-model="baselineRoleName"
            clearable
            filterable
            placeholder="从系统角色套用权限基线（非演示账号）"
            style="width: 100%"
            @change="applyBaseline"
          >
            <el-option
              v-for="b in baselineRoles"
              :key="b.name"
              :label="`${b.displayName}（${b.permissions?.length || 0} 项）`"
              :value="b.name"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!editingRole" label="复制自">
          <el-select
            v-model="copyFromRoleId"
            clearable
            filterable
            placeholder="可选：从已有角色复制权限"
            style="width: 100%"
            @change="applyCopyFromRole"
          >
            <el-option
              v-for="role in roles"
              :key="role.id"
              :label="`${role.displayName || role.name}（${role.permissions?.length || 0} 项）`"
              :value="role.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="权限">
          <div class="perm-toolbar">
            <el-button size="small" @click="selectAllPerms">全选</el-button>
            <el-button size="small" @click="clearAllPerms">清空</el-button>
            <el-button size="small" @click="selectReadOnlyPerms">仅只读</el-button>
            <span class="perm-count">已选 {{ roleForm.permissionIds.length }} / {{ allPermissionIds().length }}</span>
          </div>
          <el-alert
            v-if="editingRole?.name === 'ADMIN'"
            type="warning"
            :closable="false"
            show-icon
            style="margin-bottom: 8px; width: 100%"
            title="ADMIN 角色必须保留「用户管理」与「角色权限」，否则可能无法继续管理系统。"
          />
          <div v-loading="permLoading" class="perm-groups">
            <div v-for="group in permissionGroups" :key="group.resource" class="perm-group">
              <div class="perm-group-title">
                <el-checkbox
                  :model-value="isGroupChecked(group)"
                  :indeterminate="isGroupIndeterminate(group)"
                  @change="(v) => toggleGroup(group, v)"
                >
                  {{ group.label }}
                  <span class="group-count">({{ groupSelectedCount(group) }}/{{ group.permissions?.length || 0 }})</span>
                </el-checkbox>
              </div>
              <el-checkbox-group v-model="roleForm.permissionIds" class="perm-checks">
                <el-checkbox
                  v-for="p in group.permissions"
                  :key="p.id"
                  :label="p.id"
                  :disabled="isAdminLockedPerm(p)"
                >
                  <el-tooltip
                    :content="p.description || p.name"
                    placement="top"
                    :disabled="!p.description"
                  >
                    <span>
                      <span class="perm-name">{{ p.displayName }}</span>
                      <span class="perm-code">{{ p.name }}</span>
                    </span>
                  </el-tooltip>
                </el-checkbox>
              </el-checkbox-group>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submitRole">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { userApi, roleApi, permissionApi } from '@/api/device'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

const BASELINE_NAMES = ['VIEWER', 'ALARM_DUTY', 'CONFIG_ADMIN', 'OPERATOR']

const activeTab = ref(auth.hasPermission('users:manage') ? 'users' : 'roles')
const users = ref([])
const roles = ref([])
const permissionGroups = ref([])
const userLoading = ref(false)
const roleLoading = ref(false)
const permLoading = ref(false)
const submitLoading = ref(false)
const refreshing = ref(false)

const userFilter = ref({ keyword: '', status: '', roleId: null })
const roleFilter = ref({ keyword: '' })
const matrixFilter = ref({ resource: '' })
const copyFromRoleId = ref(null)
const baselineRoleName = ref(null)

const userDialogVisible = ref(false)
const passwordDialogVisible = ref(false)
const roleDialogVisible = ref(false)
const editingUser = ref(null)
const editingRole = ref(null)
const currentUser = ref(null)

const effDrawerVisible = ref(false)
const effUser = ref(null)
const effGroups = ref([])
const effLoading = ref(false)

const userFormRef = ref(null)
const passwordFormRef = ref(null)
const roleFormRef = ref(null)

const userForm = ref({
  username: '',
  password: '',
  realName: '',
  email: '',
  phone: '',
  status: 'active',
  description: '',
  roleIds: []
})

const passwordForm = ref({ newPassword: '', confirmPassword: '' })

const roleForm = ref({
  name: '',
  displayName: '',
  description: '',
  permissionIds: []
})

const validatePasswordComplexity = (_rule, value, callback) => {
  if (!value) {
    callback(new Error('请输入密码'))
    return
  }
  if (value.length < 8) {
    callback(new Error('密码至少 8 位'))
    return
  }
  if (!/[A-Za-z]/.test(value) || !/\d/.test(value)) {
    callback(new Error('密码须同时包含字母与数字'))
    return
  }
  callback()
}

const validateConfirmPassword = (_rule, value, callback) => {
  if (value !== passwordForm.value.newPassword) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const userRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 2, max: 50, message: '长度 2~50', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { validator: validatePasswordComplexity, trigger: 'blur' }
  ],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }],
  roleIds: [{ type: 'array', required: true, min: 1, message: '请至少选择一个角色', trigger: 'change' }]
}

const passwordRules = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { validator: validatePasswordComplexity, trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    { validator: validateConfirmPassword, trigger: 'blur' }
  ]
}

const roleRules = {
  name: [
    { required: true, message: '请输入角色编码', trigger: 'blur' },
    {
      pattern: /^[A-Za-z][A-Za-z0-9_]{1,31}$/,
      message: '英文开头，仅字母数字下划线，2~32 位',
      trigger: 'blur'
    }
  ],
  displayName: [{ required: true, message: '请输入显示名称', trigger: 'blur' }]
}

const activeUserCount = computed(() => users.value.filter(u => u.status === 'active' && !u.locked).length)
const lockedUserCount = computed(() => users.value.filter(u => u.locked).length)

const totalPermissionCount = computed(() =>
  permissionGroups.value.reduce((n, g) => n + (g.permissions?.length || 0), 0)
)

const baselineRoles = computed(() =>
  roles.value.filter(r => BASELINE_NAMES.includes(r.name))
)

const matrixRoles = computed(() => roles.value)

const filteredMatrixGroups = computed(() => {
  const res = matrixFilter.value.resource
  if (!res) return permissionGroups.value
  return permissionGroups.value.filter(g => g.resource === res)
})

const filteredUsers = computed(() => {
  let list = users.value
  const kw = (userFilter.value.keyword || '').trim().toLowerCase()
  if (kw) {
    list = list.filter(u =>
      (u.username || '').toLowerCase().includes(kw)
      || (u.realName || '').toLowerCase().includes(kw)
      || (u.email || '').toLowerCase().includes(kw)
    )
  }
  if (userFilter.value.status === 'locked') {
    list = list.filter(u => u.locked)
  } else if (userFilter.value.status) {
    list = list.filter(u => u.status === userFilter.value.status && !u.locked)
  }
  if (userFilter.value.roleId) {
    list = list.filter(u => (u.roles || []).some(r => r.id === userFilter.value.roleId))
  }
  return list
})

const filteredRoles = computed(() => {
  const kw = (roleFilter.value.keyword || '').trim().toLowerCase()
  if (!kw) return roles.value
  return roles.value.filter(r =>
    (r.name || '').toLowerCase().includes(kw)
    || (r.displayName || '').toLowerCase().includes(kw)
    || (r.description || '').toLowerCase().includes(kw)
  )
})

function formatDate(dateStr) {
  if (!dateStr) return '-'
  return String(dateStr).replace('T', ' ').slice(0, 19)
}

function isSelf(user) {
  return !!user && user.username === auth.user?.username
}

function isBuiltinAdmin(user) {
  return !!user && user.username === 'admin'
}

function isAdminLockedPerm(p) {
  if (!editingRole.value || editingRole.value.name !== 'ADMIN') return false
  return p.name === 'users:manage' || p.name === 'roles:manage'
}

function rolePermCount(role) {
  return role.permissions?.length || 0
}

function roleCompleteness(role) {
  const total = totalPermissionCount.value
  if (!total) return 0
  return Math.round((rolePermCount(role) / total) * 100)
}

function roleHasPerm(role, permId) {
  return (role.permissions || []).some(p => p.id === permId)
}

function goAuditLog() {
  ElMessageBox.confirm(
    '将打开日志中心查看用户/角色变更记录，是否继续？',
    '打开日志中心',
    { type: 'info', confirmButtonText: '前往', cancelButtonText: '取消' }
  ).then(() => {
    router.push({ path: '/audit', query: { tab: 'ops', module: 'user' } })
  }).catch(() => {})
}

async function loadUsers() {
  if (!auth.hasPermission('users:manage')) return
  userLoading.value = true
  try {
    const res = await userApi.getUsers()
    users.value = Array.isArray(res) ? res : (res?.data || [])
  } catch {
    ElMessage.error('加载用户失败')
  } finally {
    userLoading.value = false
  }
}

async function loadRoles() {
  roleLoading.value = true
  try {
    const res = await roleApi.getRoles()
    roles.value = Array.isArray(res) ? res : (res?.data || [])
  } catch {
    ElMessage.error('加载角色失败')
  } finally {
    roleLoading.value = false
  }
}

async function loadPermissionGroups() {
  if (!auth.hasPermission('roles:manage')) return
  permLoading.value = true
  try {
    const res = await permissionApi.getPermissionGroups()
    permissionGroups.value = Array.isArray(res) ? res : []
  } catch {
    ElMessage.error('加载权限目录失败')
  } finally {
    permLoading.value = false
  }
}

async function refreshAll() {
  refreshing.value = true
  try {
    await Promise.all([loadUsers(), loadRoles(), loadPermissionGroups()])
  } finally {
    refreshing.value = false
  }
}

async function refreshSessionIfNeeded(affectedUsernames = []) {
  try {
    const me = auth.user?.username
    const selfAffected = !affectedUsernames.length || (me && affectedUsernames.includes(me))
    // 角色权限变更会 bump 全局版本，操作者需立即换票
    await auth.fetchMe()
    if (selfAffected) {
      ElMessage.info('会话权限已刷新；其他在线会话需重新登录')
    }
  } catch {
    // 401 拦截器会处理
  }
}

function showUserDialog(user) {
  editingUser.value = user
  if (user) {
    userForm.value = {
      username: user.username,
      password: '',
      realName: user.realName || '',
      email: user.email || '',
      phone: user.phone || '',
      status: user.status || 'active',
      description: user.description || '',
      roleIds: (user.roles || []).map(r => r.id)
    }
  } else {
    userForm.value = {
      username: '',
      password: '',
      realName: '',
      email: '',
      phone: '',
      status: 'active',
      description: '',
      roleIds: []
    }
  }
  userDialogVisible.value = true
}

function showPasswordDialog(user) {
  currentUser.value = user
  passwordForm.value = { newPassword: '', confirmPassword: '' }
  passwordDialogVisible.value = true
}

async function showEffectivePerms(user) {
  effUser.value = user
  effDrawerVisible.value = true
  effLoading.value = true
  effGroups.value = []
  try {
    const res = await userApi.getEffectivePermissions(user.id)
    effGroups.value = res?.groups || []
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '加载生效权限失败')
  } finally {
    effLoading.value = false
  }
}

function showRoleDialog(role) {
  editingRole.value = role
  copyFromRoleId.value = null
  baselineRoleName.value = null
  if (role) {
    roleForm.value = {
      name: role.name,
      displayName: role.displayName || '',
      description: role.description || '',
      permissionIds: (role.permissions || []).map(p => p.id)
    }
  } else {
    roleForm.value = {
      name: '',
      displayName: '',
      description: '',
      permissionIds: []
    }
  }
  roleDialogVisible.value = true
  if (!permissionGroups.value.length) loadPermissionGroups()
}

function copyRoleAsNew(role) {
  editingRole.value = null
  copyFromRoleId.value = role.id
  baselineRoleName.value = null
  roleForm.value = {
    name: '',
    displayName: `${role.displayName || role.name}_副本`,
    description: role.description || '',
    permissionIds: (role.permissions || []).map(p => p.id)
  }
  roleDialogVisible.value = true
  if (!permissionGroups.value.length) loadPermissionGroups()
}

function applyCopyFromRole(roleId) {
  if (!roleId) return
  const role = roles.value.find(r => r.id === roleId)
  if (!role) return
  roleForm.value.permissionIds = (role.permissions || []).map(p => p.id)
  if (!roleForm.value.displayName) {
    roleForm.value.displayName = `${role.displayName || role.name}_副本`
  }
}

function applyBaseline(name) {
  if (!name) return
  const role = roles.value.find(r => r.name === name)
  if (!role) return
  roleForm.value.permissionIds = (role.permissions || []).map(p => p.id)
}

function allPermissionIds() {
  return permissionGroups.value.flatMap(g => (g.permissions || []).map(p => p.id))
}

function selectAllPerms() {
  roleForm.value.permissionIds = allPermissionIds()
}

function clearAllPerms() {
  if (editingRole.value?.name === 'ADMIN') {
    const locked = permissionGroups.value
      .flatMap(g => g.permissions || [])
      .filter(p => p.name === 'users:manage' || p.name === 'roles:manage')
      .map(p => p.id)
    roleForm.value.permissionIds = locked
    return
  }
  roleForm.value.permissionIds = []
}

function selectReadOnlyPerms() {
  const ids = permissionGroups.value
    .flatMap(g => g.permissions || [])
    .filter(p => p.action === 'read' || p.name?.endsWith(':read'))
    .map(p => p.id)
  roleForm.value.permissionIds = ids
}

function groupSelectedCount(group) {
  const ids = (group.permissions || []).map(p => p.id)
  return ids.filter(id => roleForm.value.permissionIds.includes(id)).length
}

function isGroupChecked(group) {
  const ids = (group.permissions || []).map(p => p.id)
  return ids.length > 0 && ids.every(id => roleForm.value.permissionIds.includes(id))
}

function isGroupIndeterminate(group) {
  const ids = (group.permissions || []).map(p => p.id)
  const n = ids.filter(id => roleForm.value.permissionIds.includes(id)).length
  return n > 0 && n < ids.length
}

function toggleGroup(group, checked) {
  const ids = (group.permissions || []).map(p => p.id)
  const set = new Set(roleForm.value.permissionIds)
  if (checked) ids.forEach(id => set.add(id))
  else {
    ids.forEach(id => {
      const p = (group.permissions || []).find(x => x.id === id)
      if (isAdminLockedPerm(p)) return
      set.delete(id)
    })
  }
  roleForm.value.permissionIds = [...set]
}

async function toggleUserStatus(user, enabled) {
  const status = enabled ? 'active' : 'disabled'
  try {
    await userApi.updateUser(user.id, {
      realName: user.realName,
      email: user.email,
      phone: user.phone,
      status,
      description: user.description,
      roles: (user.roles || []).map(r => ({ id: r.id, name: r.name }))
    })
    user.status = status
    ElMessage.success(enabled ? '已启用' : '已禁用')
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '状态更新失败')
  }
}

async function handleUnlock(user) {
  try {
    await userApi.unlockUser(user.id)
    ElMessage.success('已解锁')
    await loadUsers()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '解锁失败')
  }
}

async function submitUser() {
  try {
    await userFormRef.value?.validate()
  } catch {
    return
  }
  submitLoading.value = true
  try {
    const selected = userForm.value.roleIds
      .map(id => roles.value.find(r => r.id === id))
      .filter(Boolean)
      .map(r => ({ id: r.id, name: r.name }))
    const data = {
      username: userForm.value.username,
      password: userForm.value.password,
      realName: userForm.value.realName,
      email: userForm.value.email,
      phone: userForm.value.phone,
      status: userForm.value.status,
      description: userForm.value.description,
      roles: selected
    }
    if (editingUser.value) {
      delete data.password
      const oldRoleIds = (editingUser.value.roles || []).map(r => r.id).sort().join(',')
      const newRoleIds = [...userForm.value.roleIds].sort().join(',')
      await userApi.updateUser(editingUser.value.id, data)
      ElMessage.success('用户已更新')
      if (isSelf(editingUser.value) || oldRoleIds !== newRoleIds) {
        await refreshSessionIfNeeded([editingUser.value.username])
      }
    } else {
      await userApi.createUser(data)
      ElMessage.success('用户已创建')
    }
    userDialogVisible.value = false
    await loadUsers()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    submitLoading.value = false
  }
}

async function submitPassword() {
  try {
    await passwordFormRef.value?.validate()
  } catch {
    return
  }
  submitLoading.value = true
  try {
    await userApi.updatePassword(currentUser.value.id, passwordForm.value.newPassword)
    ElMessage.success('密码已重置')
    passwordDialogVisible.value = false
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '重置失败')
  } finally {
    submitLoading.value = false
  }
}

async function submitRole() {
  try {
    await roleFormRef.value?.validate()
  } catch {
    return
  }

  const userCount = editingRole.value?.userCount || 0
  if (editingRole.value && userCount > 0) {
    try {
      await ElMessageBox.confirm(
        `将影响 ${userCount} 个账号的有效权限，是否继续？`,
        '确认变更影响面',
        { type: 'warning', confirmButtonText: '继续保存', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }

  submitLoading.value = true
  try {
    if (editingRole.value) {
      await roleApi.updateRole(editingRole.value.id, {
        displayName: roleForm.value.displayName,
        description: roleForm.value.description,
        permissionIds: roleForm.value.permissionIds
      })
      ElMessage.success('角色权限已保存')
      await refreshSessionIfNeeded()
    } else {
      await roleApi.createRole({
        name: roleForm.value.name.trim().toUpperCase(),
        displayName: roleForm.value.displayName,
        description: roleForm.value.description,
        permissionIds: roleForm.value.permissionIds
      })
      ElMessage.success('角色已创建')
      if (roleForm.value.permissionIds.length) {
        await refreshSessionIfNeeded()
      }
    }
    roleDialogVisible.value = false
    await loadRoles()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '保存角色失败')
  } finally {
    submitLoading.value = false
  }
}

function handleDeleteUser(user) {
  if (isSelf(user) || isBuiltinAdmin(user)) return
  ElMessageBox.confirm(`确定删除用户 ${user.username}？此操作不可恢复。`, '删除用户', { type: 'warning' })
    .then(async () => {
      try {
        await userApi.deleteUser(user.id)
        ElMessage.success('已删除')
        loadUsers()
      } catch (e) {
        ElMessage.error(e?.response?.data?.message || '删除失败')
      }
    })
    .catch(() => {})
}

function handleDeleteRole(role) {
  if (role.preset) return
  if ((role.userCount || 0) > 0) {
    ElMessage.warning('该角色仍有用户绑定，请先调整用户角色')
    return
  }
  ElMessageBox.confirm(`确定删除角色 ${role.displayName || role.name}？`, '删除角色', { type: 'warning' })
    .then(async () => {
      try {
        await roleApi.deleteRole(role.id)
        ElMessage.success('已删除')
        await refreshSessionIfNeeded()
        loadRoles()
      } catch (e) {
        ElMessage.error(e?.response?.data?.message || '删除失败')
      }
    })
    .catch(() => {})
}

function exportMatrixCsv() {
  const roleList = matrixRoles.value
  const groups = filteredMatrixGroups.value
  if (!roleList.length || !groups.length) {
    ElMessage.warning('暂无矩阵数据可导出')
    return
  }
  const header = ['权限编码', '权限名称', '资源', ...roleList.map(r => r.displayName || r.name)]
  const lines = [header.map(csvEscape).join(',')]
  for (const g of groups) {
    for (const p of g.permissions || []) {
      const row = [
        p.name,
        p.displayName || p.name,
        g.label || g.resource,
        ...roleList.map(r => (roleHasPerm(r, p.id) ? 'Y' : 'N'))
      ]
      lines.push(row.map(csvEscape).join(','))
    }
  }
  const blob = new Blob(['\uFEFF' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `permission-matrix-${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success('已导出权限矩阵 CSV')
}

function csvEscape(v) {
  const s = String(v ?? '')
  if (/[",\n]/.test(s)) return `"${s.replace(/"/g, '""')}"`
  return s
}

onMounted(async () => {
  await refreshAll()
})
</script>

<style scoped>
.user-manage {
  min-height: calc(100vh - 100px);
}

.stat-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 14px;
}

.stat-item {
  background: #fff;
  border-radius: 8px;
  padding: 14px 16px;
  border: 1px solid #ebeef5;
}

.stat-num {
  display: block;
  font-size: 24px;
  font-weight: 700;
  color: #303133;
}

.stat-item.success .stat-num { color: #67c23a; }
.stat-item.warn .stat-num { color: #e6a23c; }
.stat-item.muted .stat-num { color: #909399; }

.stat-label {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
  display: block;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.role-hint {
  margin-bottom: 4px;
}

.role-tag {
  margin-right: 4px;
  margin-bottom: 2px;
}

.self-tag {
  margin-left: 6px;
}

.more-perm {
  color: #909399;
  font-size: 12px;
}

.perm-pct {
  margin-left: 4px;
  color: #909399;
  font-size: 12px;
}

.option-desc {
  float: right;
  color: #a8abb2;
  font-size: 12px;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.form-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
}

.perm-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  width: 100%;
}

.perm-count {
  margin-left: auto;
  color: #909399;
  font-size: 13px;
}

.perm-groups {
  width: 100%;
  max-height: 420px;
  overflow: auto;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 8px 12px;
}

.perm-group {
  padding: 8px 0;
  border-bottom: 1px solid #f2f3f5;
}

.perm-group:last-child {
  border-bottom: none;
}

.perm-group-title {
  font-weight: 600;
  margin-bottom: 6px;
  color: #303133;
}

.group-count {
  margin-left: 6px;
  font-weight: 400;
  color: #909399;
  font-size: 12px;
}

.perm-checks {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-left: 22px;
}

.perm-name {
  margin-right: 8px;
}

.perm-code {
  color: #c0c4cc;
  font-size: 12px;
  font-family: Consolas, monospace;
}

.matrix-hint {
  color: #909399;
  font-size: 12px;
}

.matrix-wrap {
  margin-top: 12px;
  overflow: auto;
  max-height: calc(100vh - 320px);
  border: 1px solid #ebeef5;
  border-radius: 4px;
}

.perm-matrix {
  border-collapse: collapse;
  min-width: 100%;
  font-size: 13px;
}

.perm-matrix th,
.perm-matrix td {
  border: 1px solid #ebeef5;
  padding: 8px 10px;
  text-align: center;
  white-space: nowrap;
  background: #fff;
}

.perm-matrix th {
  background: #f5f7fa;
  font-weight: 600;
  position: sticky;
  top: 0;
  z-index: 2;
}

.perm-matrix .sticky-col {
  position: sticky;
  left: 0;
  z-index: 1;
  text-align: left;
  min-width: 220px;
  background: #fff;
}

.perm-matrix th.sticky-col {
  z-index: 3;
  background: #f5f7fa;
}

.perm-matrix .group-row td {
  background: #f0f2f5;
  text-align: left;
  font-weight: 600;
}

.perm-cell .perm-code {
  display: block;
  margin-top: 2px;
}

.role-col {
  min-width: 100px;
}

.check-cell {
  width: 72px;
}

.eff-summary {
  margin: 0 0 12px;
  color: #909399;
  font-size: 13px;
}

.eff-group {
  margin-bottom: 14px;
}

.eff-group-title {
  font-weight: 600;
  margin-bottom: 6px;
  color: #303133;
}

.eff-perm {
  padding: 4px 0 4px 8px;
  border-left: 2px solid #e4e7ed;
  margin-bottom: 4px;
}

.eff-desc {
  margin-left: 8px;
  color: #409eff;
  font-size: 12px;
  cursor: help;
}

@media (max-width: 768px) {
  .stat-row {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>

<template>
  <div class="aiops-policy" v-loading="loading">
    <el-alert type="info" :closable="false" show-icon class="mb"
      title="策略护栏：Webhook 通知、超时升级与无人值守白名单。设备改配仍须人工确认（除非明确允许自动备份）。" />
    <el-form label-width="160px" size="small" class="pol-form">
      <el-divider content-position="left">通知与升级</el-divider>
      <el-form-item label="启用 Webhook">
        <el-switch v-model="form.webhookEnabled" />
      </el-form-item>
      <el-form-item label="Webhook URL">
        <el-input v-model="form.webhookUrl" placeholder="https://..." clearable />
      </el-form-item>
      <el-form-item label="最低推送级别">
        <el-select v-model="form.webhookMinSeverity" style="width: 140px">
          <el-option label="CRITICAL" value="CRITICAL" />
          <el-option label="MAJOR" value="MAJOR" />
          <el-option label="WARNING" value="WARNING" />
          <el-option label="INFO" value="INFO" />
        </el-select>
      </el-form-item>
      <el-form-item label="超时升级">
        <el-switch v-model="form.escalationEnabled" />
      </el-form-item>
      <el-form-item label="升级超时(分钟)">
        <el-input-number v-model="form.escalationMinutes" :min="5" :max="1440" />
      </el-form-item>
      <el-form-item label="升级时推送">
        <el-switch v-model="form.escalationNotify" />
      </el-form-item>

      <el-divider content-position="left">无人值守</el-divider>
      <el-form-item label="运维模式">
        <el-radio-group v-model="form.llmOpsMode">
          <el-radio label="manual">人工运维</el-radio>
          <el-radio label="unattended">无人值守</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="运营暂停">
        <el-switch v-model="form.unattendedPaused" />
      </el-form-item>
      <el-form-item label="每轮事件上限">
        <el-input-number v-model="form.unattendedMaxPerCycle" :min="1" :max="20" />
      </el-form-item>
      <el-form-item label="单事件最多步数">
        <el-input-number v-model="form.unattendedMaxSteps" :min="1" :max="10" />
      </el-form-item>
      <el-form-item label="冷却(分钟)">
        <el-input-number v-model="form.unattendedCooldownMinutes" :min="1" :max="120" />
      </el-form-item>
      <el-form-item label="关联后自动处置">
        <el-switch v-model="form.unattendedOnCorrelate" />
      </el-form-item>
      <el-form-item label="自动确认连带">
        <el-switch v-model="form.autoAckSecondary" />
      </el-form-item>
      <el-form-item label="允许自动备份">
        <el-switch v-model="form.unattendedAllowBackup" />
      </el-form-item>
      <el-form-item label="LLM 熔断阈值">
        <el-input-number v-model="form.llmCircuitFailThreshold" :min="1" :max="20" />
      </el-form-item>
      <el-form-item label="熔断窗口(分)">
        <el-input-number v-model="form.llmCircuitMinutes" :min="1" :max="240" />
      </el-form-item>
      <el-form-item label="运行记录保留(天)">
        <el-input-number v-model="form.unattendedRunRetentionDays" :min="1" :max="365" />
      </el-form-item>
      <el-form-item label="风暴窗口(分)">
        <el-input-number v-model="form.stormWindowMinutes" :min="1" :max="120" />
      </el-form-item>
      <el-form-item label="基线最少样本">
        <el-input-number v-model="form.anomalyMinSamples" :min="3" :max="60" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        <el-button @click="load">重置</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { ref, onMounted, inject } from 'vue'
import { ElMessage } from 'element-plus'
import { aiopsApi } from '@/api/device'

const hubRefresh = inject('aiopsHubRefresh', null)
const loading = ref(false)
const saving = ref(false)
const form = ref({
  webhookEnabled: false,
  webhookUrl: '',
  webhookMinSeverity: 'MAJOR',
  escalationEnabled: false,
  escalationMinutes: 30,
  escalationNotify: true,
  llmOpsMode: 'manual',
  unattendedPaused: false,
  unattendedMaxPerCycle: 3,
  unattendedMaxSteps: 3,
  unattendedCooldownMinutes: 10,
  unattendedOnCorrelate: false,
  unattendedAllowBackup: false,
  autoAckSecondary: false,
  llmCircuitFailThreshold: 3,
  llmCircuitMinutes: 15,
  unattendedRunRetentionDays: 30,
  stormWindowMinutes: 10,
  anomalyMinSamples: 5
})

async function load() {
  loading.value = true
  try {
    const p = await aiopsApi.getPolicy()
    form.value = {
      ...form.value,
      webhookEnabled: !!p.webhookEnabled,
      webhookUrl: p.webhookUrl || '',
      webhookMinSeverity: p.webhookMinSeverity || 'MAJOR',
      escalationEnabled: !!p.escalationEnabled,
      escalationMinutes: p.escalationMinutes ?? 30,
      escalationNotify: p.escalationNotify !== false,
      llmOpsMode: p.llmOpsMode === 'unattended' ? 'unattended' : 'manual',
      unattendedPaused: !!p.unattendedPaused,
      unattendedMaxPerCycle: p.unattendedMaxPerCycle ?? 3,
      unattendedMaxSteps: p.unattendedMaxSteps ?? 3,
      unattendedCooldownMinutes: p.unattendedCooldownMinutes ?? 10,
      unattendedOnCorrelate: !!p.unattendedOnCorrelate,
      unattendedAllowBackup: !!p.unattendedAllowBackup,
      autoAckSecondary: !!p.autoAckSecondary,
      llmCircuitFailThreshold: p.llmCircuitFailThreshold ?? 3,
      llmCircuitMinutes: p.llmCircuitMinutes ?? 15,
      unattendedRunRetentionDays: p.unattendedRunRetentionDays ?? 30,
      stormWindowMinutes: p.stormWindowMinutes ?? 10,
      anomalyMinSamples: p.anomalyMinSamples ?? 5
    }
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    await aiopsApi.updatePolicy({ ...form.value })
    ElMessage.success('策略已保存')
    hubRefresh?.()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.mb { margin-bottom: 16px; }
.pol-form { max-width: 640px; }
</style>

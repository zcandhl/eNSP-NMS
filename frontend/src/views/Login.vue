<template>
  <div class="login-page">
    <div class="login-bg" aria-hidden="true">
      <div class="grid" />
      <div class="orb orb-a" />
      <div class="orb orb-b" />
    </div>

    <div class="login-top">
      <ThemePicker placement="bottom-start" />
    </div>

    <div class="login-layout">
      <aside class="login-hero">
        <div class="hero-brand">
          <div class="hero-mark">N</div>
          <div>
            <div class="hero-name">eNSP NMS</div>
            <div class="hero-tag">Enterprise Network Manager</div>
          </div>
        </div>
        <h2 class="hero-title">实验室网络运维<br />一站式管理平台</h2>
        <ul class="hero-points">
          <li>设备发现 · 状态探测 · 分组资产</li>
          <li>SNMP 性能 · Trap 告警 · 拓扑可视</li>
          <li>配置备份 · WebSSH · 智能运维</li>
        </ul>
        <p class="hero-foot">面向 eNSP 实验环境的教学网管产品</p>
        <div v-if="license" class="hero-license">
          <div>{{ license.customer || '授权客户未配置' }}</div>
          <div class="hero-license-meta">
            {{ license.sku || '教学标准版' }}
            <template v-if="license.expires"> · 授权至 {{ license.expires }}</template>
          </div>
        </div>
      </aside>

      <div class="login-card">
        <div class="card-head">
          <h1>{{ forceChange ? '首次登录改密' : '登录系统' }}</h1>
          <p>{{ forceChange ? '默认口令须立即更换后方可进入系统' : '使用运维账号进入网络管理系统' }}</p>
        </div>
        <el-alert
          v-if="license?.expired === true"
          type="error"
          :closable="false"
          show-icon
          class="license-alert"
          title="授权已到期，请联系供应商续期（课堂功能仍可登录使用）"
        />
        <el-alert
          v-else-if="licenseNearExpiry"
          type="warning"
          :closable="false"
          show-icon
          class="license-alert"
          :title="`授权将在 ${license.daysRemaining} 天后到期，请联系供应商续期`"
        />

        <el-form v-if="!forceChange" ref="formRef" :model="form" :rules="rules" @keyup.enter="onSubmit">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="用户名" prefix-icon="User" size="large" />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="密码"
              prefix-icon="Lock"
              size="large"
              show-password
            />
          </el-form-item>
          <el-button type="primary" size="large" class="submit-btn" :loading="loading" @click="onSubmit">
            进入工作台
          </el-button>
        </el-form>

        <el-form v-else ref="pwdRef" :model="pwdForm" :rules="pwdRules" @keyup.enter="onChangePassword">
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            class="pwd-alert"
            title="安全要求：请设置至少 8 位且含字母与数字的新密码"
          />
          <el-form-item prop="newPassword">
            <el-input v-model="pwdForm.newPassword" type="password" show-password size="large" placeholder="新密码" />
          </el-form-item>
          <el-form-item prop="confirmPassword">
            <el-input v-model="pwdForm.confirmPassword" type="password" show-password size="large" placeholder="确认新密码" />
          </el-form-item>
          <el-button type="primary" size="large" class="submit-btn" :loading="loading" @click="onChangePassword">
            保存并进入
          </el-button>
        </el-form>

        <p v-if="!forceChange" class="hint">默认账号：admin / Admin@123（首次登录须改密）</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useLicenseInfo } from '@/composables/useLicenseInfo'
import ThemePicker from '@/components/ThemePicker.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const { info: license, load: loadLicense } = useLicenseInfo()

const formRef = ref(null)
const pwdRef = ref(null)
const loading = ref(false)
const forceChange = ref(false)
const licenseNearExpiry = computed(() => {
  const d = license.value?.daysRemaining
  return license.value?.expired === false && typeof d === 'number' && d >= 0 && d <= 30
})
const form = reactive({
  username: 'admin',
  password: ''
})
const pwdForm = reactive({
  newPassword: '',
  confirmPassword: ''
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const pwdRules = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    {
      validator: (_r, v, cb) => {
        if (!v || v.length < 8) return cb(new Error('至少 8 位'))
        if (!/[A-Za-z]/.test(v) || !/\d/.test(v)) return cb(new Error('须同时包含字母与数字'))
        cb()
      },
      trigger: 'blur'
    }
  ],
  confirmPassword: [
    {
      validator: (_r, v, cb) => {
        if (v !== pwdForm.newPassword) return cb(new Error('两次输入不一致'))
        cb()
      },
      trigger: 'blur'
    }
  ]
}

onMounted(() => {
  loadLicense()
  try {
    const hint = sessionStorage.getItem('ensp_nms_login_hint')
    if (hint) {
      sessionStorage.removeItem('ensp_nms_login_hint')
      ElMessage.warning(hint)
    }
  } catch {
    // ignore
  }
  if (auth.isAuthenticated && auth.user?.mustChangePassword) {
    forceChange.value = true
  }
})

const goHome = () => {
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  router.replace(redirect || '/')
}

const onSubmit = async () => {
  await formRef.value?.validate().catch(() => Promise.reject())
  loading.value = true
  try {
    const data = await auth.login(form.username, form.password)
    if (data?.mustChangePassword) {
      forceChange.value = true
      ElMessage.warning('请先修改默认密码')
      return
    }
    ElMessage.success('登录成功')
    goHome()
  } catch (e) {
    const msg = e?.response?.data?.message || '登录失败'
    ElMessage.error(msg)
  } finally {
    loading.value = false
  }
}

const onChangePassword = async () => {
  await pwdRef.value?.validate().catch(() => Promise.reject())
  loading.value = true
  try {
    await auth.changePassword(form.password || '', pwdForm.newPassword)
    ElMessage.success('密码已更新')
    forceChange.value = false
    goHome()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '改密失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--nms-bg, #f0f3f8);
}
.login-bg {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
}
.login-top {
  position: absolute;
  top: 16px;
  right: 20px;
  z-index: 2;
}
.login-layout {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: 1.1fr 0.9fr;
  width: min(920px, 94vw);
  background: #fff;
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(15, 35, 60, 0.12);
}
.login-hero {
  padding: 40px 36px;
  background: linear-gradient(145deg, #1a3a5c 0%, #0f2740 100%);
  color: #fff;
}
.hero-brand {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 28px;
}
.hero-mark {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: #3d8bfd;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
}
.hero-name { font-weight: 700; font-size: 16px; }
.hero-tag { font-size: 12px; opacity: 0.7; }
.hero-title {
  font-size: 28px;
  line-height: 1.35;
  margin: 0 0 20px;
  font-weight: 650;
}
.hero-points {
  margin: 0;
  padding-left: 18px;
  line-height: 1.9;
  opacity: 0.9;
  font-size: 14px;
}
.hero-foot {
  margin-top: 28px;
  font-size: 12px;
  opacity: 0.55;
}
.hero-license {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid rgba(255, 255, 255, 0.15);
  font-size: 13px;
  opacity: 0.85;
}
.hero-license-meta {
  margin-top: 4px;
  font-size: 12px;
  opacity: 0.7;
}
.license-alert {
  margin-bottom: 16px;
}
.login-card {
  padding: 40px 36px;
}
.card-head h1 {
  margin: 0 0 6px;
  font-size: 22px;
}
.card-head p {
  margin: 0 0 24px;
  color: #6b7c90;
  font-size: 13px;
}
.submit-btn {
  width: 100%;
  margin-top: 8px;
}
.hint {
  margin-top: 16px;
  font-size: 12px;
  color: #909399;
  text-align: center;
}
.pwd-alert {
  margin-bottom: 16px;
}
@media (max-width: 800px) {
  .login-layout {
    grid-template-columns: 1fr;
  }
  .login-hero {
    display: none;
  }
}
</style>

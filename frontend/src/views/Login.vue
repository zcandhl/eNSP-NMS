/**
 * @file    Login.vue
 * @module  视图页/登录
 * @desc    登录页：账号认证与登录状态跳转。
 *
 * @author  ZC
 * @date    2026-08-15 * @version 1.0.0
 * @requires @element-plus/icons-vue, element-plus, vue, vue-router, @/components/BrandLogo, @/components/ThemePicker, @/stores/auth
 */

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
          <span class="hero-mark"><BrandLogo :size="40" /></span>
          <div>
            <div class="hero-name">预验通</div>
            <div class="hero-tag">园区网工程预实施验证系统</div>
          </div>
        </div>
        <div class="hero-main">
          <h2 class="hero-title">开工之前，<br />先通一遍。</h2>
          <p class="hero-sub">验证一次 · 复用到底</p>
        </div>
        <div class="hero-feats">
          <div class="feat">
            <span class="feat-ico"><el-icon><Connection /></el-icon></span>
            <span>设备纳管 · 拓扑自动上图</span>
          </div>
          <div class="feat">
            <span class="feat-ico"><el-icon><Finished /></el-icon></span>
            <span>检查项逐项判定 · 结果可复检</span>
          </div>
          <div class="feat">
            <span class="feat-ico"><el-icon><Document /></el-icon></span>
            <span>验收报告一键生成 · 留痕取证</span>
          </div>
        </div>
        <div class="hero-wm" aria-hidden="true"><BrandLogo :size="170" /></div>
      </aside>

      <div class="login-card">
        <div class="card-head">
          <h1>{{ forceChange ? '首次登录改密' : '登录系统' }}</h1>
          <p>{{ forceChange ? '默认口令须立即更换后方可进入系统' : '使用运维账号进入预实施验证平台' }}</p>
        </div>
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
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import ThemePicker from '@/components/ThemePicker.vue'
import BrandLogo from '@/components/BrandLogo.vue'
import { Connection, Finished, Document } from '@element-plus/icons-vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const formRef = ref(null)
const pwdRef = ref(null)
const loading = ref(false)
const forceChange = ref(false)
const form = reactive({
  username: '',
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
  background:
    radial-gradient(1100px 700px at 12% -8%, rgba(63, 140, 255, 0.16), transparent 60%),
    radial-gradient(900px 600px at 105% 110%, rgba(34, 197, 220, 0.14), transparent 55%),
    linear-gradient(180deg, #eef3fa 0%, #e7edf6 100%);
}
.login-bg {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
}
.login-bg .grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(31, 74, 120, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(31, 74, 120, 0.05) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(720px 520px at 50% 45%, rgba(0, 0, 0, 0.8), transparent 75%);
  -webkit-mask-image: radial-gradient(720px 520px at 50% 45%, rgba(0, 0, 0, 0.8), transparent 75%);
}
.login-bg .orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(70px);
}
.login-bg .orb-a {
  width: 420px;
  height: 420px;
  left: -120px;
  top: -140px;
  background: rgba(63, 140, 255, 0.2);
}
.login-bg .orb-b {
  width: 380px;
  height: 380px;
  right: -100px;
  bottom: -120px;
  background: rgba(45, 183, 220, 0.18);
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
  padding: 44px 40px;
  background: linear-gradient(
    150deg,
    color-mix(in srgb, var(--el-color-primary) 55%, #071626) 0%,
    color-mix(in srgb, var(--el-color-primary) 22%, #071626) 62%,
#071626 100%
  );
  color: #fff;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
  min-height: 470px;
}
.login-hero::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image: radial-gradient(rgba(255, 255, 255, 0.08) 1px, transparent 1px);
  background-size: 22px 22px;
  mask-image: linear-gradient(160deg, rgba(0, 0, 0, 0.9), transparent 68%);
  pointer-events: none;
}
.hero-brand {
  display: flex;
  gap: 12px;
  align-items: center;
  position: relative;
  z-index: 1;
  margin-bottom: 44px;
}
.hero-mark {
  color: var(--el-color-primary);
  display: flex;
  filter: drop-shadow(0 2px 8px rgba(0, 0, 0, 0.25));
}
.hero-name { font-weight: 700; font-size: 19px; letter-spacing: 1px; }
.hero-tag { font-size: 12px; opacity: 0.7; margin-top: 3px; letter-spacing: 1px; }
.hero-main {
  margin: auto 0;
  position: relative;
  z-index: 1;
}
.hero-title {
  font-size: 30px;
  line-height: 1.5;
  margin: 0 0 18px;
  font-weight: 700;
  letter-spacing: 1.5px;
}
.hero-sub {
  margin: 0;
  font-size: 14px;
  opacity: 0.75;
  letter-spacing: 2px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.hero-sub::before {
  content: '';
  width: 26px;
  height: 3px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.45);
  flex-shrink: 0;
}
.hero-feats {
  position: relative;
  z-index: 1;
  margin-top: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-top: 4px;
  margin-bottom: auto;
}
.feat {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 14px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.07);
  border: 1px solid rgba(255, 255, 255, 0.13);
  font-size: 13px;
  letter-spacing: 0.5px;
}
.feat-ico {
  display: flex;
  color: var(--el-color-primary-light-3);
  font-size: 16px;
}
.hero-wm {
  position: absolute;
  right: -30px;
  bottom: -36px;
  color: var(--el-color-primary);
  opacity: 0.16;
  pointer-events: none;
  mask-image: linear-gradient(315deg, rgba(0, 0, 0, 0.9) 25%, transparent 68%);
  -webkit-mask-image: linear-gradient(315deg, rgba(0, 0, 0, 0.9) 25%, transparent 68%);
}
.login-card {
  padding: 40px 36px;
  display: flex;
  flex-direction: column;
  justify-content: center;
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

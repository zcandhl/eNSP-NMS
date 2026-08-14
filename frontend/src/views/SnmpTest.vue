<template>
  <div class="snmp-test">
    <el-card>
      <template #header>
        <span>SNMP 连接测试工具</span>
      </template>

      <el-form :model="form" label-width="120px">
        <el-form-item label="设备IP">
          <el-input v-model="form.ip" placeholder="192.168.1.1" />
        </el-form-item>
        <el-form-item label="SNMP端口">
          <el-input-number v-model="form.port" :min="1" :max="65535" />
        </el-form-item>
        <el-form-item label="Community">
          <el-input v-model="form.community" placeholder="public" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="testSnmp" :loading="loading">
            测试 SNMP 连接
          </el-button>
          <el-button @click="testPing">测试 Ping</el-button>
        </el-form-item>
      </el-form>

      <el-divider />

      <div v-if="result" class="result">
        <h4>测试结果：</h4>
        <pre>{{ result }}</pre>
      </div>

      <div v-if="error" class="error">
        <h4>错误信息：</h4>
        <pre>{{ error }}</pre>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/api/index'

const form = ref({
  ip: '192.168.1.1',
  port: 161,
  community: 'public'
})

const loading = ref(false)
const result = ref('')
const error = ref('')

const testSnmp = async () => {
  loading.value = true
  result.value = ''
  error.value = ''

  try {
    const data = await request.get('/snmp/test', {
      params: {
        ip: form.value.ip,
        port: form.value.port,
        community: form.value.community
      }
    })

    if (data.success) {
      result.value = JSON.stringify(data.data, null, 2)
      ElMessage.success('SNMP 连接成功！')
    } else {
      error.value = data.error
      ElMessage.error('SNMP 连接失败')
    }
  } catch (err) {
    error.value = err?.response?.data?.message || err.message
    ElMessage.error('测试请求失败')
  } finally {
    loading.value = false
  }
}

const testPing = () => {
  result.value = '请在本地命令行执行：ping ' + form.value.ip
  ElMessage.info('请在本地命令行执行 ping 命令')
}
</script>

<style scoped>
.result pre {
  background: #f5f5f5;
  padding: 10px;
  border-radius: 4px;
  overflow-x: auto;
}
.error pre {
  background: #fff2f0;
  padding: 10px;
  border-radius: 4px;
  color: #f5222d;
  overflow-x: auto;
}
</style>


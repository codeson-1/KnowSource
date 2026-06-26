<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { extractErrorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const loading = ref(false)
const form = reactive({
  username: '',
  email: '',
  password: '',
})

async function submit() {
  loading.value = true
  try {
    await auth.register(form)
    ElMessage.success('注册成功，默认角色为 VIEWER')
    await router.push('/kbs')
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="auth-layout">
    <section class="auth-copy">
      <p class="brand">KnowSource</p>
      <h1>创建演示账号</h1>
      <p>注册用户默认是 VIEWER，可用于验证只读问答链路。上传、发布等写操作需要 EDITOR、OWNER 或 ADMIN 权限。</p>
    </section>

    <section class="auth-panel">
      <el-tabs model-value="register" stretch>
        <el-tab-pane label="注册" name="register" />
      </el-tabs>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" autocomplete="email" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-button type="primary" :loading="loading" native-type="submit" style="width: 100%">注册并进入</el-button>
      </el-form>
      <el-divider />
      <el-button text type="primary" @click="router.push('/login')">已有账号，去登录</el-button>
    </section>
  </main>
</template>

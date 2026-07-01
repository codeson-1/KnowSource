<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { extractErrorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const loading = ref(false)
const form = reactive({
  username: 'demo',
  password: 'demo',
})

async function submit() {
  loading.value = true
  try {
    await auth.login(form)
    ElMessage.success('登录成功')
    await router.push(String(route.query.redirect || '/kbs'))
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
      <h1>企业知识库问答工作台</h1>
      <p>围绕 3 分钟面试演示主链路组织：登录、知识库、文档入库、发布索引、流式问答、引用溯源和问答追踪复盘。</p>
    </section>

    <section class="auth-panel">
      <el-tabs model-value="login" stretch>
        <el-tab-pane label="登录" name="login" />
      </el-tabs>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-button type="primary" :loading="loading" native-type="submit" style="width: 100%">登录</el-button>
      </el-form>
      <el-divider />
      <el-button text type="primary" @click="router.push('/register')">没有账号，去注册</el-button>
    </section>
  </main>
</template>

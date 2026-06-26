<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

async function logout() {
  await auth.logout()
  ElMessage.success('已退出登录')
  await router.push('/login')
}
</script>

<template>
  <main class="page-shell">
    <header class="console-topbar">
      <div class="topbar-title">
        <p class="brand">KnowSource</p>
        <h1>企业知识库问答工作台</h1>
      </div>
      <div class="topbar-actions">
        <el-tag effect="plain">{{ auth.username }} / {{ auth.globalRole }}</el-tag>
        <el-button v-if="auth.globalRole === 'ADMIN'" @click="router.push('/admin/users')">用户管理</el-button>
        <el-button @click="router.push('/kbs')">知识库</el-button>
        <el-button type="info" plain @click="logout">退出</el-button>
      </div>
    </header>
    <slot />
  </main>
</template>

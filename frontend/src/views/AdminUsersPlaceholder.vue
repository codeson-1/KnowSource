<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'

import { createUser, listUsers, updateUserRole } from '@/api/auth'
import { extractErrorMessage } from '@/api/http'
import ConsoleLayout from '@/layouts/ConsoleLayout.vue'
import type { GlobalRole, UserResponse } from '@/types/api'

const users = ref<UserResponse[]>([])
const loading = ref(false)
const submitting = ref(false)
const dialogVisible = ref(false)
const roleOptions: GlobalRole[] = ['ADMIN', 'EDITOR', 'VIEWER']

const form = reactive({
  username: '',
  password: '',
  email: '',
  globalRole: 'VIEWER' as GlobalRole,
})

async function load() {
  loading.value = true
  try {
    users.value = await listUsers()
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.username = ''
  form.password = ''
  form.email = ''
  form.globalRole = 'VIEWER'
}

async function submit() {
  if (!form.username.trim() || !form.password.trim()) {
    ElMessage.warning('请填写用户名和密码')
    return
  }
  submitting.value = true
  try {
    await createUser({
      username: form.username.trim(),
      password: form.password,
      email: form.email.trim() || undefined,
      globalRole: form.globalRole,
    })
    ElMessage.success('用户已创建')
    dialogVisible.value = false
    resetForm()
    await load()
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    submitting.value = false
  }
}

async function changeRole(user: UserResponse, role: GlobalRole) {
  try {
    await updateUserRole(user.id, role)
    ElMessage.success(`${user.username} 已更新为 ${role}`)
    await load()
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
    await load()
  }
}

function formatDate(value: string | null) {
  return value ? new Date(value).toLocaleString() : '-'
}

onMounted(load)
</script>

<template>
  <ConsoleLayout>
    <div class="workspace-layout">
      <section class="content" style="grid-column: 1 / -1">
        <div class="kb-hero">
          <div>
            <h2>用户管理</h2>
            <p>ADMIN 可创建演示用户，并调整全局角色。注册用户默认是 VIEWER。</p>
          </div>
          <div class="toolbar-inline">
            <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
            <el-button type="primary" :icon="Plus" @click="dialogVisible = true">创建用户</el-button>
          </div>
        </div>

        <section class="panel">
          <el-table v-loading="loading" :data="users" stripe>
            <template #empty>
              <div class="empty-state">暂无用户。后端启动时通常会初始化 demo 用户。</div>
            </template>
            <el-table-column prop="username" label="用户名" min-width="180">
              <template #default="{ row }">
                <strong>{{ row.username }}</strong>
              </template>
            </el-table-column>
            <el-table-column prop="email" label="邮箱" min-width="220">
              <template #default="{ row }">{{ row.email || '-' }}</template>
            </el-table-column>
            <el-table-column label="全局角色" width="180">
              <template #default="{ row }">
                <el-select :model-value="row.globalRole" @change="(role: GlobalRole) => changeRole(row, role)">
                  <el-option v-for="role in roleOptions" :key="role" :label="role" :value="role" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="190">
              <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
            </el-table-column>
          </el-table>
        </section>
      </section>
    </div>

    <el-dialog v-model="dialogVisible" title="创建用户" width="520px" destroy-on-close @closed="resetForm">
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="off" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" autocomplete="off" />
        </el-form-item>
        <el-form-item label="全局角色">
          <el-select v-model="form.globalRole" style="width: 100%">
            <el-option v-for="role in roleOptions" :key="role" :label="role" :value="role" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">创建</el-button>
      </template>
    </el-dialog>
  </ConsoleLayout>
</template>

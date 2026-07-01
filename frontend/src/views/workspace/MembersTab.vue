<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'

import { addMember, listMembers, removeMember, updateMember } from '@/api/kbs'
import { extractErrorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { KnowledgeBaseMemberResponse, MemberRole } from '@/types/api'

const props = defineProps<{
  kbId: string
  currentMemberRole: MemberRole | null
}>()
const emit = defineEmits<{ changed: [] }>()

const auth = useAuthStore()
const loading = ref(false)
const submitting = ref(false)
const addDialogVisible = ref(false)
const members = ref<KnowledgeBaseMemberResponse[]>([])
const form = reactive({
  username: '',
  role: 'VIEWER' as MemberRole,
})

const canManage = computed(() => auth.globalRole === 'ADMIN' || props.currentMemberRole === 'OWNER')
const roleOptions: MemberRole[] = ['OWNER', 'EDITOR', 'VIEWER']

function resetForm() {
  form.username = ''
  form.role = 'VIEWER'
}

function openAddDialog() {
  if (!canManage.value) {
    ElMessage.warning('当前角色没有成员管理权限。')
    return
  }
  resetForm()
  addDialogVisible.value = true
}

async function load() {
  loading.value = true
  try {
    members.value = await listMembers(props.kbId)
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!canManage.value) {
    ElMessage.warning('当前角色没有成员管理权限。')
    return
  }
  if (!form.username.trim()) {
    ElMessage.warning('请输入用户名')
    return
  }
  submitting.value = true
  try {
    await addMember(props.kbId, { username: form.username.trim(), role: form.role })
    ElMessage.success('成员已添加或更新')
    addDialogVisible.value = false
    resetForm()
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    submitting.value = false
  }
}

async function changeRole(member: KnowledgeBaseMemberResponse, role: MemberRole) {
  try {
    await updateMember(props.kbId, member.userId, { role })
    ElMessage.success('成员角色已更新')
    await load()
    emit('changed')
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
    await load()
  }
}

async function remove(member: KnowledgeBaseMemberResponse) {
  try {
    await ElMessageBox.confirm(`确认移除成员 ${member.username}？如果这是最后一个 OWNER，后端会拒绝操作。`, '移除成员', {
      type: 'warning',
      confirmButtonText: '移除',
      cancelButtonText: '取消',
    })
    await removeMember(props.kbId, member.userId)
    ElMessage.success('成员已移除')
    await load()
    emit('changed')
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(extractErrorMessage(error))
    }
  }
}

onMounted(load)
</script>

<template>
  <section class="panel">
    <div class="section-heading">
      <div>
        <h3>成员列表</h3>
      </div>
      <div class="toolbar-inline">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" :disabled="!canManage" @click="openAddDialog">添加成员</el-button>
      </div>
    </div>

    <el-alert
      v-if="!canManage"
      title="只有知识库 OWNER 或全局 ADMIN 可以管理成员。"
      type="info"
      :closable="false"
      style="margin-bottom: 12px"
    />

    <el-table v-loading="loading" :data="members" stripe>
      <template #empty>
        <div class="empty-state">暂无成员。创建者通常会作为 OWNER 自动加入。</div>
      </template>
      <el-table-column prop="username" label="用户名" min-width="160">
        <template #default="{ row }">
          <strong>{{ row.username }}</strong>
        </template>
      </el-table-column>
      <el-table-column prop="globalRole" label="全局角色" width="120" />
      <el-table-column label="成员角色" width="180">
        <template #default="{ row }">
          <el-select
            :model-value="row.memberRole"
            :disabled="!canManage"
            @change="(role: MemberRole) => changeRole(row, role)"
          >
            <el-option v-for="role in roleOptions" :key="role" :label="role" :value="role" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="danger" :disabled="!canManage" @click="remove(row)">移除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>

  <el-dialog v-model="addDialogVisible" title="添加成员" width="520px" destroy-on-close @closed="resetForm">
    <p class="muted" style="margin-bottom: 14px">按用户名添加；若已存在则更新成员角色。</p>
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="用户名">
        <el-input v-model="form.username" :disabled="!canManage" placeholder="例如 demo" />
      </el-form-item>
      <el-form-item label="成员角色">
        <el-select v-model="form.role" :disabled="!canManage" style="width: 100%">
          <el-option v-for="role in roleOptions" :key="role" :label="role" :value="role" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="addDialogVisible = false">取消</el-button>
      <el-button type="primary" :icon="Plus" :loading="submitting" :disabled="!canManage" @click="submit">
        添加成员
      </el-button>
    </template>
  </el-dialog>
</template>

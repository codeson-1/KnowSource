<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Edit, Plus, Refresh } from '@element-plus/icons-vue'

import { createKb, deleteKb, listKbs, updateKb } from '@/api/kbs'
import { extractErrorMessage } from '@/api/http'
import ConsoleLayout from '@/layouts/ConsoleLayout.vue'
import { useAuthStore } from '@/stores/auth'
import type { KnowledgeBaseResponse } from '@/types/api'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const creating = ref(false)
const createDialogVisible = ref(false)
const saving = ref(false)
const editDialogVisible = ref(false)
const editingKb = ref<KnowledgeBaseResponse | null>(null)
const kbs = ref<KnowledgeBaseResponse[]>([])
const form = reactive({
  name: '',
  description: '',
})
const editForm = reactive({
  name: '',
  description: '',
})

function resetCreateForm() {
  form.name = ''
  form.description = ''
}

function openCreateDialog() {
  resetCreateForm()
  createDialogVisible.value = true
}

async function load() {
  loading.value = true
  try {
    kbs.value = await listKbs()
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写知识库名称')
    return
  }
  creating.value = true
  try {
    const kb = await createKb({ name: form.name.trim(), description: form.description.trim() })
    ElMessage.success('知识库已创建')
    createDialogVisible.value = false
    resetCreateForm()
    await router.push(`/kbs/${kb.id}`)
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    creating.value = false
  }
}

function openEdit(kb: KnowledgeBaseResponse) {
  editingKb.value = kb
  editForm.name = kb.name
  editForm.description = kb.description || ''
  editDialogVisible.value = true
}

async function saveEdit() {
  if (!editingKb.value || !editForm.name.trim()) {
    ElMessage.warning('请填写知识库名称')
    return
  }
  saving.value = true
  try {
    await updateKb(editingKb.value.id, {
      name: editForm.name.trim(),
      description: editForm.description.trim(),
    })
    ElMessage.success('知识库已更新')
    editDialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    saving.value = false
  }
}

async function remove(kb: KnowledgeBaseResponse) {
  try {
    await ElMessageBox.confirm(`删除知识库 ${kb.name} 会移除其成员、文档、索引和问答追踪记录。确定继续吗？`, '确认删除知识库', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    await deleteKb(kb.id)
    ElMessage.success('知识库已删除')
    await load()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(extractErrorMessage(error))
    }
  }
}

function formatDate(value: string | null) {
  return value ? new Date(value).toLocaleString() : '-'
}

function canManageKb(kb: KnowledgeBaseResponse) {
  return auth.globalRole === 'ADMIN' || kb.memberRole === 'OWNER'
}

function roleTone(role: KnowledgeBaseResponse['memberRole']) {
  if (role === 'OWNER') {
    return 'success'
  }
  if (role === 'EDITOR') {
    return 'warning'
  }
  return ''
}

onMounted(load)
</script>

<template>
  <ConsoleLayout>
    <div class="workspace-layout workspace-layout--single">
      <section class="content">
        <div class="kb-hero">
          <div>
            <h2>知识库列表</h2>
            <p>选择一个知识库进入工作台，继续完成上传、发布、问答和问答追踪复盘。</p>
          </div>
          <div class="toolbar-inline">
            <el-button type="primary" :icon="Plus" :disabled="!auth.canWrite" @click="openCreateDialog">
              创建知识库
            </el-button>
            <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>

        <section class="panel">
          <el-alert
            v-if="!auth.canWrite"
            title="全局 VIEWER 不能创建知识库；你仍可进入已授权知识库，具体操作由每个知识库的成员角色决定。"
            type="info"
            show-icon
            :closable="false"
            style="margin-bottom: 12px"
          />
          <el-skeleton v-if="loading" :rows="5" animated />
          <el-empty v-else-if="!kbs.length" description="暂无可访问知识库" />
          <el-table v-else :data="kbs" stripe>
            <el-table-column prop="name" label="名称" min-width="180" />
            <el-table-column prop="description" label="描述" min-width="260" show-overflow-tooltip />
            <el-table-column label="当前角色" width="130" align="center">
              <template #default="{ row }">
                <span class="status-tag" :class="roleTone(row.memberRole)">{{ row.memberRole }}</span>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="190" align="center">
              <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="220" fixed="right" align="center">
              <template #default="{ row }">
                <el-button type="primary" link @click="router.push(`/kbs/${row.id}`)">进入</el-button>
                <template v-if="canManageKb(row)">
                  <el-button type="primary" link :icon="Edit" @click="openEdit(row)">编辑</el-button>
                  <el-button type="danger" link :icon="Delete" @click="remove(row)">删除</el-button>
                </template>
                <span v-else class="row-action-note">只读</span>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </section>
    </div>

    <el-dialog v-model="createDialogVisible" title="创建知识库" width="520px" destroy-on-close @closed="resetCreateForm">
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="例如：HR 制度库" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="4" placeholder="收录范围、部门或演示说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submit">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editDialogVisible" title="编辑知识库" width="520px" destroy-on-close>
      <el-form label-position="top" @submit.prevent="saveEdit">
        <el-form-item label="名称">
          <el-input v-model="editForm.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :rows="4" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </ConsoleLayout>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Edit, Plus, Refresh } from '@element-plus/icons-vue'

import { createKb, deleteKb, listKbs, updateKb } from '@/api/kbs'
import { extractErrorMessage } from '@/api/http'
import ConsoleLayout from '@/layouts/ConsoleLayout.vue'
import type { KnowledgeBaseResponse } from '@/types/api'

const router = useRouter()
const loading = ref(false)
const creating = ref(false)
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
    await ElMessageBox.confirm(`删除知识库 ${kb.name} 会移除其成员、文档、索引和 QaTrace。确定继续吗？`, '确认删除知识库', {
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

onMounted(load)
</script>

<template>
  <ConsoleLayout>
    <div class="workspace-layout">
      <section class="sidebar">
        <div class="section-heading">
          <div>
            <h2>创建知识库</h2>
            <p>Demo 通常从一个 HR 制度库开始。</p>
          </div>
        </div>
        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item label="名称">
            <el-input v-model="form.name" placeholder="例如：HR 制度库" />
          </el-form-item>
          <el-form-item label="描述">
            <el-input v-model="form.description" type="textarea" :rows="4" placeholder="收录范围、部门或演示说明" />
          </el-form-item>
          <el-button type="primary" :loading="creating" :icon="Plus" native-type="submit" style="width: 100%">创建</el-button>
        </el-form>
      </section>

      <section class="content">
        <div class="kb-hero">
          <div>
            <h2>知识库列表</h2>
            <p>选择一个知识库进入工作台，继续完成上传、发布、问答和 QaTrace 复盘。</p>
          </div>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>

        <section class="panel">
          <el-skeleton v-if="loading" :rows="5" animated />
          <el-empty v-else-if="!kbs.length" description="暂无可访问知识库" />
          <el-table v-else :data="kbs" stripe>
            <el-table-column prop="name" label="名称" min-width="180" />
            <el-table-column prop="description" label="描述" min-width="260" show-overflow-tooltip />
            <el-table-column label="创建时间" width="190">
              <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="210" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link @click="router.push(`/kbs/${row.id}`)">进入</el-button>
                <el-button type="primary" link :icon="Edit" @click="openEdit(row)">编辑</el-button>
                <el-button type="danger" link :icon="Delete" @click="remove(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </section>
    </div>

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

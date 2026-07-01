<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  CircleCheck,
  DocumentAdd,
  Download,
  Refresh,
  Search,
  UploadFilled,
  Warning,
} from '@element-plus/icons-vue'
import type { UploadFile } from 'element-plus'

import {
  archiveDocument,
  createTextDocument,
  deleteDocument,
  getDocumentPreview,
  getIngestTask,
  listChunks,
  listDocuments,
  openSourcePreviewBlob,
  publishDocument,
  replaceTextDocument,
  replaceUploadDocument,
  retryLatestFailedIndex,
  retryIngest,
  revokeSourcePreviewUrl,
  uploadDocument,
} from '@/api/documents'
import { extractErrorMessage } from '@/api/http'
import StatusTag from '@/components/StatusTag.vue'
import { useAuthStore } from '@/stores/auth'
import type {
  DocumentChunkResponse,
  DocumentIngestResponse,
  DocumentPreviewResponse,
  DocumentResponse,
  MemberRole,
} from '@/types/api'

const props = defineProps<{ kbId: string; currentMemberRole: MemberRole | null }>()
const auth = useAuthStore()

const loading = ref(false)
const submitting = ref(false)
const detailLoading = ref(false)
const togglingDocIds = ref<Set<string>>(new Set())
const documents = ref<DocumentResponse[]>([])
const selectedDoc = ref<DocumentResponse | null>(null)
const selectedTask = ref<DocumentIngestResponse | null>(null)
const chunks = ref<DocumentChunkResponse[]>([])
const uploadDialogVisible = ref(false)
const detailVisible = ref(false)
const uploadMode = ref<'file' | 'text'>('file')
const uploadTarget = ref<DocumentResponse | null>(null)
const preview = ref<DocumentPreviewResponse | null>(null)
const pollTimer = ref<number | null>(null)
const keyword = ref('')
const statusFilter = ref('ALL')

const statusFilterOptions = [
  { label: '全部状态', value: 'ALL' },
  { label: '草稿文档', value: 'DRAFT_DOC' },
  { label: '已发布文档', value: 'PUBLISHED_DOC' },
  { label: '索引已同步', value: 'INDEX_SYNCED' },
  { label: '处理中', value: 'PROCESSING' },
  { label: '异常文件', value: 'FAILED_DOC' },
]

const form = reactive({
  title: '',
  content: '',
  file: null as File | null,
})

const canWrite = computed(
  () => auth.canWrite || props.currentMemberRole === 'OWNER' || props.currentMemberRole === 'EDITOR',
)
const busyDocs = computed(() =>
  documents.value.filter(
    (doc) =>
      ['PENDING', 'SYNCING'].includes(doc.indexStatus) ||
      ['PENDING', 'PARSING'].includes(doc.latestIngestStatus || ''),
  ),
)
const visibleDocuments = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  return documents.value.filter((doc) => {
    const matchesText =
      !text ||
      doc.title.toLowerCase().includes(text) ||
      doc.id.toLowerCase().includes(text) ||
      (doc.fileType || '').toLowerCase().includes(text)
    const matchesStatus =
      statusFilter.value === 'ALL' ||
      (statusFilter.value === 'DRAFT_DOC' && doc.status === 'DRAFT') ||
      (statusFilter.value === 'PUBLISHED_DOC' && doc.status === 'PUBLISHED') ||
      (statusFilter.value === 'INDEX_SYNCED' && doc.indexStatus === 'SYNCED') ||
      (statusFilter.value === 'PROCESSING' &&
        (['PENDING', 'SYNCING'].includes(doc.indexStatus) ||
          ['PENDING', 'PARSING'].includes(doc.latestIngestStatus || ''))) ||
      (statusFilter.value === 'FAILED_DOC' &&
        (doc.indexStatus === 'FAILED' || doc.latestIngestStatus === 'FAILED'))
    return matchesText && matchesStatus
  })
})
const searchableCount = computed(() => documents.value.filter(searchable).length)
const failedDocumentCount = computed(
  () => documents.value.filter((doc) => doc.indexStatus === 'FAILED' || doc.latestIngestStatus === 'FAILED').length,
)
const documentStats = computed(() => [
  {
    label: '可检索',
    value: String(searchableCount.value),
    hint: 'PUBLISHED + SYNCED，可直接参与问答',
    tone: 'success',
    icon: CircleCheck,
  },
  {
    label: '异常文件',
    value: String(failedDocumentCount.value),
    hint: failedDocumentCount.value ? '需要重试入库或重试索引' : '当前没有失败文件',
    tone: 'danger',
    icon: Warning,
  },
])

function searchable(doc: DocumentResponse) {
  return doc.status === 'PUBLISHED' && doc.indexStatus === 'SYNCED'
}

function canPublish(doc: DocumentResponse) {
  return (
    canWrite.value &&
    doc.status !== 'PUBLISHED' &&
    doc.indexStatus !== 'SYNCING' &&
    doc.latestIngestStatus === 'READY'
  )
}

function canArchive(doc: DocumentResponse) {
  return canWrite.value && doc.status === 'PUBLISHED'
}

function isDocumentEnabled(doc: DocumentResponse) {
  return doc.status === 'PUBLISHED'
}

function canToggleEnabled(doc: DocumentResponse) {
  if (!canWrite.value || doc.indexStatus === 'SYNCING' || ['PENDING', 'PARSING'].includes(doc.latestIngestStatus || '')) {
    return false
  }
  return doc.status === 'PUBLISHED' || canPublish(doc)
}

function isToggling(doc: DocumentResponse) {
  return togglingDocIds.value.has(doc.id)
}

async function toggleEnabled(doc: DocumentResponse, enabled: string | number | boolean) {
  if (!canToggleEnabled(doc)) {
    return
  }
  togglingDocIds.value = new Set(togglingDocIds.value).add(doc.id)
  try {
    if (enabled) {
      if (!canPublish(doc)) {
        ElMessage.warning('当前文档还不能发布。请确认入库完成且不在索引中。')
        return
      }
      await publishDocument(doc.id)
      ElMessage.success('已启用，正在发布索引')
    } else {
      if (!canArchive(doc)) {
        ElMessage.warning('当前文档还未启用。')
        return
      }
      await ElMessageBox.confirm('关闭启用会下架文档并删除向量，问答将不再检索它。确定继续吗？', '关闭启用', {
        type: 'warning',
        confirmButtonText: '关闭启用',
        cancelButtonText: '取消',
      })
      await archiveDocument(doc.id)
      ElMessage.success('已关闭启用')
    }
    await load()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(extractErrorMessage(error))
    }
  } finally {
    const next = new Set(togglingDocIds.value)
    next.delete(doc.id)
    togglingDocIds.value = next
  }
}

function resetForm() {
  form.title = ''
  form.content = ''
  form.file = null
  uploadMode.value = 'file'
}

function openUploadDialog(target?: DocumentResponse) {
  uploadTarget.value = target || null
  resetForm()
  if (target) {
    form.title = target.title
  }
  uploadDialogVisible.value = true
}

async function load() {
  loading.value = true
  try {
    documents.value = await listDocuments(props.kbId)
    syncPolling()
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    loading.value = false
  }
}

async function submitUpload() {
  if (!canWrite.value) {
    ElMessage.warning('当前角色没有写操作入口。')
    return
  }
  submitting.value = true
  try {
    const target = uploadTarget.value
    if (uploadMode.value === 'file') {
      if (!form.file) {
        ElMessage.warning('请选择文件')
        return
      }
      const title = form.title.trim() || form.file.name
      if (target) {
        await replaceUploadDocument(target.id, { title, file: form.file })
      } else {
        await uploadDocument(props.kbId, { title, file: form.file })
      }
    } else {
      if (!form.title.trim() || !form.content.trim()) {
        ElMessage.warning('请填写标题和内容')
        return
      }
      if (target) {
        await replaceTextDocument(target.id, { title: form.title.trim(), content: form.content.trim() })
      } else {
        await createTextDocument(props.kbId, { title: form.title.trim(), content: form.content.trim() })
      }
    }
    ElMessage.success(target ? '文档已提交替换入库' : '文档已进入异步入库流程')
    uploadDialogVisible.value = false
    resetForm()
    await load()
    if (target) {
      await inspect(target)
    }
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    submitting.value = false
  }
}

async function inspect(doc: DocumentResponse) {
  detailVisible.value = true
  detailLoading.value = true
  selectedDoc.value = doc
  selectedTask.value = doc.latestIngestTaskId
    ? {
        document: doc,
        ingestTaskId: doc.latestIngestTaskId,
        ingestStatus: doc.latestIngestStatus || 'PENDING',
        parentChunkCount: doc.parentChunkCount,
        childChunkCount: doc.childChunkCount,
      }
    : null
  chunks.value = []
  preview.value = null
  try {
    const [latest, task, docChunks] = await Promise.all([
      listDocuments(props.kbId).then((items) => items.find((item) => item.id === doc.id) || doc).catch(() => doc),
      getIngestTask(doc.id).catch(() => null),
      listChunks(doc.id).catch(() => []),
    ])
    selectedDoc.value = latest
    selectedTask.value = task
    chunks.value = docChunks
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    detailLoading.value = false
  }
}

async function openPreview(doc: DocumentResponse, pageNumber?: number | null) {
  try {
    preview.value = await getDocumentPreview(doc.id, pageNumber)
    const url = await openSourcePreviewBlob(preview.value.sourceKey)
    revokeSourcePreviewUrl(url)
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  }
}

async function remove(doc: DocumentResponse) {
  try {
    await ElMessageBox.confirm('删除会移除文档、切块、索引和源文件引用。确定继续吗？', '确认删除', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    await deleteDocument(doc.id)
    ElMessage.success('文档已删除')
    if (selectedDoc.value?.id === doc.id) {
      detailVisible.value = false
      selectedDoc.value = null
      selectedTask.value = null
      chunks.value = []
    }
    await load()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(extractErrorMessage(error))
    }
  }
}

async function retryParse(doc: DocumentResponse) {
  try {
    await retryIngest(doc.id)
    ElMessage.success('已提交入库重试')
    await load()
    await inspect(doc)
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  }
}

async function retryIndex(doc: DocumentResponse) {
  try {
    await retryLatestFailedIndex(doc.id)
    ElMessage.success('已重新排队最新失败索引事件')
    await load()
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  }
}

function onFileChange(file: UploadFile) {
  form.file = file.raw || null
}

function syncPolling() {
  if (busyDocs.value.length && !pollTimer.value) {
    pollTimer.value = window.setInterval(load, 3500)
  } else if (!busyDocs.value.length && pollTimer.value) {
    window.clearInterval(pollTimer.value)
    pollTimer.value = null
  }
}

function formatDate(value: string | null) {
  return value ? new Date(value).toLocaleString() : '-'
}

onMounted(load)
onBeforeUnmount(() => {
  if (pollTimer.value) {
    window.clearInterval(pollTimer.value)
  }
})
</script>

<template>
  <div class="status-card-grid">
    <article
      v-for="stat in documentStats"
      :key="stat.label"
      class="status-card"
      :class="`status-card--${stat.tone}`"
    >
      <div>
        <span>{{ stat.label }}</span>
        <strong>{{ stat.value }}</strong>
        <p>{{ stat.hint }}</p>
      </div>
      <div class="status-card-icon">
        <el-icon><component :is="stat.icon" /></el-icon>
      </div>
    </article>
  </div>

  <section class="filter-bar">
    <div class="filter-group">
      <label>
        <span>搜索</span>
        <el-input v-model="keyword" :prefix-icon="Search" clearable placeholder="按文件名、ID 或类型查找..." />
      </label>
      <label>
        <span>状态</span>
        <el-select v-model="statusFilter" style="width: 168px">
          <el-option
            v-for="option in statusFilterOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </label>
    </div>
    <div class="toolbar-inline">
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" :icon="DocumentAdd" :disabled="!canWrite" @click="openUploadDialog()">上传文档</el-button>
    </div>
  </section>

  <section class="panel inventory-panel">
    <div class="section-heading compact">
      <div>
        <span class="section-kicker">INVENTORY</span>
        <h3>当前筛选命中 <small>共 {{ visibleDocuments.length }} 个文件</small></h3>
      </div>
    </div>
    <el-alert
      v-if="!canWrite"
      title="当前用户不是全局 ADMIN/EDITOR，也不是该知识库 OWNER/EDITOR，写操作入口已禁用。"
      type="info"
      :closable="false"
      style="margin-bottom: 12px"
    />

    <el-table v-loading="loading" :data="visibleDocuments" stripe>
      <template #empty>
        <span />
      </template>
      <el-table-column prop="title" label="标题" min-width="230">
        <template #default="{ row }">
          <strong>{{ row.title }}</strong>
        </template>
      </el-table-column>
      <el-table-column prop="fileType" label="类型" width="100" align="center" />
      <el-table-column label="文档状态" width="130" align="center">
        <template #default="{ row }"><StatusTag :value="row.status" /></template>
      </el-table-column>
      <el-table-column label="索引状态" width="126" align="center">
        <template #default="{ row }"><StatusTag :value="row.indexStatus" /></template>
      </el-table-column>
      <el-table-column label="入库状态" width="120" align="center">
        <template #default="{ row }"><StatusTag :value="row.latestIngestStatus" kind="ingest" /></template>
      </el-table-column>
      <el-table-column label="切块" width="110" align="center">
        <template #default="{ row }">{{ row.parentChunkCount }} / {{ row.childChunkCount }}</template>
      </el-table-column>
      <el-table-column label="检索可用" width="118" align="center">
        <template #default="{ row }"><StatusTag :value="searchable(row) ? '可检索' : '不可检索'" kind="available" /></template>
      </el-table-column>
      <el-table-column label="启用" width="96" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="isDocumentEnabled(row)"
            :disabled="!canToggleEnabled(row)"
            :loading="isToggling(row)"
            aria-label="切换文档启用状态"
            @change="toggleEnabled(row, $event)"
          />
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="76" align="center" />
      <el-table-column label="发布时间" width="170" align="center">
        <template #default="{ row }">{{ formatDate(row.publishedAt) }}</template>
      </el-table-column>
      <el-table-column label="向量同步" width="170" align="center">
        <template #default="{ row }">{{ formatDate(row.vectorsSyncedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="300" fixed="right" align="center">
        <template #default="{ row }">
          <el-button link type="primary" @click="inspect(row)">详情</el-button>
          <el-button link type="primary" :disabled="!canWrite" @click="openUploadDialog(row)">替换</el-button>
          <el-button link type="warning" :disabled="!canWrite" @click="retryParse(row)">重试入库</el-button>
          <el-button link type="warning" :disabled="!canWrite || !row.latestFailedIndexEventId" @click="retryIndex(row)">重试索引</el-button>
          <el-button link type="danger" :disabled="!canWrite" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>

  <el-dialog
    v-model="uploadDialogVisible"
    :title="uploadTarget ? '替换上传' : '上传文档'"
    width="640px"
    destroy-on-close
  >
    <el-alert
      v-if="uploadTarget"
      title="替换成功后会递增文档版本，并回到 DRAFT / NONE，需重新发布到 SYNCED 后才可检索。"
      type="warning"
      :closable="false"
      style="margin-bottom: 12px"
    />
    <el-segmented
      v-model="uploadMode"
      :options="[{ label: '文件上传', value: 'file' }, { label: 'JSON 文本', value: 'text' }]"
      style="margin-bottom: 14px"
    />
    <el-form label-position="top" @submit.prevent="submitUpload">
      <el-form-item label="标题">
        <el-input v-model="form.title" placeholder="默认使用文件名" />
      </el-form-item>
      <template v-if="uploadMode === 'file'">
        <el-form-item label="文件">
          <el-upload
            drag
            :auto-upload="false"
            :limit="1"
            :on-change="onFileChange"
            :on-remove="() => (form.file = null)"
            accept=".txt,.md,.markdown,.pdf,.doc,.docx"
          >
            <el-icon><UploadFilled /></el-icon>
            <div class="el-upload__text">拖入文件或点击选择</div>
            <template #tip>
              <div class="el-upload__tip">支持 txt、md、markdown、pdf、doc、docx。</div>
            </template>
          </el-upload>
        </el-form-item>
      </template>
      <template v-else>
        <el-form-item label="内容">
          <el-input v-model="form.content" type="textarea" :rows="10" placeholder="粘贴已抽取的文档文本" />
        </el-form-item>
      </template>
    </el-form>
    <template #footer>
      <el-button @click="uploadDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submitUpload">
        {{ uploadTarget ? '提交替换' : '提交入库' }}
      </el-button>
    </template>
  </el-dialog>

  <el-drawer v-model="detailVisible" size="62%" destroy-on-close>
    <template #header>
      <div>
        <h3>{{ selectedDoc?.title || '文档详情' }}</h3>
        <p class="muted">元数据、最新入库任务、child chunks 与源文件入口。</p>
      </div>
    </template>

    <el-skeleton v-if="detailLoading" :rows="8" animated />
    <template v-else-if="selectedDoc">
      <div class="metric-grid">
        <div class="metric"><span>文档状态</span><strong>{{ selectedDoc.status }}</strong></div>
        <div class="metric"><span>索引状态</span><strong>{{ selectedDoc.indexStatus }}</strong></div>
        <div class="metric"><span>入库任务</span><strong>{{ selectedTask?.ingestStatus || '-' }}</strong></div>
        <div class="metric"><span>切块</span><strong>{{ selectedTask?.parentChunkCount ?? 0 }} / {{ selectedTask?.childChunkCount ?? chunks.length }}</strong></div>
      </div>

      <el-descriptions :column="2" border style="margin-top: 14px">
        <el-descriptions-item label="文件类型">{{ selectedDoc.fileType || '-' }}</el-descriptions-item>
        <el-descriptions-item label="版本">v{{ selectedDoc.version }}</el-descriptions-item>
        <el-descriptions-item label="创建人">{{ selectedDoc.createdBy }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatDate(selectedDoc.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="发布时间">{{ formatDate(selectedDoc.publishedAt) }}</el-descriptions-item>
        <el-descriptions-item label="向量同步">{{ formatDate(selectedDoc.vectorsSyncedAt) }}</el-descriptions-item>
        <el-descriptions-item label="源文件" :span="2">
          <div class="toolbar-inline">
            <span class="muted">{{ selectedDoc.ossKey }}</span>
            <el-button :icon="Download" size="small" @click="openPreview(selectedDoc)">预览/下载</el-button>
          </div>
        </el-descriptions-item>
      </el-descriptions>

      <div class="section-heading drawer-heading">
        <div>
          <h3>Child chunks</h3>
          <p>{{ chunks.length }} 个切块。发布索引读取当前版本 child chunks。</p>
        </div>
      </div>
      <div v-if="!chunks.length" class="empty-state">暂无切块。若入库任务为 PENDING/PARSING，请等待轮询；FAILED 可重试入库。</div>
      <div v-else class="chunk-list">
        <article v-for="chunk in chunks" :key="chunk.id" class="chunk-item">
          <div class="toolbar-inline">
            <strong>#{{ chunk.chunkIndex }} / {{ chunk.chunkType }}</strong>
            <span class="status-tag">v{{ chunk.docVersion }}</span>
            <span v-if="chunk.pageNumber" class="status-tag">p{{ chunk.pageNumber }}</span>
            <el-button v-if="chunk.pageNumber" link type="primary" @click="openPreview(selectedDoc, chunk.pageNumber)">
              打开页
            </el-button>
          </div>
          <p>{{ chunk.content }}</p>
          <small class="muted">chunkId {{ chunk.id }} / parent {{ chunk.parentChunkId }}</small>
        </article>
      </div>
    </template>
  </el-drawer>
</template>

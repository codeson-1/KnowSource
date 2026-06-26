<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'

import { getTrace, listTraces } from '@/api/traces'
import { extractErrorMessage } from '@/api/http'
import SourceList from '@/components/SourceList.vue'
import type { QaTraceDetailResponse, QaTraceSummaryResponse } from '@/types/api'

const props = defineProps<{
  kbId: string
  refreshKey: number
}>()

const loading = ref(false)
const detailLoading = ref(false)
const traces = ref<QaTraceSummaryResponse[]>([])
const selected = ref<QaTraceDetailResponse | null>(null)
const drawerVisible = ref(false)

async function load() {
  loading.value = true
  try {
    traces.value = await listTraces(props.kbId, 20)
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    loading.value = false
  }
}

async function openTrace(traceId: string) {
  drawerVisible.value = true
  detailLoading.value = true
  selected.value = null
  try {
    selected.value = await getTrace(props.kbId, traceId)
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    detailLoading.value = false
  }
}

function formatDate(value: string | null) {
  return value ? new Date(value).toLocaleString() : '-'
}

watch(() => props.refreshKey, load)
onMounted(load)
</script>

<template>
  <section class="panel">
    <div class="section-heading">
      <div>
        <h3>QaTrace</h3>
        <p>最近 20 次问答链路。列表展示后端摘要字段，完整 timing 在详情中。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="traces" stripe>
      <template #empty>
        <div class="empty-state">暂无 QaTrace。先在 Chat 完成一次问答。</div>
      </template>
      <el-table-column prop="query" label="问题" min-width="260" show-overflow-tooltip />
      <el-table-column prop="ragProfile" label="Profile" width="110" />
      <el-table-column prop="sourceCount" label="Sources" width="96" />
      <el-table-column label="totalMs" width="100">
        <template #default="{ row }">{{ row.totalMs ?? '-' }}</template>
      </el-table-column>
      <el-table-column prop="answerPreview" label="Answer preview" min-width="260" show-overflow-tooltip />
      <el-table-column label="createdAt" width="180">
        <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openTrace(row.id)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>

  <el-drawer v-model="drawerVisible" size="58%" destroy-on-close>
    <template #header>
      <div>
        <h3>Trace 详情</h3>
        <p class="muted">{{ selected?.id || '加载中' }}</p>
      </div>
    </template>

    <el-skeleton v-if="detailLoading" :rows="8" animated />
    <template v-else-if="selected">
      <div class="metric-grid">
        <div class="metric"><span>Total</span><strong>{{ selected.totalMs ?? '-' }} ms</strong></div>
        <div class="metric"><span>Retrieval</span><strong>{{ selected.retrievalMs ?? '-' }} ms</strong></div>
        <div class="metric"><span>Rewrite LLM</span><strong>{{ selected.rewriteLlmMs ?? '-' }} ms</strong></div>
        <div class="metric"><span>LLM</span><strong>{{ selected.llmMs ?? '-' }} ms</strong></div>
        <div class="metric"><span>First Token</span><strong>{{ selected.generationFirstTokenMs ?? '-' }} ms</strong></div>
      </div>

      <el-descriptions :column="1" border style="margin-top: 14px">
        <el-descriptions-item label="原始 query">{{ selected.query }}</el-descriptions-item>
        <el-descriptions-item label="rewrittenQuery">{{ selected.rewrittenQuery || '-' }}</el-descriptions-item>
        <el-descriptions-item label="ragProfile">{{ selected.ragProfile || '-' }}</el-descriptions-item>
        <el-descriptions-item label="sessionId">{{ selected.sessionId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="createdAt">{{ formatDate(selected.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="final answer">{{ selected.answer || '-' }}</el-descriptions-item>
      </el-descriptions>

      <div class="section-heading drawer-heading">
        <div>
          <h3>Retrieved chunks / sources</h3>
          <p>{{ selected.retrievedChunks?.length || 0 }} 条召回来源。</p>
        </div>
      </div>
      <SourceList :sources="selected.retrievedChunks || []" empty-text="没有召回来源，通常对应空上下文拒答。" />

      <div class="section-heading drawer-heading">
        <div>
          <h3>Token usage</h3>
          <p>后端原样记录的 token_usage JSON。</p>
        </div>
      </div>
      <pre class="json-block">{{ JSON.stringify(selected.tokenUsage, null, 2) }}</pre>
    </template>
  </el-drawer>
</template>

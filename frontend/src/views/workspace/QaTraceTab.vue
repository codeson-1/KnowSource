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
        <h3>问答追踪</h3>
        <p>最近 20 次问答链路。列表展示摘要字段，完整耗时明细在详情中。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="traces" stripe>
      <template #empty>
        <div class="empty-state">暂无问答追踪记录。先在知识库问答中完成一次问答。</div>
      </template>
      <el-table-column prop="query" label="问题" min-width="260" show-overflow-tooltip />
      <el-table-column prop="ragProfile" label="RAG 模式" width="110" />
      <el-table-column prop="sourceCount" label="来源数" width="96" />
      <el-table-column label="总耗时" width="100">
        <template #default="{ row }">{{ row.totalMs ?? '-' }}</template>
      </el-table-column>
      <el-table-column prop="answerPreview" label="答案预览" min-width="260" show-overflow-tooltip />
      <el-table-column label="创建时间" width="180">
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
        <h3>追踪详情</h3>
        <p class="muted">{{ selected?.id || '加载中' }}</p>
      </div>
    </template>

    <el-skeleton v-if="detailLoading" :rows="8" animated />
    <template v-else-if="selected">
      <div class="metric-grid">
        <div class="metric"><span>总耗时</span><strong>{{ selected.totalMs ?? '-' }} ms</strong></div>
        <div class="metric"><span>检索耗时</span><strong>{{ selected.retrievalMs ?? '-' }} ms</strong></div>
        <div class="metric"><span>改写耗时</span><strong>{{ selected.rewriteLlmMs ?? '-' }} ms</strong></div>
        <div class="metric"><span>生成耗时</span><strong>{{ selected.llmMs ?? '-' }} ms</strong></div>
        <div class="metric"><span>首 Token 耗时</span><strong>{{ selected.generationFirstTokenMs ?? '-' }} ms</strong></div>
      </div>

      <el-descriptions :column="1" border style="margin-top: 14px">
        <el-descriptions-item label="原始问题">{{ selected.query }}</el-descriptions-item>
        <el-descriptions-item label="改写问题">{{ selected.rewrittenQuery || '-' }}</el-descriptions-item>
        <el-descriptions-item label="RAG 模式">{{ selected.ragProfile || '-' }}</el-descriptions-item>
        <el-descriptions-item label="会话 ID">{{ selected.sessionId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatDate(selected.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="最终答案">{{ selected.answer || '-' }}</el-descriptions-item>
      </el-descriptions>

      <div class="section-heading drawer-heading">
        <div>
          <h3>召回片段 / 引用来源</h3>
          <p>{{ selected.retrievedChunks?.length || 0 }} 条召回来源。</p>
        </div>
      </div>
      <SourceList :sources="selected.retrievedChunks || []" empty-text="没有召回来源，通常对应空上下文拒答。" />

      <div class="section-heading drawer-heading">
        <div>
          <h3>Token 用量</h3>
          <p>后端原样记录的 Token 用量 JSON。</p>
        </div>
      </div>
      <pre class="json-block">{{ JSON.stringify(selected.tokenUsage, null, 2) }}</pre>
    </template>
  </el-drawer>
</template>

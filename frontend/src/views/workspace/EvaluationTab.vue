<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, VideoPlay } from '@element-plus/icons-vue'
import MarkdownIt from 'markdown-it'

import { getGoldenSetReport, runGoldenSet } from '@/api/eval'
import { extractErrorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { EvalReportResponse, EvalRunResponse } from '@/types/api'

const markdown = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
})

const auth = useAuthStore()
const running = ref(false)
const loadingReport = ref(false)
const result = ref<EvalRunResponse | null>(null)
const report = ref<EvalReportResponse | null>(null)
const canRun = computed(() => auth.globalRole === 'ADMIN')
const renderedReport = computed(() => markdown.render(report.value?.markdown || ''))

function pct(value: number | null | undefined) {
  if (value == null) {
    return '-'
  }
  return `${(value * 100).toFixed(1)}%`
}

async function run() {
  running.value = true
  try {
    result.value = await runGoldenSet()
    ElMessage.success('Golden Set 评测完成')
    await loadReport()
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    running.value = false
  }
}

async function loadReport() {
  loadingReport.value = true
  try {
    report.value = await getGoldenSetReport()
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    loadingReport.value = false
  }
}

onMounted(loadReport)
</script>

<template>
  <div class="grid-two">
    <section class="panel">
      <div class="section-heading">
        <div>
          <h3>Golden Set 评测</h3>
          <p>通过后端生产问答路径运行基准集，验证 Recall@5、引用命中和拒答准确率。</p>
        </div>
        <el-button type="primary" :icon="VideoPlay" :loading="running" :disabled="!canRun" @click="run">
          运行评测
        </el-button>
      </div>

      <el-alert
        v-if="!canRun"
        title="Golden Set 评测接口仅 ADMIN 可运行。"
        type="info"
        :closable="false"
        style="margin-bottom: 12px"
      />

      <div class="metric-grid">
        <div class="metric">
          <span>Total cases</span>
          <strong>{{ result?.summary.totalCases ?? '-' }}</strong>
        </div>
        <div class="metric">
          <span>Recall@5</span>
          <strong>{{ pct(result?.summary.recallAt5) }}</strong>
        </div>
        <div class="metric">
          <span>Citation hit rate</span>
          <strong>{{ pct(result?.summary.citationHitRate) }}</strong>
        </div>
        <div class="metric">
          <span>Refusal accuracy</span>
          <strong>{{ pct(result?.summary.refusalAccuracy) }}</strong>
        </div>
      </div>

      <el-table :data="result?.cases || []" stripe style="margin-top: 14px">
        <template #empty>
          <div class="empty-state">运行一次评测后显示 per-case pass/fail。</div>
        </template>
        <el-table-column prop="id" label="Case" width="120" />
        <el-table-column prop="question" label="问题" min-width="240" show-overflow-tooltip />
        <el-table-column prop="profile" label="Profile" width="100" />
        <el-table-column label="Sources" min-width="180">
          <template #default="{ row }">{{ row.sourceTitles.join(', ') || '-' }}</template>
        </el-table-column>
        <el-table-column label="Citation" width="96">
          <template #default="{ row }">
            <span class="status-tag" :class="row.citationHit ? 'success' : 'danger'">
              {{ row.citationHit ? 'hit' : 'miss' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="Refusal" width="104">
          <template #default="{ row }">
            <span class="status-tag" :class="row.refusalCorrect ? 'success' : 'danger'">
              {{ row.refusalCorrect ? 'correct' : 'wrong' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="Result" width="96">
          <template #default="{ row }">
            <span class="status-tag" :class="row.passed ? 'success' : 'danger'">
              {{ row.passed ? 'pass' : 'fail' }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="panel">
      <div class="section-heading">
        <div>
          <h3>report.md</h3>
          <p>{{ report?.reportPath || '最新评测报告' }}</p>
        </div>
        <el-button :icon="Refresh" :loading="loadingReport" @click="loadReport">刷新报告</el-button>
      </div>
      <el-skeleton v-if="loadingReport" :rows="8" animated />
      <div v-else-if="!report?.markdown" class="empty-state">暂无 report.md 内容。</div>
      <div v-else class="markdown-report" v-html="renderedReport" />
    </section>
  </div>
</template>

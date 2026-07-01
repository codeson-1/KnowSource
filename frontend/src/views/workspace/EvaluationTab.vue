<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, VideoPlay, View } from '@element-plus/icons-vue'
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
const reportDrawerVisible = ref(false)
const canRun = computed(() => auth.globalRole === 'ADMIN')
const renderedReport = computed(() => markdown.render(report.value?.markdown || ''))
const parsedReport = computed(() => parseReport(report.value?.markdown || ''))
const latestMetrics = computed(() => ({
  totalCases: result.value ? String(result.value.summary.totalCases) : metricValue('用例总数', 'Total cases'),
  inScopeCases: result.value ? String(result.value.summary.inScopeCases) : metricValue('范围内用例', 'In-scope cases'),
  outOfScopeCases: result.value
    ? String(result.value.summary.outOfScopeCases)
    : metricValue('范围外用例', 'Out-of-scope cases'),
  recallAt5: result.value ? pct(result.value.summary.recallAt5) : metricValue('Recall@5'),
  citationHitRate: result.value
    ? pct(result.value.summary.citationHitRate)
    : metricValue('引用命中率', 'Citation hit rate'),
  refusalAccuracy: result.value
    ? pct(result.value.summary.refusalAccuracy)
    : metricValue('拒答准确率', 'Refusal accuracy'),
}))
const failedCases = computed(() => {
  if (result.value) {
    return result.value.cases
      .filter((item) => !item.passed)
      .map((item) => ({
        id: item.id,
        question: item.question,
        sourceTitles: item.sourceTitles.join(', ') || '-',
      }))
  }
  return parsedReport.value.cases
    .filter((item) => !item.passed)
    .map((item) => ({
      id: item.id,
      question: item.question,
      sourceTitles: item.sourceTitles || '-',
    }))
})
const generatedAtLabel = computed(() => {
  const value = result.value?.generatedAt || report.value?.generatedAt || parsedReport.value.generatedAt
  return formatDate(value)
})
const reportPath = computed(() => result.value?.reportPath || report.value?.reportPath || 'docs/eval/report.md')
const reportStatus = computed(() => (report.value?.markdown || result.value ? '已生成' : '暂无报告'))

interface ParsedReportCase {
  id: string
  question: string
  sourceTitles: string
  passed: boolean
}

function parseReport(markdownText: string) {
  const metrics: Record<string, string> = {}
  const cases: ParsedReportCase[] = []
  const lines = markdownText.split(/\r?\n/)
  let generatedAt: string | null = null
  let inCaseTable = false

  for (const line of lines) {
    const generatedMatch = line.match(/^(?:Generated at|生成时间):\s*(.+)$/)
    if (generatedMatch) {
      generatedAt = generatedMatch[1].trim()
      continue
    }

    const cells = parseMarkdownRow(line)
    if (!cells.length || cells.every((cell) => /^:?-{3,}:?$/.test(cell))) {
      continue
    }

    if (cells[0] === 'Metric' || cells[0] === '指标' || cells[0] === 'ID' || cells[0] === '用例 ID') {
      inCaseTable = cells[0] === 'ID' || cells[0] === '用例 ID'
      continue
    }

    if (!inCaseTable && cells.length >= 2) {
      metrics[cells[0]] = cells[1]
      continue
    }

    if (inCaseTable && cells.length >= 7) {
      cases.push({
        id: cells[0],
        question: cells[2],
        sourceTitles: cells[5],
        passed: isPositive(cells[6]),
      })
    }
  }

  return { generatedAt, metrics, cases }
}

function metricValue(...names: string[]) {
  for (const name of names) {
    const value = parsedReport.value.metrics[name]
    if (value) {
      return value
    }
  }
  return '-'
}

function isPositive(value: string) {
  return ['yes', 'true', '是', '通过'].includes(value.trim().toLowerCase())
}

function parseMarkdownRow(line: string) {
  const trimmed = line.trim()
  if (!trimmed.startsWith('|') || !trimmed.endsWith('|')) {
    return []
  }
  return trimmed
    .slice(1, -1)
    .split('|')
    .map((cell) => cell.trim())
}

function pct(value: number | null | undefined) {
  if (value == null) {
    return '-'
  }
  return `${(value * 100).toFixed(1)}%`
}

function formatDate(value: string | null | undefined) {
  if (!value) {
    return '-'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

async function run() {
  running.value = true
  try {
    result.value = await runGoldenSet()
    ElMessage.success('基准集评测完成')
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
  <div class="eval-grid">
    <section class="panel">
      <div class="section-heading">
        <div>
          <h3>基准集评测</h3>
          <p>通过后端生产问答路径运行基准集，验证 Recall@5、引用命中和拒答准确率。</p>
        </div>
        <el-button type="primary" :icon="VideoPlay" :loading="running" :disabled="!canRun" @click="run">
          运行评测
        </el-button>
      </div>

      <el-alert
        v-if="!canRun"
        title="基准集评测仅 ADMIN 可运行。"
        type="info"
        :closable="false"
        style="margin-bottom: 12px"
      />

      <div class="metric-grid">
        <div class="metric">
          <span>用例总数</span>
          <strong>{{ latestMetrics.totalCases }}</strong>
        </div>
        <div class="metric">
          <span>召回率@5</span>
          <strong>{{ latestMetrics.recallAt5 }}</strong>
        </div>
        <div class="metric">
          <span>引用命中率</span>
          <strong>{{ latestMetrics.citationHitRate }}</strong>
        </div>
        <div class="metric">
          <span>拒答准确率</span>
          <strong>{{ latestMetrics.refusalAccuracy }}</strong>
        </div>
      </div>

      <el-table :data="result?.cases || []" stripe style="margin-top: 14px">
        <template #empty>
          <div class="empty-state">运行一次评测后显示逐条用例结果。</div>
        </template>
        <el-table-column prop="id" label="用例" width="120" />
        <el-table-column prop="question" label="问题" min-width="240" show-overflow-tooltip />
        <el-table-column prop="profile" label="RAG 模式" width="100" />
        <el-table-column label="来源" min-width="180">
          <template #default="{ row }">{{ row.sourceTitles.join(', ') || '-' }}</template>
        </el-table-column>
        <el-table-column label="引用" width="96">
          <template #default="{ row }">
            <span class="status-tag" :class="row.citationHit ? 'success' : 'danger'">
              {{ row.citationHit ? '命中' : '未命中' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="拒答" width="104">
          <template #default="{ row }">
            <span class="status-tag" :class="row.refusalCorrect ? 'success' : 'danger'">
              {{ row.refusalCorrect ? '正确' : '错误' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="96">
          <template #default="{ row }">
            <span class="status-tag" :class="row.passed ? 'success' : 'danger'">
              {{ row.passed ? '通过' : '失败' }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="panel eval-report-panel">
      <div class="section-heading">
        <div>
          <h3>最新报告摘要</h3>
          <p>{{ reportPath }}</p>
        </div>
        <div class="toolbar-inline">
          <el-button :icon="Refresh" :loading="loadingReport" @click="loadReport">刷新</el-button>
          <el-button :icon="View" :disabled="!report?.markdown" @click="reportDrawerVisible = true">查看原始报告</el-button>
        </div>
      </div>
      <el-skeleton v-if="loadingReport" :rows="8" animated />
      <template v-else>
        <div class="report-summary-head">
          <span class="status-tag" :class="report?.markdown || result ? 'success' : ''">{{ reportStatus }}</span>
          <div>
            <strong>{{ generatedAtLabel }}</strong>
            <small>报告生成时间</small>
          </div>
        </div>

        <div class="report-kpi-list">
          <div class="report-kpi-row">
            <span>范围内用例</span>
            <strong>{{ latestMetrics.inScopeCases }}</strong>
          </div>
          <div class="report-kpi-row">
            <span>范围外拒答</span>
            <strong>{{ latestMetrics.outOfScopeCases }}</strong>
          </div>
          <div class="report-kpi-row">
            <span>Recall@5</span>
            <strong>{{ latestMetrics.recallAt5 }}</strong>
          </div>
          <div class="report-kpi-row">
            <span>引用命中率</span>
            <strong>{{ latestMetrics.citationHitRate }}</strong>
          </div>
          <div class="report-kpi-row">
            <span>拒答准确率</span>
            <strong>{{ latestMetrics.refusalAccuracy }}</strong>
          </div>
        </div>

        <div class="report-conclusion">
          <div class="section-heading compact">
            <div>
              <h3>评测结论</h3>
              <p>优先展示需要处理的失败用例，完整记录可在原始报告中查看。</p>
            </div>
          </div>
          <div v-if="failedCases.length" class="report-failure-list">
            <article v-for="item in failedCases.slice(0, 5)" :key="item.id" class="report-failure-row">
              <strong>{{ item.id }}</strong>
              <span>{{ item.question }}</span>
              <small>{{ item.sourceTitles }}</small>
            </article>
          </div>
          <div v-else class="report-pass-state">
            <strong>当前基准集全部通过</strong>
            <span>暂无需要优先排查的失败用例。</span>
          </div>
        </div>
      </template>
    </section>
  </div>

  <el-drawer v-model="reportDrawerVisible" size="62%" destroy-on-close>
    <template #header>
      <div>
        <h3>原始评测报告</h3>
        <p class="muted">{{ reportPath }}</p>
      </div>
    </template>
    <el-skeleton v-if="loadingReport" :rows="8" animated />
    <div v-else-if="!report?.markdown" class="empty-state">暂无评测报告内容。</div>
    <div v-else class="markdown-report markdown-report--drawer" v-html="renderedReport" />
  </el-drawer>
</template>

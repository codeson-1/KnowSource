<script setup lang="ts">
import { ElMessage } from 'element-plus'

import { getDocumentPreview, openSourcePreviewBlob, revokeSourcePreviewUrl } from '@/api/documents'
import { extractErrorMessage } from '@/api/http'
import type { SourceCitation } from '@/types/api'

defineProps<{
  sources: SourceCitation[]
  emptyText?: string
}>()

async function openSource(source: SourceCitation) {
  try {
    const preview = await getDocumentPreview(source.docId, source.pageNumber)
    const url = await openSourcePreviewBlob(preview.sourceKey)
    revokeSourcePreviewUrl(url)
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  }
}
</script>

<template>
  <div v-if="!sources.length" class="empty-state">
    {{ emptyText || '暂无引用来源' }}
  </div>
  <div v-else class="source-list">
    <article v-for="source in sources" :key="`${source.docId}-${source.chunkId}-${source.index}`" class="source-item">
      <strong>[{{ source.index }}] {{ source.title || source.docId }}</strong>
      <p>{{ source.snippet }}</p>
      <small>
        {{ source.pageNumber ? `p${source.pageNumber} / ` : '' }}
        chunk {{ source.chunkIndex }} / chunkId {{ source.chunkId }} / v{{ source.docVersion }} / score {{ source.score.toFixed(3) }}
      </small>
      <el-button link type="primary" @click="openSource(source)">打开源文件</el-button>
    </article>
  </div>
</template>

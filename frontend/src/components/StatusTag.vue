<script setup lang="ts">
const props = defineProps<{
  value: string | null | undefined
  kind?: 'document' | 'index' | 'ingest' | 'available'
}>()

function tone() {
  if (props.kind === 'available') {
    return props.value === '可检索' ? 'success' : 'danger'
  }
  if (['READY', 'SYNCED', 'PUBLISHED'].includes(props.value || '')) {
    return 'success'
  }
  if (['PENDING', 'PARSING', 'SYNCING'].includes(props.value || '')) {
    return 'warning'
  }
  if (['FAILED', 'ARCHIVED'].includes(props.value || '')) {
    return 'danger'
  }
  return ''
}
</script>

<template>
  <span class="status-tag" :class="tone()">{{ value || '-' }}</span>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'

import { getKb, listMembers } from '@/api/kbs'
import { extractErrorMessage } from '@/api/http'
import ConsoleLayout from '@/layouts/ConsoleLayout.vue'
import DocumentsTab from '@/views/workspace/DocumentsTab.vue'
import ChatTab from '@/views/workspace/ChatTab.vue'
import QaTraceTab from '@/views/workspace/QaTraceTab.vue'
import MembersTab from '@/views/workspace/MembersTab.vue'
import EvaluationTab from '@/views/workspace/EvaluationTab.vue'
import { useAuthStore } from '@/stores/auth'
import type { KnowledgeBaseMemberResponse, KnowledgeBaseResponse, MemberRole } from '@/types/api'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const kb = ref<KnowledgeBaseResponse | null>(null)
const members = ref<KnowledgeBaseMemberResponse[]>([])
const loading = ref(false)
const allowedTabs = ['documents', 'chat', 'members', 'traces', 'evaluation']
const activeTab = ref(allowedTabs.includes(String(route.query.tab)) ? String(route.query.tab) : 'documents')
const traceRefreshKey = ref(0)

const kbId = computed(() => String(route.params.kbId))
const currentMemberRole = computed<MemberRole | null>(() => {
  const userId = auth.userId
  return members.value.find((member) => member.userId === userId)?.memberRole || null
})

async function loadKb() {
  loading.value = true
  try {
    const [kbData, memberData] = await Promise.all([
      getKb(kbId.value),
      listMembers(kbId.value).catch(() => []),
    ])
    kb.value = kbData
    members.value = memberData
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    loading.value = false
  }
}

function onChatTraced() {
  traceRefreshKey.value += 1
}

watch(kbId, loadKb)
watch(
  () => route.query.tab,
  (tab) => {
    const next = String(tab || 'documents')
    activeTab.value = allowedTabs.includes(next) ? next : 'documents'
  },
)
watch(activeTab, (tab) => {
  if (route.query.tab !== tab) {
    void router.replace({ query: { ...route.query, tab } })
  }
})
onMounted(loadKb)
</script>

<template>
  <ConsoleLayout>
    <div class="workspace-layout workspace-layout--single" :class="{ 'workspace-layout--chat': activeTab === 'chat' }">
      <section class="content">
        <el-skeleton v-if="loading && !kb" :rows="5" animated />
        <template v-else-if="kb">
          <div v-if="activeTab !== 'chat'" class="kb-hero workspace-hero">
            <div>
              <span class="section-kicker">KNOWLEDGE WORKSPACE</span>
              <h2>{{ kb.name }}</h2>
              <p>{{ kb.description || '这个知识库还没有描述。' }}</p>
            </div>
            <div class="toolbar-inline">
              <el-button :icon="ArrowLeft" plain @click="router.push('/kbs')">返回列表</el-button>
              <span class="status-tag">成员角色 {{ currentMemberRole || '-' }}</span>
            </div>
          </div>

          <div class="workspace-panel">
            <DocumentsTab
              v-if="activeTab === 'documents'"
              :kb-id="kb.id"
              :current-member-role="currentMemberRole"
            />
            <ChatTab
              v-else-if="activeTab === 'chat'"
              :kb-id="kb.id"
              :kb-name="kb.name"
              @traced="onChatTraced"
            />
            <MembersTab
              v-else-if="activeTab === 'members'"
              :kb-id="kb.id"
              :current-member-role="currentMemberRole"
              @changed="loadKb"
            />
            <QaTraceTab v-else-if="activeTab === 'traces'" :kb-id="kb.id" :refresh-key="traceRefreshKey" />
            <EvaluationTab v-else-if="activeTab === 'evaluation'" />
          </div>
        </template>
      </section>
    </div>
  </ConsoleLayout>
</template>

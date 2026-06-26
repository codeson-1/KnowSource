<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'

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
const auth = useAuthStore()
const kb = ref<KnowledgeBaseResponse | null>(null)
const members = ref<KnowledgeBaseMemberResponse[]>([])
const loading = ref(false)
const activeTab = ref('documents')
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
onMounted(loadKb)
</script>

<template>
  <ConsoleLayout>
    <div class="workspace-layout">
      <section class="content" style="grid-column: 1 / -1">
        <el-skeleton v-if="loading && !kb" :rows="5" animated />
        <template v-else-if="kb">
          <div class="kb-hero">
            <div>
              <h2>{{ kb.name }}</h2>
              <p>{{ kb.description || '这个知识库还没有描述。' }}</p>
            </div>
            <div class="toolbar-inline">
              <span class="status-tag success">知识库已选择</span>
              <span class="status-tag">成员角色 {{ currentMemberRole || '-' }}</span>
              <span class="status-tag">Phase 2</span>
            </div>
          </div>

          <el-tabs v-model="activeTab">
            <el-tab-pane label="Documents" name="documents">
              <DocumentsTab :kb-id="kb.id" :current-member-role="currentMemberRole" />
            </el-tab-pane>
            <el-tab-pane label="Chat" name="chat">
              <ChatTab :kb-id="kb.id" @traced="onChatTraced" />
            </el-tab-pane>
            <el-tab-pane label="Members" name="members">
              <MembersTab :kb-id="kb.id" :current-member-role="currentMemberRole" @changed="loadKb" />
            </el-tab-pane>
            <el-tab-pane label="QaTrace" name="traces">
              <QaTraceTab :kb-id="kb.id" :refresh-key="traceRefreshKey" />
            </el-tab-pane>
            <el-tab-pane label="Evaluation" name="evaluation">
              <EvaluationTab />
            </el-tab-pane>
          </el-tabs>
        </template>
      </section>
    </div>
  </ConsoleLayout>
</template>

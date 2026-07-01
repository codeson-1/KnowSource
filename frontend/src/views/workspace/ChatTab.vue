<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatDotRound,
  Connection,
  Delete,
  DocumentChecked,
  Finished,
  Plus,
  Refresh,
  View,
} from '@element-plus/icons-vue'

import { deleteChatSession, getChatSession, listChatSessions } from '@/api/chatSessions'
import { streamChat } from '@/api/chatStream'
import { extractErrorMessage } from '@/api/http'
import SourceList from '@/components/SourceList.vue'
import type {
  ChatSessionMessageResponse,
  ChatSessionSummaryResponse,
  ChatStreamDone,
  RagProfile,
  SourceCitation,
} from '@/types/api'

const props = defineProps<{ kbId: string; kbName: string }>()
const emit = defineEmits<{ traced: [] }>()

type ChatMessageId = number | string

interface ChatMessage {
  id: ChatMessageId
  role: 'user' | 'assistant'
  content: string
  meta?: string
  sources: SourceCitation[]
  qaTraceId?: string | null
  createdAt?: string
}

const form = reactive({
  question: '',
})
const defaultRagProfile: RagProfile = 'auto'

const loading = ref(false)
const sessionsLoading = ref(false)
const historyLoading = ref(false)
const sessions = ref<ChatSessionSummaryResponse[]>([])
const sessionStorageKey = computed(() => `knowsource.frontend.sessionId.${props.kbId}`)
const sessionId = ref<string | null>(localStorage.getItem(sessionStorageKey.value))
const messages = ref<ChatMessage[]>([])
const activeEvidenceMessageId = ref<ChatMessageId | null>(null)
let nextMessageId = 1
const TYPEWRITER_DELAY_MS = 14

const activeEvidenceMessage = computed(() =>
  messages.value.find((message) => message.role === 'assistant' && message.id === activeEvidenceMessageId.value) || null,
)
const activeEvidenceSources = computed(() => activeEvidenceMessage.value?.sources || [])
const evidenceHeading = computed(() => {
  if (!activeEvidenceMessage.value) {
    return '引用证据'
  }
  return activeEvidenceSources.value.length ? `当前回答证据 · ${activeEvidenceSources.value.length}` : '当前回答无引用'
})
const evidenceDescription = computed(() => {
  if (!activeEvidenceMessage.value) {
    return '点击某条回答的证据按钮后，这里会切换到该回答的引用来源。'
  }
  if (!activeEvidenceSources.value.length) {
    return '这条回答没有可展示的引用，通常对应空上下文拒答或模型软拒答。'
  }
  return preview(displayContent(activeEvidenceMessage.value.content), 96)
})

const promptSuggestions = [
  {
    title: '全局概览',
    text: '请基于知识库内容，总结最重要的 3 个主题，并分别列出关键要点。',
    icon: Connection,
  },
  {
    title: '定义查询',
    text: '请解释一个关键概念，并说明相关文档是怎么描述的。',
    icon: DocumentChecked,
  },
  {
    title: '对比分析',
    text: '请比较知识库中涉及到的两种方案或概念的异同，并在结尾给出推荐选择。',
    icon: ChatDotRound,
  },
  {
    title: '步骤指引',
    text: '根据知识库提供的资料，给出完成某件事的标准步骤清单。',
    icon: Finished,
  },
]

watch(
  () => props.kbId,
  () => {
    void initializeSessionState()
  },
  { immediate: true },
)

async function initializeSessionState() {
  const storedSessionId = localStorage.getItem(sessionStorageKey.value)
  sessionId.value = storedSessionId
  messages.value = []
  activeEvidenceMessageId.value = null
  await refreshSessions()
  if (storedSessionId && sessions.value.some((session) => session.id === storedSessionId)) {
    await openSession(storedSessionId, false)
  } else if (storedSessionId) {
    localStorage.removeItem(sessionStorageKey.value)
    sessionId.value = null
  }
}

async function refreshSessions() {
  sessionsLoading.value = true
  try {
    sessions.value = await listChatSessions(props.kbId, 30)
  } catch (error) {
    ElMessage.error(extractErrorMessage(error))
  } finally {
    sessionsLoading.value = false
  }
}

async function openSession(targetSessionId: string, notifyOnError = true) {
  historyLoading.value = true
  try {
    const detail = await getChatSession(props.kbId, targetSessionId)
    sessionId.value = detail.id
    localStorage.setItem(sessionStorageKey.value, detail.id)
    messages.value = detail.messages.map(toChatMessage)
    selectLatestAnswerEvidence()
    void nextTick(scrollToBottom)
  } catch (error) {
    if (notifyOnError) {
      ElMessage.error(extractErrorMessage(error))
    }
  } finally {
    historyLoading.value = false
  }
}

async function removeSession(targetSessionId: string) {
  try {
    await ElMessageBox.confirm('删除后将从历史对话中移除该会话，问答追踪审计记录仍会保留。', '删除历史对话', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    await deleteChatSession(props.kbId, targetSessionId)
    if (sessionId.value === targetSessionId) {
      newChat()
    }
    await refreshSessions()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(extractErrorMessage(error))
    }
  }
}

function append(role: ChatMessage['role'], content: string, meta?: string) {
  const message: ChatMessage = {
    id: `local-${nextMessageId++}`,
    role,
    content,
    meta,
    sources: [],
  }
  messages.value.push(message)
  void nextTick(scrollToBottom)
  return message
}

function updateMessage(id: ChatMessageId, patch: Partial<ChatMessage>) {
  const index = messages.value.findIndex((message) => message.id === id)
  if (index === -1) {
    return null
  }
  messages.value[index] = {
    ...messages.value[index],
    ...patch,
  }
  return messages.value[index]
}

function toChatMessage(message: ChatSessionMessageResponse): ChatMessage {
  const role = message.role === 'ASSISTANT' ? 'assistant' : 'user'
  return {
    id: message.id,
    role,
    content: message.content,
    qaTraceId: message.qaTraceId,
    sources: message.sources || [],
    createdAt: message.createdAt,
    meta: role === 'user' ? formatMessageTime(message.createdAt) : undefined,
  }
}

function selectLatestAnswerEvidence() {
  const latestAssistant = [...messages.value].reverse().find(
    (message) => message.role === 'assistant' && message.sources.length,
  )
  activeEvidenceMessageId.value = latestAssistant?.id || null
}

function selectEvidence(message: ChatMessage) {
  if (message.role === 'assistant' && message.sources.length) {
    activeEvidenceMessageId.value = message.id
  }
}

function scrollToBottom() {
  const el = document.querySelector('.chat-transcript')
  if (el) {
    el.scrollTop = el.scrollHeight
  }
}

function newChat() {
  sessionId.value = null
  localStorage.removeItem(sessionStorageKey.value)
  messages.value = []
  activeEvidenceMessageId.value = null
}

function createTypewriter(messageId: ChatMessageId) {
  let renderedText = ''
  let pendingCharacters: string[] = []
  let timer: number | null = null
  let finished = false
  let drainResolver: (() => void) | null = null

  const resolveDrain = () => {
    if (finished && !pendingCharacters.length && timer === null && drainResolver) {
      drainResolver()
      drainResolver = null
    }
  }
  const tick = () => {
    timer = null
    const nextCharacter = pendingCharacters.shift()
    if (!nextCharacter) {
      resolveDrain()
      return
    }
    renderedText += nextCharacter
    updateMessage(messageId, { content: renderedText })
    void nextTick(scrollToBottom)
    if (pendingCharacters.length) {
      timer = window.setTimeout(tick, TYPEWRITER_DELAY_MS)
    } else {
      resolveDrain()
    }
  }
  const schedule = () => {
    if (timer === null && pendingCharacters.length) {
      timer = window.setTimeout(tick, TYPEWRITER_DELAY_MS)
    }
  }

  return {
    get text() {
      return renderedText + pendingCharacters.join('')
    },
    enqueue(text: string) {
      if (!text) {
        return
      }
      pendingCharacters.push(...Array.from(text))
      schedule()
    },
    finish() {
      finished = true
      if (!pendingCharacters.length && timer === null) {
        return Promise.resolve()
      }
      return new Promise<void>((resolve) => {
        drainResolver = resolve
        schedule()
      })
    },
    stop() {
      if (timer !== null) {
        window.clearTimeout(timer)
        timer = null
      }
      pendingCharacters = []
      finished = true
      resolveDrain()
    },
  }
}

async function send() {
  const question = form.question.trim()
  if (!question || loading.value) {
    return
  }

  append('user', question, '刚刚')
  form.question = ''
  const assistant = append('assistant', '正在生成...')
  const assistantId = assistant.id
  activeEvidenceMessageId.value = null
  loading.value = true
  const typewriter = createTypewriter(assistantId)

  try {
    let answer = ''
    const streamState: { done: ChatStreamDone | null } = { done: null }
    await streamChat(
      props.kbId,
      {
        question,
        profile: defaultRagProfile,
        sessionId: sessionId.value,
      },
      {
        onSources(sources) {
          updateMessage(assistantId, { sources })
          if (sources.length) {
            activeEvidenceMessageId.value = assistantId
          }
        },
        onToken(token) {
          answer += token
          typewriter.enqueue(token)
        },
        onDone(done) {
          streamState.done = done
          const finalAnswer = done.answer || answer
          if (finalAnswer && finalAnswer.startsWith(answer) && finalAnswer.length > answer.length) {
            const remainingText = finalAnswer.slice(answer.length)
            answer += remainingText
            typewriter.enqueue(remainingText)
          } else if (finalAnswer && !answer) {
            answer = finalAnswer
            typewriter.enqueue(finalAnswer)
          }
          sessionId.value = done.sessionId
          localStorage.setItem(sessionStorageKey.value, done.sessionId)
          void refreshSessions()
          emit('traced')
        },
      },
    )
    await typewriter.finish()
    const donePayload = streamState.done
    if (donePayload) {
      const currentSources = messages.value.find((message) => message.id === assistantId)?.sources || []
      const nextSources = donePayload.refused ? [] : currentSources
      updateMessage(assistantId, {
        content: donePayload.answer || typewriter.text || answer,
        qaTraceId: donePayload.qaTraceId,
        sources: nextSources,
      })
      activeEvidenceMessageId.value = nextSources.length ? assistantId : null
    }
  } catch (error) {
    typewriter.stop()
    updateMessage(assistantId, {
      content: extractErrorMessage(error),
      sources: [],
    })
    ElMessage.error(extractErrorMessage(error))
  } finally {
    loading.value = false
  }
}

function formatSessionTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function formatMessageTime(value?: string) {
  return value ? formatSessionTime(value) : undefined
}

function preview(content: string, maxLength: number) {
  const normalized = content.replace(/\s+/g, ' ').trim()
  return normalized.length > maxLength ? `${normalized.slice(0, maxLength)}...` : normalized
}

function displayContent(content: string) {
  return content
    .replace(/\s*[\[【]\d+(?:\s*[,，、]\s*\d+)*[\]】]/g, '')
    .replace(/\s+([。！？；，、,.!?;:])/g, '$1')
    .trim()
}
</script>

<template>
  <div class="qa-console">
    <aside class="qa-sidebar">
      <div class="qa-sidebar-head">
        <div>
          <span class="section-kicker">SESSION HISTORY</span>
          <h3>Q&A Console</h3>
        </div>
        <span class="status-dot" />
      </div>

      <el-button type="primary" :icon="Plus" class="qa-new-button" @click="newChat">新建问答</el-button>

      <div class="local-session-list" v-loading="sessionsLoading">
        <div class="session-list-head">
          <p>历史对话 <span>{{ sessions.length }}</span></p>
          <el-button :icon="Refresh" text circle :loading="sessionsLoading" @click="refreshSessions" />
        </div>

        <div v-if="!sessions.length && !sessionsLoading" class="session-empty">
          还没有服务端会话。发送第一条问题后会自动保存。
        </div>

        <article
          v-for="session in sessions"
          :key="session.id"
          class="session-row"
          :class="{ active: session.id === sessionId }"
        >
          <button class="session-card" type="button" @click="openSession(session.id)">
            <strong>{{ session.title || '未命名对话' }}</strong>
            <span>{{ session.lastMessagePreview || '暂无消息' }}</span>
            <small>{{ session.messageCount }} 条消息 / {{ formatSessionTime(session.updatedAt) }}</small>
          </button>
          <el-button
            :icon="Delete"
            text
            circle
            class="session-delete"
            :disabled="loading"
            @click.stop="removeSession(session.id)"
          />
        </article>
      </div>

    </aside>

    <section class="qa-main">
      <div class="qa-canvas" v-loading="historyLoading">
        <div v-if="!messages.length" class="qa-empty">
          <div class="qa-emblem">
            <el-icon><ChatDotRound /></el-icon>
          </div>
          <span class="section-kicker">KNOWLEDGE Q&A · KNOWSOURCE</span>
          <h2>向你的知识库提问</h2>
          <p>检索、理解、溯源，每一个回答都会附带可追踪的文档引用。</p>
          <span class="status-pill">当前知识库 · {{ props.kbName }}</span>
          <div class="suggestion-grid">
            <article
              v-for="suggestion in promptSuggestions"
              :key="suggestion.title"
              class="suggestion-card"
            >
              <span>
                <el-icon><component :is="suggestion.icon" /></el-icon>
              </span>
              <strong>{{ suggestion.title }}</strong>
              <small>{{ suggestion.text }}</small>
            </article>
          </div>
        </div>

        <div v-else class="chat-transcript">
          <article v-for="message in messages" :key="message.id" class="qa-message" :class="message.role">
            <div class="qa-avatar">{{ message.role === 'user' ? 'You' : 'KS' }}</div>
            <div class="qa-message-body">
              <div class="qa-message-meta">
                <strong>{{ message.role === 'user' ? 'You' : 'KnowSource' }}</strong>
                <span v-if="message.role === 'user' && message.meta">{{ message.meta }}</span>
                <el-button
                  v-if="message.role === 'assistant' && message.sources.length"
                  :icon="View"
                  link
                  type="primary"
                  class="evidence-toggle"
                  :class="{ active: activeEvidenceMessageId === message.id }"
                  @click="selectEvidence(message)"
                >
                  {{ `${message.sources.length} 条证据` }}
                </el-button>
              </div>
              <p>{{ displayContent(message.content) }}</p>
              <div
                v-if="message.role === 'assistant' && message.sources.length && activeEvidenceMessageId === message.id"
                class="qa-inline-evidence"
              >
                <SourceList :sources="message.sources" empty-text="这条回答没有可展示的引用证据。" />
              </div>
            </div>
          </article>
        </div>
      </div>

      <form class="qa-composer" @submit.prevent="send">
        <span class="status-pill">{{ sessionId ? '继续历史对话' : '新的问答' }} · {{ props.kbName }}</span>
        <el-input
          v-model="form.question"
          type="textarea"
          :rows="3"
          placeholder="输入你的问题，按 Enter 发送，Shift + Enter 换行"
          @keydown.enter.exact.prevent="send"
        />
        <div class="composer-footer">
          <div class="composer-hints">
            <kbd>Enter</kbd><span>发送</span>
            <kbd>Shift + Enter</kbd><span>换行</span>
          </div>
          <div class="toolbar-inline">
            <el-button type="primary" native-type="submit" :loading="loading">发送</el-button>
          </div>
        </div>
      </form>
    </section>

    <aside class="qa-evidence">
      <div class="section-heading compact">
        <div>
          <span class="section-kicker">EVIDENCE CHAIN</span>
          <h3>{{ evidenceHeading }}</h3>
          <p>{{ evidenceDescription }}</p>
        </div>
      </div>
      <SourceList :sources="activeEvidenceSources" empty-text="请选择一条回答查看对应引用证据。" />
    </aside>
  </div>
</template>

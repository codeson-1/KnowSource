<script setup lang="ts">
import { nextTick, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { streamChat } from '@/api/chatStream'
import { extractErrorMessage } from '@/api/http'
import SourceList from '@/components/SourceList.vue'
import type { ChatStreamDone, RagProfile, SourceCitation } from '@/types/api'

const props = defineProps<{ kbId: string }>()
const emit = defineEmits<{ traced: [] }>()

interface ChatMessage {
  id: number
  role: 'user' | 'assistant'
  content: string
  meta?: string
  sources?: SourceCitation[]
}

const form = reactive({
  question: '',
  profile: 'auto' as RagProfile,
  topK: 5,
})

const loading = ref(false)
const sessionId = ref<string | null>(localStorage.getItem('knowsource.frontend.sessionId'))
const messages = ref<ChatMessage[]>([])
const latestSources = ref<SourceCitation[]>([])
let nextMessageId = 1

function append(role: ChatMessage['role'], content: string, meta?: string) {
  const message: ChatMessage = { id: nextMessageId++, role, content, meta, sources: [] }
  messages.value.push(message)
  void nextTick(scrollToBottom)
  return message
}

function scrollToBottom() {
  const el = document.querySelector('.chat-log')
  if (el) {
    el.scrollTop = el.scrollHeight
  }
}

async function send() {
  const question = form.question.trim()
  if (!question) {
    return
  }

  append('user', question, form.profile)
  form.question = ''
  const assistant = append('assistant', '正在生成...', 'streaming')
  loading.value = true
  latestSources.value = []

  try {
    let answer = ''
    let donePayload: ChatStreamDone | null = null
    await streamChat(
      props.kbId,
      {
        question,
        topK: Number(form.topK),
        profile: form.profile,
        sessionId: sessionId.value,
      },
      {
        onSources(sources) {
          latestSources.value = sources
          assistant.sources = sources
        },
        onToken(token) {
          answer += token
          assistant.content = answer
          void nextTick(scrollToBottom)
        },
        onDone(done) {
          donePayload = done
          assistant.content = done.answer || answer
          assistant.meta = `${done.ragProfile}${done.refused ? ' / refused' : ''} / trace ${done.qaTraceId}`
          sessionId.value = done.sessionId
          localStorage.setItem('knowsource.frontend.sessionId', done.sessionId)
          emit('traced')
        },
      },
    )
    if (!donePayload && answer) {
      assistant.meta = 'stream completed'
    }
  } catch (error) {
    assistant.content = extractErrorMessage(error)
    assistant.meta = '请求失败'
    ElMessage.error(extractErrorMessage(error))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="chat-layout">
    <section class="panel">
      <div class="section-heading">
        <div>
          <h3>流式问答</h3>
          <p>{{ sessionId ? `当前会话 ${sessionId}` : '新会话；auto 单轮走 naive，多轮走 modular。' }}</p>
        </div>
        <div class="toolbar-inline">
          <el-select v-model="form.profile" style="width: 128px">
            <el-option label="auto" value="auto" />
            <el-option label="naive" value="naive" />
            <el-option label="modular" value="modular" />
          </el-select>
          <el-input-number v-model="form.topK" :min="1" :max="15" controls-position="right" style="width: 120px" />
        </div>
      </div>

      <div class="chat-log">
        <div v-if="!messages.length" class="empty-state">
          先确认至少有一个 PUBLISHED + SYNCED 文档，再提问。若没有可检索上下文，后端会返回明确拒答。
        </div>
        <article v-for="message in messages" :key="message.id" class="chat-message" :class="message.role">
          <div>{{ message.content }}</div>
          <div v-if="message.meta" class="chat-meta">{{ message.meta }}</div>
          <SourceList v-if="message.sources?.length" :sources="message.sources" />
        </article>
      </div>

      <form class="chat-form" @submit.prevent="send">
        <el-input v-model="form.question" type="textarea" :rows="3" placeholder="例如：年假有多少天？" />
        <el-button type="primary" native-type="submit" :loading="loading">发送</el-button>
      </form>
    </section>

    <section class="panel">
      <div class="section-heading">
        <div>
          <h3>最近引用</h3>
          <p>来自最新一次问答的 sources。</p>
        </div>
      </div>
      <SourceList :sources="latestSources" empty-text="暂无引用。空上下文拒答不是错误。" />
    </section>
  </div>
</template>

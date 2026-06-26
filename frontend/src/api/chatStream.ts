import { useAuthStore } from '@/stores/auth'
import type { ChatRequest, ChatStreamDone, SourceCitation } from '@/types/api'

interface StreamHandlers {
  onSources?: (sources: SourceCitation[]) => void
  onToken?: (token: string) => void
  onDone?: (done: ChatStreamDone) => void
}

export async function streamChat(kbId: string, request: ChatRequest, handlers: StreamHandlers) {
  const auth = useAuthStore()
  let response = await postStream(kbId, request, auth.accessToken)

  if (response.status === 401 && auth.refreshToken && await auth.refresh()) {
    response = await postStream(kbId, request, auth.accessToken)
  }

  if (!response.ok) {
    throw new Error(await readStreamError(response))
  }
  if (!response.body) {
    throw new Error('当前浏览器不支持流式读取。')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true })
    const frames = buffer.split(/\r?\n\r?\n/)
    buffer = frames.pop() || ''
    for (const frame of frames) {
      dispatchFrame(frame, handlers)
    }
  }

  if (buffer.trim()) {
    dispatchFrame(buffer, handlers)
  }
}

function postStream(kbId: string, request: ChatRequest, accessToken: string | null) {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  }
  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`
  }
  return fetch(`/api/kbs/${kbId}/chat/stream`, {
    method: 'POST',
    headers,
    body: JSON.stringify(request),
  })
}

function dispatchFrame(frame: string, handlers: StreamHandlers) {
  const event = parseSseFrame(frame)
  if (!event) {
    return
  }
  if (event.name === 'sources') {
    handlers.onSources?.(JSON.parse(event.data || '[]') as SourceCitation[])
  } else if (event.name === 'token') {
    handlers.onToken?.(parseJsonOrText(event.data))
  } else if (event.name === 'done') {
    handlers.onDone?.(JSON.parse(event.data) as ChatStreamDone)
  }
}

function parseSseFrame(frame: string) {
  let name = 'message'
  const data: string[] = []
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith('event:')) {
      name = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      data.push(line.slice(5).trimStart())
    }
  }
  return data.length ? { name, data: data.join('\n') } : null
}

function parseJsonOrText(value: string) {
  try {
    return JSON.parse(value) as string
  } catch {
    return value || ''
  }
}

async function readStreamError(response: Response) {
  try {
    const payload = await response.json() as { message?: string; error?: string }
    return payload.message || payload.error || `${response.status} ${response.statusText}`
  } catch {
    return `${response.status} ${response.statusText}`
  }
}

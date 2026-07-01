import { http } from './http'
import type { ChatSessionDetailResponse, ChatSessionSummaryResponse } from '@/types/api'

export async function listChatSessions(kbId: string, limit = 30) {
  const { data } = await http.get<ChatSessionSummaryResponse[]>(`/kbs/${kbId}/chat/sessions`, {
    params: { limit },
  })
  return data
}

export async function getChatSession(kbId: string, sessionId: string) {
  const { data } = await http.get<ChatSessionDetailResponse>(`/kbs/${kbId}/chat/sessions/${sessionId}`)
  return data
}

export async function deleteChatSession(kbId: string, sessionId: string) {
  await http.delete(`/kbs/${kbId}/chat/sessions/${sessionId}`)
}

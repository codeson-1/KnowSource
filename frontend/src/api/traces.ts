import { http } from './http'
import type { QaTraceDetailResponse, QaTraceSummaryResponse } from '@/types/api'

export async function listTraces(kbId: string, limit = 10) {
  const { data } = await http.get<QaTraceSummaryResponse[]>(`/kbs/${kbId}/qa-traces`, {
    params: { limit },
  })
  return data
}

export async function getTrace(kbId: string, traceId: string) {
  const { data } = await http.get<QaTraceDetailResponse>(`/kbs/${kbId}/qa-traces/${traceId}`)
  return data
}

import { http } from './http'
import type { EvalHistoryItem, EvalReportResponse, EvalRunResponse } from '@/types/api'

export async function runGoldenSet() {
  const { data } = await http.post<EvalRunResponse>('/eval/golden-set/run')
  return data
}

export async function getGoldenSetReport() {
  const { data } = await http.get<EvalReportResponse>('/eval/golden-set/report')
  return data
}

export async function getGoldenSetHistory() {
  const { data } = await http.get<EvalHistoryItem[]>('/eval/golden-set/history')
  return data
}

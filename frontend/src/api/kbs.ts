import { http } from './http'
import type { KnowledgeBaseMemberResponse, KnowledgeBaseResponse, MemberRole } from '@/types/api'

export async function listKbs() {
  const { data } = await http.get<KnowledgeBaseResponse[]>('/kbs')
  return data
}

export async function createKb(payload: { name: string; description?: string }) {
  const { data } = await http.post<KnowledgeBaseResponse>('/kbs', payload)
  return data
}

export async function getKb(kbId: string) {
  const { data } = await http.get<KnowledgeBaseResponse>(`/kbs/${kbId}`)
  return data
}

export async function updateKb(kbId: string, payload: { name: string; description?: string }) {
  const { data } = await http.put<KnowledgeBaseResponse>(`/kbs/${kbId}`, payload)
  return data
}

export async function deleteKb(kbId: string) {
  await http.delete(`/kbs/${kbId}`)
}

export async function listMembers(kbId: string) {
  const { data } = await http.get<KnowledgeBaseMemberResponse[]>(`/kbs/${kbId}/members`)
  return data
}

export async function addMember(kbId: string, payload: { username: string; role: MemberRole }) {
  const { data } = await http.post<KnowledgeBaseMemberResponse>(`/kbs/${kbId}/members`, payload)
  return data
}

export async function updateMember(kbId: string, userId: number, payload: { role: MemberRole }) {
  const { data } = await http.put<KnowledgeBaseMemberResponse>(`/kbs/${kbId}/members/${userId}`, payload)
  return data
}

export async function removeMember(kbId: string, userId: number) {
  await http.delete(`/kbs/${kbId}/members/${userId}`)
}

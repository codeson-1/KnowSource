import { http } from './http'
import type {
  DocumentChunkResponse,
  DocumentIngestResponse,
  DocumentPublishResponse,
  DocumentPreviewResponse,
  DocumentResponse,
} from '@/types/api'

export async function listDocuments(kbId: string) {
  const { data } = await http.get<DocumentResponse[]>(`/kbs/${kbId}/documents`)
  return data
}

export async function createTextDocument(kbId: string, payload: { title: string; content: string }) {
  const { data } = await http.post<DocumentIngestResponse>(`/kbs/${kbId}/documents`, payload)
  return data
}

export async function uploadDocument(kbId: string, payload: { title: string; file: File }) {
  const form = new FormData()
  form.set('title', payload.title)
  form.set('file', payload.file)
  const { data } = await http.post<DocumentIngestResponse>(`/kbs/${kbId}/documents/upload`, form)
  return data
}

export async function replaceTextDocument(docId: string, payload: { title: string; content: string }) {
  const { data } = await http.put<DocumentIngestResponse>(`/documents/${docId}`, payload)
  return data
}

export async function replaceUploadDocument(docId: string, payload: { title: string; file: File }) {
  const form = new FormData()
  form.set('title', payload.title)
  form.set('file', payload.file)
  const { data } = await http.post<DocumentIngestResponse>(`/documents/${docId}/replace-upload`, form)
  return data
}

export async function getIngestTask(docId: string) {
  const { data } = await http.get<DocumentIngestResponse>(`/documents/${docId}/ingest-task`)
  return data
}

export async function retryIngest(docId: string) {
  const { data } = await http.post<DocumentIngestResponse>(`/documents/${docId}/ingest-task/retry`)
  return data
}

export async function listChunks(docId: string) {
  const { data } = await http.get<DocumentChunkResponse[]>(`/documents/${docId}/chunks`)
  return data
}

export async function publishDocument(docId: string) {
  const { data } = await http.post<DocumentPublishResponse>(`/documents/${docId}/publish`)
  return data
}

export async function archiveDocument(docId: string) {
  const { data } = await http.post<DocumentPublishResponse>(`/documents/${docId}/archive`)
  return data
}

export async function requeueIndexEvent(docId: string, eventId: string) {
  const { data } = await http.post<DocumentPublishResponse>(`/documents/${docId}/index-events/${eventId}/requeue`)
  return data
}

export async function retryLatestFailedIndex(docId: string) {
  const { data } = await http.post<DocumentPublishResponse>(`/documents/${docId}/index/retry`)
  return data
}

export async function deleteDocument(docId: string) {
  await http.delete(`/documents/${docId}`)
}

export async function getDocumentPreview(docId: string, pageNumber?: number | null) {
  const { data } = await http.get<DocumentPreviewResponse>(`/documents/${docId}/preview`, {
    params: pageNumber ? { pageNumber } : undefined,
  })
  return data
}

export async function openSourcePreviewBlob(sourceKey: string, targetWindow?: Window | null) {
  const { data } = await http.get<Blob>('/documents/source-preview', {
    params: { sourceKey },
    responseType: 'blob',
  })
  const url = URL.createObjectURL(data)
  if (targetWindow) {
    targetWindow.location.href = url
  } else {
    window.open(url, '_blank', 'noopener,noreferrer')
  }
  return url
}

export function revokeSourcePreviewUrl(url: string) {
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

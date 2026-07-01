export type GlobalRole = 'ADMIN' | 'EDITOR' | 'VIEWER'
export type MemberRole = 'OWNER' | 'EDITOR' | 'VIEWER'
export type DocumentStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
export type IndexStatus = 'NONE' | 'PENDING' | 'SYNCING' | 'SYNCED' | 'FAILED'
export type IngestStatus = 'PENDING' | 'PARSING' | 'READY' | 'FAILED'
export type RagProfile = 'auto' | 'naive' | 'modular'

export interface AuthResponse {
  accessToken: string
  refreshToken: string
  userId: number
  username: string
  globalRole: GlobalRole
}

export interface UserResponse {
  id: number
  username: string
  email: string | null
  globalRole: GlobalRole
  createdAt: string
}

export interface KnowledgeBaseResponse {
  id: string
  name: string
  description: string | null
  ownerId: number
  createdAt: string
  memberRole: MemberRole
}

export interface KnowledgeBaseMemberResponse {
  userId: number
  username: string
  globalRole: GlobalRole
  memberRole: MemberRole
}

export interface DocumentResponse {
  id: string
  kbId: string
  title: string
  status: DocumentStatus
  indexStatus: IndexStatus
  ossKey: string
  version: number
  fileType: string | null
  createdBy: number
  publishedAt: string | null
  vectorsSyncedAt: string | null
  createdAt: string
  latestIngestTaskId: string | null
  latestIngestStatus: IngestStatus | null
  parentChunkCount: number
  childChunkCount: number
  latestFailedIndexEventId: string | null
}

export interface DocumentIngestResponse {
  document: DocumentResponse
  ingestTaskId: string
  ingestStatus: IngestStatus
  parentChunkCount: number
  childChunkCount: number
}

export interface DocumentChunkResponse {
  id: string
  docId: string
  docVersion: number
  parentChunkId: string
  content: string
  chunkIndex: number
  pageNumber: number | null
  chunkType: string
}

export interface DocumentPublishResponse {
  docId: string
  kbId: string
  version: number
  indexStatus: IndexStatus
  eventId: string
  message: string
}

export interface DocumentPreviewResponse {
  docId: string
  sourceKey: string
  previewUrl: string
  pageNumber: number | null
}

export interface SourceCitation {
  index: number
  chunkId: string
  docId: string
  docVersion: number
  title: string
  chunkIndex: number
  pageNumber: number | null
  snippet: string
  score: number
}

export interface ChatStreamDone {
  qaTraceId: string
  sessionId: string
  kbId: string
  question: string
  rewrittenQuery: string | null
  ragProfile: Exclude<RagProfile, 'auto'>
  refused: boolean
  answer: string
}

export interface ChatSessionSummaryResponse {
  id: string
  kbId: string
  title: string | null
  messageCount: number
  lastMessageRole: string | null
  lastMessagePreview: string | null
  createdAt: string
  updatedAt: string
}

export interface ChatSessionMessageResponse {
  id: number
  role: 'USER' | 'ASSISTANT'
  content: string
  qaTraceId: string | null
  sources: SourceCitation[]
  createdAt: string
}

export interface ChatSessionDetailResponse {
  id: string
  kbId: string
  title: string | null
  createdAt: string
  updatedAt: string
  messages: ChatSessionMessageResponse[]
}

export interface QaTraceSummaryResponse {
  id: string
  kbId: string
  query: string
  answerPreview: string
  sourceCount: number
  totalMs: number | null
  ragProfile: string | null
  createdAt: string
}

export interface QaTraceDetailResponse {
  id: string
  sessionId: string | null
  kbId: string
  userId: number
  query: string
  rewrittenQuery: string | null
  retrievedChunks: SourceCitation[]
  answer: string | null
  retrievalMs: number | null
  llmMs: number | null
  rewriteLlmMs: number | null
  generationFirstTokenMs: number | null
  totalMs: number | null
  tokenUsage: unknown
  ragProfile: string | null
  createdAt: string
}

export interface ChatRequest {
  question: string
  topK?: number
  profile: RagProfile
  sessionId?: string | null
}

export interface EvalSummaryResponse {
  totalCases: number
  inScopeCases: number
  outOfScopeCases: number
  recallAt5: number
  citationHitRate: number
  refusalAccuracy: number
}

export interface EvalCaseResponse {
  id: string
  setupQuestion: string | null
  question: string
  profile: string
  expected: string
  refused: boolean
  sourceTitles: string[]
  citationHit: boolean
  refusalCorrect: boolean
  passed: boolean
  qaTraceId: string | null
  sessionId: string | null
}

export interface EvalRunResponse {
  kbId: string
  generatedAt: string
  summary: EvalSummaryResponse
  cases: EvalCaseResponse[]
  reportPath: string
}

export interface EvalReportResponse {
  generatedAt: string
  reportPath: string
  markdown: string
}

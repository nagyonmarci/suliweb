async function fetchJson<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`API error ${res.status}: ${text}`);
  }
  return res.json();
}

async function fetchText(path: string, options?: RequestInit): Promise<string> {
  const res = await fetch(path, options);
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`API error ${res.status}: ${text}`);
  }
  return res.text();
}

export interface Email {
  id: string;
  uniqueEntryId: string;
  pstFileName: string;
  folderPath: string;
  senderEmailAddress: string;
  senderName: string;
  subject: string;
  body: string;
  htmlContent: string;
  recipients: string[];
  cc: string[];
  bcc: string[];
  receivedTime: string;
  attachmentPaths: string[];
  status: string;
  internetMessageId: string;
  importance: string;
  categories: string;
  conversationTopic: string;
  messageClass: string;
  isRead: boolean;
}

export interface FileInfo {
  id: string;
  path: string;
  fileName: string;
  size: number;
  lastModified: string;
  status: string;
}

export interface ProgressState {
  currentOperation: string;
  totalItems: number;
  processedItems: number;
  percentage: number;
  active: boolean;
}

// RAG types
export interface SearchResult {
  chunkId: string;
  emailId: string;
  sourceType: string;
  attachmentFileName: string | null;
  content: string;
  emailSubject: string;
  senderName: string;
  senderEmailAddress: string;
  pstFileName: string;
  score: number;
}

export interface MatchedChunk {
  content: string;
  sourceType: string;
  attachmentFileName: string | null;
  score: number;
}

export interface EmailSearchResult {
  email: Email;
  bestScore: number;
  matchedChunks: MatchedChunk[];
}

export interface RagStats {
  totalEmails: number;
  totalChunks: number;
  embeddedChunks: number;
  pendingChunks: number;
  failedChunks: number;
}

export interface RagHealth {
  ollamaAvailable: boolean;
  ingestionRunning: boolean;
  stats: RagStats;
}

export interface RagContext {
  query: string;
  context: string;
}

export const api = {
  // Emails
  getEmails: () => fetchJson<Email[]>('/api/emails'),

  // FileInfo
  getFileInfos: () => fetchJson<FileInfo[]>('/api/file-infos'),

  // Progress
  getProgress: () => fetchJson<ProgressState>('/api/progress'),

  // PST Finder
  findPst: (directories: string[], excludedDirectories?: string[]) => {
    const params = new URLSearchParams();
    directories.forEach(d => params.append('directories', d));
    excludedDirectories?.forEach(d => params.append('excludedDirectories', d));
    return fetchText(`/find/pst?${params}`);
  },

  // PST Processor
  processFromDb: (saveAttachments: boolean = true) =>
    fetchText(`/pst/processFromDb?saveAttachments=${saveAttachments}`, { method: 'POST' }),
  pauseProcessing: () => fetchText('/pst/pause', { method: 'POST' }),
  resumeProcessing: () => fetchText('/pst/resume', { method: 'POST' }),

  // Synology
  findSynology: () => fetchJson<FileInfo[]>('/find/synology'),
  findSynologyToDb: () => fetchText('/find/synologyToDb'),

  // RAG
  ragIngest: () => fetchText('/api/rag/ingest', { method: 'POST' }),
  ragReIngest: (emailId: string) => fetchText(`/api/rag/ingest/${emailId}`, { method: 'POST' }),
  ragEmbed: () => fetchText('/api/rag/embed', { method: 'POST' }),
  ragSearch: (q: string, topK = 10) =>
    fetchJson<SearchResult[]>(`/api/rag/search?q=${encodeURIComponent(q)}&topK=${topK}`),
  ragSearchEmails: (q: string, topK = 10) =>
    fetchJson<EmailSearchResult[]>(`/api/rag/search/emails?q=${encodeURIComponent(q)}&topK=${topK}`),
  ragContext: (q: string, topK = 10) =>
    fetchJson<RagContext>(`/api/rag/context?q=${encodeURIComponent(q)}&topK=${topK}`),
  ragStats: () => fetchJson<RagStats>('/api/rag/stats'),
  ragHealth: () => fetchJson<RagHealth>('/api/rag/health'),
};

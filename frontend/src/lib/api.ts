// --- Auth token management ---

function getAccessToken(): string | null {
  return localStorage.getItem('accessToken');
}

function getRefreshToken(): string | null {
  return localStorage.getItem('refreshToken');
}

function setTokens(accessToken: string, refreshToken: string): void {
  localStorage.setItem('accessToken', accessToken);
  localStorage.setItem('refreshToken', refreshToken);
}

function clearTokens(): void {
  localStorage.removeItem('accessToken');
  localStorage.removeItem('refreshToken');
}

function authHeaders(): Record<string, string> {
  const token = getAccessToken();
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }
  return headers;
}

async function tryRefreshToken(): Promise<boolean> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) return false;

  try {
    const res = await fetch('/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    });
    if (!res.ok) {
      clearTokens();
      return false;
    }
    const data = await res.json();
    localStorage.setItem('accessToken', data.accessToken);
    return true;
  } catch {
    clearTokens();
    return false;
  }
}

// --- Core fetch with auth ---

async function fetchJson<T>(path: string, options?: RequestInit): Promise<T> {
  let res = await fetch(path, {
    ...options,
    headers: { ...authHeaders(), ...options?.headers },
  });

  // Auto-refresh on 401
  if (res.status === 401 && getRefreshToken()) {
    const refreshed = await tryRefreshToken();
    if (refreshed) {
      res = await fetch(path, {
        ...options,
        headers: { ...authHeaders(), ...options?.headers },
      });
    }
  }

  if (res.status === 401 || res.status === 403) {
    clearTokens();
    window.location.href = '/login';
    throw new Error('Unauthorized');
  }

  if (!res.ok) {
    const text = await res.text();
    throw new Error(`API error ${res.status}: ${text}`);
  }
  return res.json();
}

async function fetchText(path: string, options?: RequestInit): Promise<string> {
  let res = await fetch(path, {
    ...options,
    headers: { ...authHeaders(), ...options?.headers },
  });

  // Auto-refresh on 401
  if (res.status === 401 && getRefreshToken()) {
    const refreshed = await tryRefreshToken();
    if (refreshed) {
      res = await fetch(path, {
        ...options,
        headers: { ...authHeaders(), ...options?.headers },
      });
    }
  }

  if (res.status === 401) {
    clearTokens();
    window.location.href = '/login';
    throw new Error('Unauthorized');
  }

  if (!res.ok) {
    const text = await res.text();
    throw new Error(`API error ${res.status}: ${text}`);
  }
  return res.text();
}

// --- Types ---

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
  attachmentsSaved: boolean;
}

export interface Attachment {
  id: string;
  emailId: string;
  filename: string;
  contentType: string;
  size: number;
  localPath: string;
  pstFileName: string;
  creationTime: string;
  emailSubject: string;
  senderName: string;
  receivedTime: string;
}

export interface ProgressState {
  currentOperation: string;
  totalItems: number;
  processedItems: number;
  percentage: number;
  statusDetail?: string;
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

export interface ChatSource {
  emailId: string;
  subject: string;
  sender: string;
  score: number;
}

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  sources?: ChatSource[];
}

export interface ChatResponse {
  answer: string;
  sources: ChatSource[];
}

export interface AuthUser {
  username: string;
  email: string;
  authorities: string[];
}

// --- API ---

export const api = {
  // Auth
  login: async (username: string, password: string) => {
    const res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
    if (!res.ok) {
      const text = await res.text();
      throw new Error(`Login failed: ${text}`);
    }
    const data = await res.json();
    setTokens(data.accessToken, data.refreshToken);
    return data;
  },

  register: async (username: string, password: string, email: string) => {
    const res = await fetch('/api/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password, email }),
    });
    if (!res.ok) {
      const text = await res.text();
      throw new Error(`Registration failed: ${text}`);
    }
    return res.json();
  },

  logout: () => {
    clearTokens();
    window.location.href = '/login';
  },

  getMe: () => fetchJson<AuthUser>('/api/auth/me'),

  isLoggedIn: () => !!getAccessToken(),

  // Emails
  getEmails: () => fetchJson<Email[]>('/api/emails'),
  searchEmails: (params: Record<string, string>) => {
    const query = new URLSearchParams();
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== '') {
        query.append(k, v);
      }
    }
    return fetchJson<Email[]>(`/api/emails/search?${query.toString()}`);
  },
  getEmailCount: () => fetchJson<number>('/api/emails/count'),
  getEmailById: (id: string) => fetchJson<Email>(`/api/emails/${id}`),

  // FileInfo
  getFileInfos: () => fetchJson<FileInfo[]>('/api/file-infos'),
  getFileInfoCounts: () => fetchJson<{ total: number; pending: number; processed: number }>('/api/file-infos/counts'),

  // Progress
  getProgress: () => fetchJson<ProgressState>('/api/progress'),

  // Attachments
  getAttachments: () => fetchJson<Attachment[]>('/api/attachments'),
  getAttachmentsByEmail: (emailId: string) => fetchJson<Attachment[]>(`/api/attachments/email/${emailId}`),
  searchAttachments: (params: Record<string, string>) => {
    const query = new URLSearchParams();
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== '') {
        query.append(k, v);
      }
    }
    return fetchJson<Attachment[]>(`/api/attachments/search?${query.toString()}`);
  },
  getAttachmentCount: () => fetchJson<number>('/api/attachments/count'),
  downloadAttachment: async (id: string, filename: string) => {
    const res = await fetch(`/api/attachments/${id}/download`, {
      headers: authHeaders(),
    });
    if (!res.ok) throw new Error('Download failed');
    const blob = await res.blob();
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    window.URL.revokeObjectURL(url);
    document.body.removeChild(a);
  },

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
  saveAttachmentsFromDb: () =>
    fetchText('/pst/saveAttachmentsFromDb', { method: 'POST' }),
  processSelected: (requests: { id: string; saveAttachments: boolean }[]) =>
    fetchText('/pst/processSelected', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(requests) }),
  pauseProcessing: () => fetchText('/pst/pause', { method: 'POST' }),
  resumeProcessing: () => fetchText('/pst/resume', { method: 'POST' }),

  // Synology
  findSynology: () => fetchJson<FileInfo[]>('/find/synology'),
  findSynologyToDb: () => fetchText('/find/synologyToDb'),

  // RAG
  ragIngest: (includeAttachments = false) =>
    fetchText(`/api/rag/ingest?includeAttachments=${includeAttachments}`, { method: 'POST' }),
  ragReIngest: (emailId: string) => fetchText(`/api/rag/ingest/${emailId}`, { method: 'POST' }),
  ragEmbed: () => fetchText('/api/rag/embed', { method: 'POST' }),
  ragSearch: (q: string, topK = 10, filters?: Record<string, string>) => {
    const params = new URLSearchParams({ q, topK: String(topK) });
    if (filters) Object.entries(filters).forEach(([k, v]) => { if (v) params.set(k, v); });
    return fetchJson<SearchResult[]>(`/api/rag/search?${params}`);
  },
  ragSearchEmails: (q: string, topK = 10, filters?: Record<string, string>) => {
    const params = new URLSearchParams({ q, topK: String(topK) });
    if (filters) Object.entries(filters).forEach(([k, v]) => { if (v) params.set(k, v); });
    return fetchJson<EmailSearchResult[]>(`/api/rag/search/emails?${params}`);
  },
  ragContext: (q: string, topK = 10) =>
    fetchJson<RagContext>(`/api/rag/context?q=${encodeURIComponent(q)}&topK=${topK}`),
  ragStats: () => fetchJson<RagStats>('/api/rag/stats'),
  ragHealth: () => fetchJson<RagHealth>('/api/rag/health'),
  ragResetFailed: () => fetchText('/api/rag/reset-failed', { method: 'POST' }),
  ragResetAll: () => fetchText('/api/rag/reset-all', { method: 'POST' }),
  ragChat: (message: string, topK = 8, model?: string,
            history?: Array<{ role: string; content: string }>) =>
    fetchJson<ChatResponse>('/api/rag/chat', {
      method: 'POST',
      body: JSON.stringify({ message, topK, model, history }),
    }),
  ragModels: () => fetchJson<string[]>('/api/rag/models'),

  /**
   * Streaming RAG chat via SSE. Calls onToken for each token, returns final sources.
   * Falls back to non-streaming ragChat on error.
   */
  ragChatStream: async (
    message: string,
    topK: number,
    model: string | undefined,
    history: Array<{ role: string; content: string }> | undefined,
    onToken: (token: string) => void,
  ): Promise<ChatSource[]> => {
    const res = await fetch('/api/rag/chat/stream', {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ message, topK, model, history }),
    });

    if (!res.ok || !res.body) {
      // Fallback to non-streaming
      const fallback = await api.ragChat(message, topK, model, history);
      onToken(fallback.answer);
      return fallback.sources;
    }

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let sources: ChatSource[] = [];
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      // SSE format: each event is "data:..." followed by newlines
      const lines = buffer.split('\n');
      buffer = lines.pop() ?? '';

      for (const line of lines) {
        const trimmed = line.replace(/^data:\s*/, '').trim();
        if (!trimmed) continue;
        try {
          const parsed = JSON.parse(trimmed);
          if (parsed.token) {
            onToken(parsed.token);
          }
          if (parsed.done && parsed.sources) {
            sources = parsed.sources;
          }
          if (parsed.error) {
            throw new Error(parsed.error);
          }
        } catch {
          // Ignore unparseable lines
        }
      }
    }

    // Process remaining buffer
    if (buffer.trim()) {
      const trimmed = buffer.replace(/^data:\s*/, '').trim();
      try {
        const parsed = JSON.parse(trimmed);
        if (parsed.done && parsed.sources) sources = parsed.sources;
      } catch { /* ignore */ }
    }

    return sources;
  },
};

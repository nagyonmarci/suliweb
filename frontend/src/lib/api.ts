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

  if (res.status === 401) {
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
  contentHash: string | null;
}

export interface UserDto {
  id: string;
  username: string;
  email: string;
  authorities: string[];
  authorityIds: string[];
  allowedFileInfoIds: string[];
}

export interface AuthorityDto {
  id: string;
  permission: string;
}

export interface FileInfoDto {
  id: string;
  fileName: string;
  path: string;
  status: string;
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

export interface UserDto {
  id: string;
  username: string;
  email: string;
  authorities: string[];
  authorityIds: string[];
  allowedFileInfoIds: string[];
}

export interface AuthorityDto {
  id: string;
  permission: string;
}

export interface FileInfoDto {
  id: string;
  fileName: string;
  path: string;
  status: string;
}

export interface SynologySettingsResponse {
  host: string | null;
  username: string | null;
  passwordConfigured: boolean;
  pathPrefix: string | null;
  localMountPrefix: string | null;
  searchExtensions: string | null;
  batchSize: number | null;
}

export interface SynologySettingsRequest {
  host?: string;
  username?: string;
  password?: string;
  pathPrefix?: string;
  localMountPrefix?: string;
  searchExtensions?: string;
  batchSize?: number;
}

// e-Discovery types
export interface EDiscoveryResult {
  esId: string;
  mongoEmailId: string;
  subject: string;
  senderName: string;
  sender: string;
  date: string;
  pstFileName: string;
  pstOwner: string;
  snippet: string;
  score: number;
}

export interface EDiscoveryStatus {
  running: boolean;
  stats: {
    totalEmails: number;
    indexed: number;
    skipped: number;
    attFailures: number;
  };
}

export interface KgPersonNode {
  id?: number;
  email: string;
  name?: string | null;
  organization?: string | null;
}

export interface KgEmailNode {
  messageId?: string;
  mongoId?: string;
  subject?: string;
  date?: string;
  pstFileName?: string;
  pstOwner?: string;
  bodyDelta?: string;
}

export interface KgStatus {
  running: boolean;
  stats: {
    totalEmails: number;
    processed: number;
    failed: number;
    ratePerMin: number;
    etaSeconds: number | null;
  };
}

export interface KgGraphStats {
  topTopics: Array<{ name: string; count: number }>;
  topOrgs: Array<{ name: string; count: number }>;
  topSenders: Array<{ email: string; name: string | null; count: number }>;
  personCount: number;
  emailCount: number;
  conceptCount: number;
}

export interface LogEntry {
  id: string;
  timestamp: string;
  level: 'INFO' | 'DEBUG' | 'WARN' | 'ERROR';
  message: string;
  stackTrace?: string;
}

export type StageState = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED';

export interface StageProgress {
  id: string;
  name: string;
  state: StageState;
  total: number;
  processed: number;
  percentage: number;
  detail?: string | null;
  ratePerMin?: number | null;
  etaSeconds?: number | null;
}

export interface PipelineStatus {
  running: boolean;
  stages: StageProgress[];
}

export interface PipelineStartRequest {
  directories: string[];
  excludedDirectories: string[];
  saveAttachments: boolean;
  skipPstDiscovery: boolean;
  skipPstProcessing: boolean;
  skipEsIndexing: boolean;
  skipKgIngestion: boolean;
}

export interface AppSettingsDto {
  ollamaBaseUrl: string;
  chatModel: string;
  nerModel: string;
  chatMaxHistoryTurns: number;
  kgBatchSize: number;
  kgMaxConcurrentWrites: number;
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
  getDuplicates: () => fetchJson<FileInfo[][]>('/api/file-infos/duplicates'),
  computeHashes: () => fetchText('/api/file-infos/compute-hashes', { method: 'POST' }),
  deduplicate: () => fetchText('/api/file-infos/deduplicate', { method: 'POST' }),

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
  getAttachmentDuplicateStats: () => fetchJson<{
    totalRecords: number; uniqueFiles: number;
    sameEmailDuplicates: number; crossEmailShared: number;
  }>('/api/attachments/duplicate-stats'),
  deduplicateAttachments: () => fetchText('/api/attachments/deduplicate', { method: 'POST' }),
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
  getSynologySettings: () => fetchJson<SynologySettingsResponse>('/api/synology/settings'),
  saveSynologySettings: (s: SynologySettingsRequest) =>
    fetchJson<SynologySettingsResponse>('/api/synology/settings', { method: 'PUT', body: JSON.stringify(s) }),

  // RAG
  ragHealth: () => fetchJson<RagHealth>('/api/rag/health'),
  ragChat: (message: string, topK = 8, model?: string,
            history?: Array<{ role: string; content: string }>) =>
    fetchJson<ChatResponse>('/api/rag/chat', {
      method: 'POST',
      body: JSON.stringify({ message, topK, model, history }),
    }),
  ragModels: () => fetchJson<string[]>('/api/rag/models'),

  // e-Discovery
  ediscoveryIngest: () => fetchText('/api/ediscovery/ingest', { method: 'POST' }),
  ediscoveryReIngest: (id: string) => fetchText(`/api/ediscovery/ingest/${id}`, { method: 'POST' }),
  ediscoverySearch: (q: string, topK = 10, filters?: {
    sender?: string; pstOwner?: string; pstFileName?: string; dateFrom?: string; dateTo?: string;
  }) => {
    const params = new URLSearchParams({ q, topK: String(topK) });
    if (filters?.sender) params.set('sender', filters.sender);
    if (filters?.pstOwner) params.set('pstOwner', filters.pstOwner);
    if (filters?.pstFileName) params.set('pstFileName', filters.pstFileName);
    if (filters?.dateFrom) params.set('dateFrom', filters.dateFrom);
    if (filters?.dateTo) params.set('dateTo', filters.dateTo);
    return fetchJson<EDiscoveryResult[]>(`/api/ediscovery/search?${params}`);
  },
  ediscoveryStatus: () => fetchJson<EDiscoveryStatus>('/api/ediscovery/status'),

  // Knowledge Graph
  kgIngest: () => fetchText('/api/kg/ingest', { method: 'POST' }),
  kgReingestConcepts: () => fetchText('/api/kg/reingest-concepts', { method: 'POST' }),
  kgStatus: () => fetchJson<KgStatus>('/api/kg/status'),
  kgGraphStats: () => fetchJson<KgGraphStats>('/api/kg/graph-stats'),
  kgPersonNetwork: (email: string) =>
    fetchJson<KgPersonNode[]>(`/api/kg/persons/${encodeURIComponent(email)}/network`),
  kgThread: (threadId: string) =>
    fetchJson<KgEmailNode[]>(`/api/kg/thread/${encodeURIComponent(threadId)}`),
  kgConcept: (name: string, topK = 10) =>
    fetchJson<KgEmailNode[]>(`/api/kg/concept/${encodeURIComponent(name)}?topK=${topK}`),
  kgChat: (message: string, topK = 8, model?: string, history?: Array<{ role: string; content: string }>) =>
    fetchJson<ChatResponse>('/api/kg/chat', {
      method: 'POST',
      body: JSON.stringify({ message, topK, model, history }),
    }),

  kgChatStream: async (
    message: string,
    topK: number,
    model: string | undefined,
    history: Array<{ role: string; content: string }> | undefined,
    onToken: (token: string) => void,
  ): Promise<ChatSource[]> => {
    const res = await fetch('/api/kg/chat/stream', {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ message, topK, model, history }),
    });

    if (!res.ok || !res.body) {
      const fallback = await api.kgChat(message, topK, model, history);
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
      const lines = buffer.split('\n');
      buffer = lines.pop() ?? '';
      for (const line of lines) {
        const trimmed = line.replace(/^data:\s*/, '').trim();
        if (!trimmed) continue;
        try {
          const parsed = JSON.parse(trimmed);
          if (parsed.token) onToken(parsed.token);
          if (parsed.done && parsed.sources) sources = parsed.sources;
          if (parsed.error) throw new Error(parsed.error);
        } catch { /* ignore */ }
      }
    }

    if (buffer.trim()) {
      try {
        const parsed = JSON.parse(buffer.replace(/^data:\s*/, '').trim());
        if (parsed.done && parsed.sources) sources = parsed.sources;
      } catch { /* ignore */ }
    }

    return sources;
  },

  // Logs
  getLogs: (level?: string, from?: string, to?: string, sort?: string) => {
    const params = new URLSearchParams({ limit: '300' });
    if (level) params.set('level', level);
    if (from)  params.set('from', from);
    if (to)    params.set('to', to);
    if (sort)  params.set('sort', sort);
    return fetchJson<LogEntry[]>(`/api/logs?${params}`);
  },

  // Users
  getUsers: () => fetchJson<UserDto[]>('/api/users'),
  getUser: (id: string) => fetchJson<UserDto>(`/api/users/${id}`),
  createUser: (data: { username: string; password: string; email?: string; authorityIds?: string[] }) =>
    fetchJson<UserDto>('/api/users', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) }),
  updateUser: (id: string, data: { email?: string; password?: string; authorityIds?: string[] }) =>
    fetchJson<UserDto>(`/api/users/${id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) }),
  deleteUser: (id: string) => fetchText(`/api/users/${id}`, { method: 'DELETE' }),
  getAuthorities: () => fetchJson<AuthorityDto[]>('/api/users/authorities'),
  getUserFiles: (id: string) => fetchJson<FileInfoDto[]>(`/api/users/${id}/files`),
  updateUserFiles: (id: string, fileInfoIds: string[]) =>
    fetchJson<UserDto>(`/api/users/${id}/files`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ fileInfoIds }) }),

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
      const lines = buffer.split('\n');
      buffer = lines.pop() ?? '';
      for (const line of lines) {
        const trimmed = line.replace(/^data:\s*/, '').trim();
        if (!trimmed) continue;
        try {
          const parsed = JSON.parse(trimmed);
          if (parsed.token) onToken(parsed.token);
          if (parsed.done && parsed.sources) sources = parsed.sources;
          if (parsed.error) throw new Error(parsed.error);
        } catch { /* ignore */ }
      }
    }

    if (buffer.trim()) {
      try {
        const parsed = JSON.parse(buffer.replace(/^data:\s*/, '').trim());
        if (parsed.done && parsed.sources) sources = parsed.sources;
      } catch { /* ignore */ }
    }

    return sources;
  },

  // Pipeline
  startPipeline: (req: PipelineStartRequest) =>
    fetchText('/api/pipeline/start', {
      method: 'POST',
      body: JSON.stringify(req),
    }),
  getPipelineStatus: () => fetchJson<PipelineStatus>('/api/pipeline/status'),

  // Settings
  getSettings: (): Promise<AppSettingsDto> =>
    fetchJson('/api/settings'),

  saveSettings: (data: Partial<AppSettingsDto>): Promise<AppSettingsDto> =>
    fetchJson('/api/settings', {
      method: 'PUT',
      body: JSON.stringify(data),
    }),
};

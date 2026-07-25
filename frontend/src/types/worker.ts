export interface WorkerStatusSnapshot {
  workerId: string;
  hostname?: string;
  startedAt?: string;
  lastSeenAt?: string;
  fetchedConfigHash?: string;
  appliedConfigHash?: string;
  applySuccess?: boolean;
  applyError?: string;
  appliedAt?: string;
  uptimeSeconds?: number;
  heapUsedBytes?: number;
  heapMaxBytes?: number;
  runningInboundCount?: number;
  activeConnectionCount?: number;
  online?: boolean;
}

export interface ProxyWorkerDTO {
  workerId: string;
  displayName: string;
  enabled: boolean;
  description?: string;
  tokenPresent: boolean;
  createdAt?: string;
  updatedAt?: string;
  status?: WorkerStatusSnapshot;
}

export interface WorkerCreateRequest {
  workerId: string;
  displayName: string;
  description?: string;
}

export interface WorkerUpdateRequest {
  displayName: string;
  description?: string;
  enabled?: boolean;
}

export interface WorkerTokenResponse {
  worker: ProxyWorkerDTO;
  token: string;
}

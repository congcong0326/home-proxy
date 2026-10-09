export type LogLevel = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR' | 'OFF';

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
  logReportingEnabled?: boolean;
  logLevel?: LogLevel;
  description?: string;
  managementUrl?: string;
  managementToken?: string;
  managementTokenPresent?: boolean;
  createdAt?: string;
  updatedAt?: string;
  status?: WorkerStatusSnapshot;
}

export interface WorkerCreateRequest {
  workerId: string;
  displayName: string;
  description?: string;
  managementUrl: string;
  managementToken: string;
  logReportingEnabled?: boolean;
  logLevel?: LogLevel;
}

export interface WorkerUpdateRequest {
  displayName: string;
  description?: string;
  enabled?: boolean;
  managementUrl?: string;
  managementToken?: string;
  logReportingEnabled?: boolean;
  logLevel?: LogLevel;
}

export interface WorkerTokenResponse {
  worker: ProxyWorkerDTO;
}

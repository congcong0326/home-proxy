export interface ProxyGatewayStatus {
  workerId: string;
  hostname?: string;
  startedAt?: string;
  lastSeenAt?: string;
  fetchedConfigHash?: string;
  appliedConfigHash?: string;
  applySuccess?: boolean;
  applyError?: string;
  uptimeSeconds?: number;
  heapUsedBytes?: number;
  heapMaxBytes?: number;
  runningInboundCount?: number;
  activeConnectionCount?: number;
  online: boolean;
}

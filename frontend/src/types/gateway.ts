export interface ProxyGatewayStatus {
  workerId: string;
  hostname?: string;
  startedAt?: string;
  lastSeenAt?: string;
  lastConfigHash?: string;
  uptimeSeconds?: number;
  heapUsedBytes?: number;
  heapMaxBytes?: number;
  runningInboundCount?: number;
  activeConnectionCount?: number;
  online: boolean;
}

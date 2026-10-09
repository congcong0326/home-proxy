import { ProtocolType } from './route';
import { ProxyEncAlgo } from './proxyEncAlgo';
import { TrafficCategory } from './observability';

export interface InboundRouteBinding {
  userIds: number[];
  routeIds: number[];
}

export interface InboundConfigDTO {
  id: number;
  workerId: string;
  fallbackRouteId?: number;
  anonymousUserId?: number;
  name: string;
  protocol: ProtocolType;
  listenIp: string;
  port: number;
  tlsEnabled: boolean;
  sniffEnabled: boolean;
  fakeIpEnabled: boolean;
  ssMethod?: ProxyEncAlgo;
  inboundRouteBindings?: InboundRouteBinding[];
  status: number;
  notes?: string;
  createdAt: string;
  updatedAt: string;
  userCount?: number;
  routeNames?: string[];
}

export interface InboundConfigCreateRequest {
  workerId: string;
  fallbackRouteId?: number;
  anonymousUserId?: number;
  name: string;
  protocol: ProtocolType;
  listenIp: string;
  port: number;
  tlsEnabled: boolean;
  sniffEnabled: boolean;
  fakeIpEnabled: boolean;
  ssMethod?: ProxyEncAlgo;
  inboundRouteBindings: InboundRouteBinding[];
  status: number;
  notes?: string;
}

export interface InboundConfigUpdateRequest {
  workerId: string;
  fallbackRouteId?: number;
  anonymousUserId?: number;
  name: string;
  protocol: ProtocolType;
  listenIp: string;
  port: number;
  tlsEnabled: boolean;
  sniffEnabled: boolean;
  fakeIpEnabled: boolean;
  ssMethod?: ProxyEncAlgo;
  inboundRouteBindings: InboundRouteBinding[];
  status: number;
  notes?: string;
}

export interface InboundTrafficStats {
  inboundId: number;
  bytesIn: number;
  bytesOut: number;
  totalBytes: number;
  period?: string;
}

export interface InboundTrafficCounters {
  upload: string;
  download: string;
  total: string;
}

export interface InboundMonthlyTrafficV1 {
  inboundId: number;
  workerId: string;
  period: string;
  timeZone: string;
  from: string;
  to: string;
  traffic: InboundTrafficCounters;
  categories: Record<TrafficCategory, InboundTrafficCounters>;
  firstSessionAt: string | null;
}

export interface InboundQueryParams {
  page?: number;
  pageSize?: number;
  sortBy?: string;
  sortDir?: 'asc' | 'desc';
  protocol?: ProtocolType;
  port?: number;
  tlsEnabled?: boolean;
  status?: number;
  workerId?: string;
}

export interface PageResponse<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

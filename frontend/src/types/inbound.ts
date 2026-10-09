import { ProtocolType } from './route';
import { ProxyEncAlgo } from './proxyEncAlgo';

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

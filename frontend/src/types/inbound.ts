import { ProtocolType } from './route';
import { ProxyEncAlgo } from './proxyEncAlgo';

export interface InboundRouteBinding {
  userIds: number[];
  routeIds: number[];
}

export interface InboundConfigDTO {
  id: number;
  name: string;
  protocol: ProtocolType;
  listenIp: string;
  port: number;
  tlsEnabled: boolean;
  sniffEnabled: boolean;
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
  name: string;
  protocol: ProtocolType;
  listenIp: string;
  port: number;
  tlsEnabled: boolean;
  sniffEnabled: boolean;
  ssMethod?: ProxyEncAlgo;
  inboundRouteBindings: InboundRouteBinding[];
  status: number;
  notes?: string;
}

export interface InboundConfigUpdateRequest {
  name: string;
  protocol: ProtocolType;
  listenIp: string;
  port: number;
  tlsEnabled: boolean;
  sniffEnabled: boolean;
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
}

export interface PageResponse<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

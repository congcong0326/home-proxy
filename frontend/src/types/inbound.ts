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
  // 兼容旧数据
  allowedUserIds?: number[];
  routeIds?: number[];
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

export interface InboundQueryParams {
  page?: number;
  size?: number;
  sortBy?: string;
  sortDir?: 'asc' | 'desc';
  protocol?: ProtocolType;
  port?: number;
  tlsEnabled?: boolean;
  status?: number;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
  first: boolean;
  last: boolean;
}

export interface ApiPageResponse<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}
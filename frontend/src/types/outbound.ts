import { ProxyEncAlgo } from './proxyEncAlgo';
import { ProtocolType } from './route';

// 出站类别（与后端 OutboundKind 一致）
export enum OutboundKind {
  DIRECT = 'DIRECT',
  BLOCK = 'BLOCK',
  PROXY = 'PROXY',
  DNS_UPSTREAM = 'DNS_UPSTREAM'
}

export const OUTBOUND_KIND_LABELS = {
  [OutboundKind.DIRECT]: '直连',
  [OutboundKind.BLOCK]: '阻断',
  [OutboundKind.PROXY]: '上游代理',
  [OutboundKind.DNS_UPSTREAM]: 'DNS 上游'
};

export const OUTBOUND_KIND_COLORS = {
  [OutboundKind.DIRECT]: 'green',
  [OutboundKind.BLOCK]: 'red',
  [OutboundKind.PROXY]: 'blue',
  [OutboundKind.DNS_UPSTREAM]: 'cyan'
};

export const PROXY_PROTOCOLS: ProtocolType[] = [
  ProtocolType.SOCKS5,
  ProtocolType.HTTPS_CONNECT,
  ProtocolType.SHADOW_SOCKS,
  ProtocolType.VLESS_REALITY,
];

export const DNS_UPSTREAM_PROTOCOLS: ProtocolType[] = [ProtocolType.DNS_SERVER, ProtocolType.DOT];

export interface OutboundDTO {
  id: number;
  name: string;
  kind: OutboundKind;
  builtin: boolean;
  proxyType?: ProtocolType;
  host?: string;
  port?: number;
  username?: string;
  password?: string;
  encAlgo?: ProxyEncAlgo;
  config?: Record<string, unknown>;
  notes?: string;
  routeCount: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface OutboundRequest {
  name: string;
  kind: OutboundKind;
  proxyType: ProtocolType;
  host: string;
  port: number;
  username?: string;
  password?: string;
  encAlgo?: ProxyEncAlgo;
  config?: Record<string, unknown>;
  notes?: string;
}

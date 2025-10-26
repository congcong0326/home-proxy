// 路由条件类型（与后端保持一致）
export enum RouteConditionType {
  DOMAIN = 'DOMAIN',
  GEO = 'GEO'
}

// 路由匹配操作（与后端保持一致）
export enum MatchOp {
  IN = 'IN',
  NOT_IN = 'NOT_IN'
}

// 路由规则类型（与后端实体保持字段名一致）
export interface RouteRule {
  conditionType: RouteConditionType; // 条件类型：DOMAIN / GEO
  op: MatchOp;                        // 匹配操作：IN / NOT_IN
  value: string;              // 具体值：域名或地理位置
}

// 协议类型枚举（与后端保持一致）
export enum ProtocolType {
  SOCKS5 = 'SOCKS5',
  HTTPS_CONNECT = 'HTTPS_CONNECT',
  SOCKS5_HTTPS = 'SOCKS5_HTTPS',
  NONE = 'NONE',
  SHADOW_SOCKS = 'SHADOW_SOCKS',
  SS = 'SHADOW_SOCKS',
}

// 协议类型标签映射
export const PROTOCOL_TYPE_LABELS = {
  [ProtocolType.SOCKS5]: 'SOCKS5协议',
  [ProtocolType.HTTPS_CONNECT]: 'HTTPS CONNECT协议',
  [ProtocolType.SOCKS5_HTTPS]: 'SOCKS5+HTTPS混合协议',
  [ProtocolType.NONE]: '直接转发',
  [ProtocolType.SHADOW_SOCKS]: 'Shadowsocks协议'
};

// 出站代理加密算法类型（仅在 SHADOW_SOCKS 时使用）
export type OutboundProxyEncAlgo = 'aes_256_gcm' | 'aes_128_gcm' | 'chacha20_ietf_poly1305';

// 路由策略枚举
export enum RoutePolicy {
  DIRECT = 'DIRECT',
  BLOCK = 'BLOCK',
  OUTBOUND_PROXY = 'OUTBOUND_PROXY',
  DESTINATION_OVERRIDE = 'DESTINATION_OVERRIDE'
}

// 路由策略标签映射
export const ROUTE_POLICY_LABELS = {
  [RoutePolicy.DIRECT]: '直连',
  [RoutePolicy.BLOCK]: '阻断',
  [RoutePolicy.OUTBOUND_PROXY]: '出站代理',
  [RoutePolicy.DESTINATION_OVERRIDE]: '目标重写'
};

// 路由策略颜色映射
export const ROUTE_POLICY_COLORS = {
  [RoutePolicy.DIRECT]: 'green',
  [RoutePolicy.BLOCK]: 'red',
  [RoutePolicy.OUTBOUND_PROXY]: 'blue',
  [RoutePolicy.DESTINATION_OVERRIDE]: 'purple'
};

// 路由状态枚举
export enum RouteStatus {
  DISABLED = 0,
  ENABLED = 1
}

// 路由状态标签映射
export const ROUTE_STATUS_LABELS = {
  [RouteStatus.DISABLED]: '禁用',
  [RouteStatus.ENABLED]: '启用'
};

// 路由状态颜色映射
export const ROUTE_STATUS_COLORS = {
  [RouteStatus.DISABLED]: 'red',
  [RouteStatus.ENABLED]: 'green'
};

// 路由DTO类型
export interface RouteDTO {
  id: number;
  name: string;
  rules: RouteRule[];
  policy: RoutePolicy;
  outboundTag?: string;
  outboundProxyType?: ProtocolType;
  outboundProxyHost?: string;
  outboundProxyPort?: number;
  outboundProxyUsername?: string;
  outboundProxyPassword?: string;
  outboundProxyEncAlgo?: OutboundProxyEncAlgo;
  status: number;
  notes?: string;
  createdAt: string;
  updatedAt: string;
}

// 创建路由请求类型
export interface CreateRouteRequest {
  name: string;
  rules: RouteRule[];
  policy: RoutePolicy;
  outboundTag?: string;
  outboundProxyType?: ProtocolType;
  outboundProxyHost?: string;
  outboundProxyPort?: number;
  outboundProxyUsername?: string;
  outboundProxyPassword?: string;
  outboundProxyEncAlgo?: OutboundProxyEncAlgo;
  status?: number;
  notes?: string;
}

// 更新路由请求类型
export interface UpdateRouteRequest {
  name?: string;
  rules?: RouteRule[];
  policy?: RoutePolicy;
  outboundTag?: string;
  outboundProxyType?: ProtocolType;
  outboundProxyHost?: string;
  outboundProxyPort?: number;
  outboundProxyUsername?: string;
  outboundProxyPassword?: string;
  outboundProxyEncAlgo?: OutboundProxyEncAlgo;
  status?: number;
  notes?: string;
}

// 路由查询参数类型
export interface RouteQueryParams {
  page?: number;
  size?: number;
  sort?: string;
  direction?: 'asc' | 'desc';
  name?: string;
  policy?: RoutePolicy;
  status?: number;
}

// 分页响应类型（复用user.ts中的定义）
export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
  first: boolean;
  last: boolean;
}

// 实际API响应类型
export interface ApiPageResponse<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}
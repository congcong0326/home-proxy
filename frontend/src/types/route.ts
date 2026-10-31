import { ProxyEncAlgo } from './proxyEncAlgo';
import { OutboundKind } from './outbound';

// 路由条件类型（与后端保持一致）
export enum RouteConditionType {
  DOMAIN = 'DOMAIN',
  GEO = 'GEO',
  /** @deprecated use RULE_SET instead */
  AD_BLOCK = 'AD_BLOCK',
  RULE_SET = 'RULE_SET'
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
  VLESS_REALITY = 'VLESS_REALITY',
  TP_PROXY = 'TP_PROXY',
  DOT = 'DOT',
  DNS_SERVER = 'DNS_SERVER',
  TLS_SNI_TCP = 'TLS_SNI_TCP',
}

// 协议类型标签映射
export const PROTOCOL_TYPE_LABELS = {
  [ProtocolType.SOCKS5]: 'SOCKS5协议',
  [ProtocolType.HTTPS_CONNECT]: 'HTTPS CONNECT协议',
  [ProtocolType.SOCKS5_HTTPS]: 'SOCKS5+HTTPS混合协议',
  [ProtocolType.NONE]: '直接转发',
  [ProtocolType.SHADOW_SOCKS]: 'Shadowsocks协议',
  [ProtocolType.VLESS_REALITY]: 'VLESS REALITY Vision协议',
  [ProtocolType.TP_PROXY]: '透明代理',
  [ProtocolType.DOT]: 'dns over tls 服务器',
  [ProtocolType.DNS_SERVER]: 'dns服务器',
  [ProtocolType.TLS_SNI_TCP]: 'TLS SNI 四层分流'
};

// 出站代理加密算法类型（仅在 SHADOW_SOCKS 时使用）
export type OutboundProxyEncAlgo = ProxyEncAlgo;

// 路由策略枚举
export enum RoutePolicy {
  DIRECT = 'DIRECT',
  BLOCK = 'BLOCK',
  OUTBOUND_PROXY = 'OUTBOUND_PROXY',
  DESTINATION_OVERRIDE = 'DESTINATION_OVERRIDE',
  DNS_REWRITING = 'DNS_REWRITING'
}

// 路由策略标签映射
export const ROUTE_POLICY_LABELS = {
  [RoutePolicy.DIRECT]: '直连',
  [RoutePolicy.BLOCK]: '阻断',
  [RoutePolicy.OUTBOUND_PROXY]: '出站代理',
  [RoutePolicy.DESTINATION_OVERRIDE]: '目标重写',
  [RoutePolicy.DNS_REWRITING]: '自定义DNS解析'
};

// 路由策略颜色映射
export const ROUTE_POLICY_COLORS = {
  [RoutePolicy.DIRECT]: 'green',
  [RoutePolicy.BLOCK]: 'red',
  [RoutePolicy.OUTBOUND_PROXY]: 'blue',
  [RoutePolicy.DESTINATION_OVERRIDE]: 'purple',
  [RoutePolicy.DNS_REWRITING]: 'cyan'
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

// 路由动作（与后端 RouteAction 一致）
export enum RouteAction {
  FORWARD = 'FORWARD',
  REWRITE_DESTINATION = 'REWRITE_DESTINATION',
  REWRITE_DNS = 'REWRITE_DNS'
}

export const ROUTE_ACTION_LABELS = {
  [RouteAction.FORWARD]: '转发到出站',
  [RouteAction.REWRITE_DESTINATION]: '目标改写',
  [RouteAction.REWRITE_DNS]: 'DNS 应答改写'
};

// 管理台路由视图；policy 为编译出的下发策略，只读
export interface RouteDTO {
  id: number;
  name: string;
  rules: RouteRule[];
  action?: RouteAction;
  outboundId?: number;
  outboundName?: string;
  outboundKind?: OutboundKind;
  rewriteTableId?: number;
  rewriteTableName?: string;
  rewriteEntryCount?: number;
  rewriteTargetHost?: string;
  rewriteTargetPort?: number;
  policy: RoutePolicy;
  outboundTag?: string;
  status: number;
  notes?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** 路由去向：转发显示出站名，改写显示改写表或固定目标。 */
export const describeRouteTarget = (route: RouteDTO): string => {
  if (route.action === RouteAction.FORWARD) return route.outboundName || '-';
  if (route.rewriteTableId) return `改写表：${route.rewriteTableName ?? route.rewriteTableId}（${route.rewriteEntryCount ?? 0} 条）`;
  if (route.action === RouteAction.REWRITE_DNS) return route.rewriteTargetHost || '-';
  return route.rewriteTargetHost ? `${route.rewriteTargetHost}:${route.rewriteTargetPort}` : '-';
};

// 创建／更新路由请求（整体替换）
export interface RouteRequest {
  name: string;
  rules: RouteRule[];
  action: RouteAction;
  outboundId?: number;
  rewriteTableId?: number;
  rewriteTargetHost?: string;
  rewriteTargetPort?: number;
  status?: number;
  notes?: string;
}

export type CreateRouteRequest = RouteRequest;
export type UpdateRouteRequest = RouteRequest;

// 路由查询参数类型
export interface RouteQueryParams {
  page?: number;
  pageSize?: number;
  sortBy?: string;
  sortDir?: 'asc' | 'desc';
  name?: string;
  policy?: RoutePolicy;
  status?: number;
}

export interface PageResponse<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

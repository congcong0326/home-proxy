// 访问日志列表项（与后端 AccessLogListItem 对齐）
export interface AccessLogListItem {
  id: number;
  ts: string; // 时间戳 ISO 字符串
  requestId: string;
  userId?: number;
  username?: string;
  proxyName?: string;
  inboundId?: number;
  clientIp?: string;
  status?: number;
  bytesIn?: number;
  bytesOut?: number;
  requestDurationMs?: number;
  originalTargetHost?: string;
  rewriteTargetHost?: string;
  // 地理信息（后端列表可能不返回，前端可选显示）
  srcGeoCountry?: string;
  srcGeoCity?: string;
  dstGeoCountry?: string;
  dstGeoCity?: string;
}

// 访问日志详情（与后端 AccessLogDetail 对齐，保留常用字段）
export interface AccessLogDetail {
  id: number;
  ts: string;
  requestId: string;
  userId?: number;
  username?: string;
  proxyName?: string;
  inboundId?: number;
  clientIp?: string;
  clientPort?: number;
  srcGeoCountry?: string;
  srcGeoCity?: string;
  originalTargetHost?: string;
  originalTargetIP?: string;
  originalTargetPort?: number;
  rewriteTargetHost?: string;
  rewriteTargetPort?: number;
  dstGeoCountry?: string;
  dstGeoCity?: string;
  inboundProtocolType?: string;
  outboundProtocolType?: string;
  routePolicyName?: string;
  routePolicyId?: number;
  bytesIn?: number;
  bytesOut?: number;
  status?: number;
  errorCode?: string;
  errorMsg?: string;
  requestDurationMs?: number;
  dnsDurationMs?: number;
  connectDurationMs?: number;
  connectTargetDurationMs?: number;
}

// 日志查询参数（与 LogController.queryAccessLogs 请求参数一致）
export interface AccessLogQueryParams {
  from?: string;
  to?: string;
  userId?: number;
  username?: string;
  proxyName?: string;
  inboundId?: number;
  clientIp?: string;
  status?: number;
  protocol?: string;
  routePolicyId?: number;
  srcGeoCountry?: string;
  srcGeoCity?: string;
  dstGeoCountry?: string;
  dstGeoCity?: string;
  host?: string;
  originalTargetHost?: string;
  rewriteTargetHost?: string;
  q?: string;
  page?: number;
  size?: number;
  sort?: string;
}

// 时间序列点（后端 dto.TimeSeriesPoint）
export interface TimeSeriesPoint {
  ts: string; // 序列时间点
  value: number; // 指标值（如请求数、流量）
}

// TopN 聚合项（后端 dto.TopItem）
export interface TopItem {
  key: string; // 维度键，例如 username / host
  value: number; // 指标值
}

// 分布聚合桶（后端 dto.DistributionBucket）
export interface DistributionBucket {
  key: string; // 分布字段的值
  count: number; // 数量
}

// 通用分页响应（与后端 PageResponse 对齐或兼容 ApiPageResponse）
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
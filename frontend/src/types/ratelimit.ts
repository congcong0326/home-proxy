// 限流范围类型枚举（与后端枚举对应）
export enum RateLimitScopeType {
  GLOBAL = 'GLOBAL',
  USERS = 'USERS'
}

// 限流策略DTO
export interface RateLimitDTO {
  id: number;
  scopeType: RateLimitScopeType;
  userIds?: number[];
  uplinkLimitBps?: number;
  downlinkLimitBps?: number;
  burstBytes?: number;
  enabled: boolean;
  effectiveTimeStart?: string; // HH:mm:ss
  effectiveTimeEnd?: string;   // HH:mm:ss
  effectiveFrom?: string;      // YYYY-MM-DD
  effectiveTo?: string;        // YYYY-MM-DD
  createdAt: string;
  updatedAt: string;
}

// 创建限流策略请求
export interface RateLimitCreateRequest {
  scopeType: RateLimitScopeType;
  userIds?: number[];
  uplinkLimitBps?: number;
  downlinkLimitBps?: number;
  burstBytes?: number;
  enabled: boolean;
  effectiveTimeStart?: string;
  effectiveTimeEnd?: string;
  effectiveFrom?: string;
  effectiveTo?: string;
}

// 更新限流策略请求
export interface RateLimitUpdateRequest {
  scopeType: RateLimitScopeType;
  userIds?: number[];
  uplinkLimitBps?: number;
  downlinkLimitBps?: number;
  burstBytes?: number;
  enabled: boolean;
  effectiveTimeStart?: string;
  effectiveTimeEnd?: string;
  effectiveFrom?: string;
  effectiveTo?: string;
}

// 限流查询参数
export interface RateLimitQueryParams {
  page?: number;
  size?: number;
  sortBy?: string;
  sortDir?: 'asc' | 'desc';
  scopeType?: RateLimitScopeType;
  enabled?: boolean;
}

// 分页响应类型（与后端一致）
export interface PageResponse<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}
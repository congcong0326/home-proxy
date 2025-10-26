import { LoginRequest, LoginResponse, ChangePasswordRequest, UserResponse, ApiResponse } from '../types/auth';
import {
  UserDTO,
  CreateUserRequest,
  UpdateUserRequest,
  ResetCredentialRequest,
  UpdateStatusRequest,
  PageResponse,
  ApiPageResponse,
  UserQueryParams
} from '../types/user';
import {
  RouteDTO,
  CreateRouteRequest,
  UpdateRouteRequest,
  RouteQueryParams
} from '../types/route';
import {
  RateLimitDTO,
  RateLimitCreateRequest,
  RateLimitUpdateRequest,
  RateLimitQueryParams,
  RateLimitScopeType,
  PageResponse as RateLimitPageResponse
} from '../types/ratelimit';
import {
  InboundConfigDTO,
  InboundConfigCreateRequest,
  InboundConfigUpdateRequest,
  InboundQueryParams
} from '../types/inbound';
import {
  AccessLogListItem,
  AccessLogDetail,
  AccessLogQueryParams,
  TimeSeriesPoint as LogTimeSeriesPoint,
  TopItem,
  DistributionBucket,
} from '../types/log';
import {
  UserTrafficStats,
  TimeSeriesPoint,
  WolConfig,
  PcStatus,
  IpStatusResponse,
  WolResponse,
  TrafficTrendParams,
  TrafficStatsParams
} from '../types/dashboard';

// API基础URL配置
const API_BASE_URL = process.env.NODE_ENV === 'production' 
  ? '/api'  // 生产环境使用相对路径
  : '/api';  // 开发环境使用代理，通过package.json的proxy配置

// HTTP请求工具类
class ApiService {
  private baseURL: string;

  constructor(baseURL: string = API_BASE_URL) {
    this.baseURL = baseURL;
  }

  // 通用请求方法
  private async request<T>(
    endpoint: string,
    options: RequestInit = {}
  ): Promise<T> {
    const url = `${this.baseURL}${endpoint}`;
    const token = localStorage.getItem('token');

    const config: RequestInit = {
      headers: {
        'Content-Type': 'application/json',
        ...(token && { Authorization: `Bearer ${token}` }),
        ...options.headers,
      },
      ...options,
    };

    try {
      const response = await fetch(url, config);
      
      if (!response.ok) {
        const errorData = await response.json().catch(() => ({}));
        throw new Error(errorData.message || `HTTP error! status: ${response.status}`);
      }

      return await response.json();
    } catch (error) {
      console.error('API request failed:', error);
      throw error;
    }
  }

  // 管理员接口专用请求方法（不带/api前缀）
  private async adminRequest<T>(
    endpoint: string,
    options: RequestInit = {}
  ): Promise<T> {
    const url = endpoint; // 直接使用endpoint，不添加baseURL前缀
    const token = localStorage.getItem('token');

    const config: RequestInit = {
      headers: {
        'Content-Type': 'application/json',
        ...(token && { Authorization: `Bearer ${token}` }),
        ...options.headers,
      },
      ...options,
    };

    try {
      const response = await fetch(url, config);
      
      if (!response.ok) {
        const errorData = await response.json().catch(() => ({}));
        throw new Error(errorData.message || `HTTP error! status: ${response.status}`);
      }

      return await response.json();
    } catch (error) {
      console.error('API request failed:', error);
      throw error;
    }
  }

  // 登录
  async login(data: LoginRequest): Promise<LoginResponse> {
    return this.adminRequest<LoginResponse>('/admin/login', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  }

  // 获取当前用户信息
  async getCurrentUser(): Promise<UserResponse> {
    return this.adminRequest<UserResponse>('/admin/me');
  }

  // 修改密码
  async changePassword(data: ChangePasswordRequest): Promise<LoginResponse> {
    return this.adminRequest<LoginResponse>('/admin/change-password', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  }

  // 退出登录
  async logout(): Promise<ApiResponse> {
    return this.adminRequest<ApiResponse>('/admin/logout', {
      method: 'POST',
    });
  }

  // ========== 用户管理接口 ==========
  
  // 分页查询用户列表
  async getUsers(params: UserQueryParams = {}): Promise<ApiPageResponse<UserDTO>> {
    const searchParams = new URLSearchParams();
    
    if (params.page) searchParams.append('page', params.page.toString());
    if (params.pageSize) searchParams.append('pageSize', params.pageSize.toString());
    if (params.q) searchParams.append('q', params.q);
    if (params.status !== undefined) searchParams.append('status', params.status.toString());
    if (params.sortBy) searchParams.append('sortBy', params.sortBy);
    if (params.sortDir) searchParams.append('sortDir', params.sortDir);
    
    const queryString = searchParams.toString();
    const endpoint = queryString ? `/users?${queryString}` : '/users';
    
    return this.request<ApiPageResponse<UserDTO>>(endpoint);
  }
  
  // 根据ID查询用户详情
  async getUserById(id: number): Promise<UserDTO> {
    return this.request<UserDTO>(`/users/${id}`);
  }
  
  // 创建用户
  async createUser(data: CreateUserRequest): Promise<UserDTO> {
    return this.request<UserDTO>('/users', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  }
  
  // 更新用户信息
  async updateUser(id: number, data: UpdateUserRequest): Promise<UserDTO> {
    return this.request<UserDTO>(`/users/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    });
  }
  
  // 删除用户
  async deleteUser(id: number): Promise<void> {
    return this.request<void>(`/users/${id}`, {
      method: 'DELETE',
    });
  }
  
  // 重置用户凭证
  async resetUserCredential(id: number, data: ResetCredentialRequest): Promise<void> {
    return this.request<void>(`/users/${id}/credential`, {
      method: 'PUT',
      body: JSON.stringify(data),
    });
  }
  
  // 更新用户状态
  async updateUserStatus(id: number, data: UpdateStatusRequest): Promise<void> {
    return this.request<void>(`/users/${id}/status`, {
      method: 'PUT',
      body: JSON.stringify(data),
    });
  }
  
  // 批量删除用户
  async batchDeleteUsers(ids: number[]): Promise<void> {
    return this.request<void>('/users/batch', {
      method: 'DELETE',
      body: JSON.stringify({ ids }),
    });
  }
  
  // 批量更新用户状态
  async batchUpdateUserStatus(ids: number[], status: number): Promise<void> {
    return this.request<void>('/users/batch/status', {
      method: 'PUT',
      body: JSON.stringify({ ids, status }),
    });
  }

  // ========== 路由管理接口 ==========
  
  // 分页查询路由列表
  async getRoutes(params: RouteQueryParams = {}): Promise<PageResponse<RouteDTO>> {
    const searchParams = new URLSearchParams();
    
    if (params.page !== undefined) searchParams.append('page', params.page.toString());
    if (params.size) searchParams.append('size', params.size.toString());
    if (params.sort) searchParams.append('sort', params.sort);
    if (params.direction) searchParams.append('direction', params.direction);
    if (params.name) searchParams.append('name', params.name);
    if (params.policy) searchParams.append('policy', params.policy);
    if (params.status !== undefined) searchParams.append('status', params.status.toString());
    
    const queryString = searchParams.toString();
    const endpoint = queryString ? `/routes?${queryString}` : '/routes';
    
    // 兼容后端返回 { items, total, page, pageSize } 的分页格式
    const raw: any = await this.request<any>(endpoint);
    if (raw && Array.isArray(raw.items)) {
      const total = typeof raw.total === 'number' ? raw.total : raw.items.length;
      const size = typeof raw.pageSize === 'number' ? raw.pageSize : raw.items.length;
      const number = typeof raw.page === 'number' ? raw.page : 0;
      const totalPages = size > 0 ? Math.ceil(total / size) : 1;
      const normalized: PageResponse<RouteDTO> = {
        content: raw.items,
        totalElements: total,
        totalPages,
        size,
        number,
        first: number === 0,
        last: number + 1 >= totalPages,
      };
      return normalized;
    }
    // 若后端已返回标准 PageResponse 结构，直接透传
    return raw as PageResponse<RouteDTO>;
  }
  
  // ========== 入站配置接口 ==========
  async getInbounds(params: InboundQueryParams = {}): Promise<PageResponse<InboundConfigDTO>> {
    const searchParams = new URLSearchParams();
    if (params.page !== undefined) searchParams.append('page', params.page.toString());
    if (params.size !== undefined) searchParams.append('size', params.size.toString());
    if (params.sortBy) searchParams.append('sortBy', params.sortBy);
    if (params.sortDir) searchParams.append('sortDir', params.sortDir);
    if (params.protocol) searchParams.append('protocol', params.protocol);
    if (params.port !== undefined) searchParams.append('port', params.port.toString());
    if (params.tlsEnabled !== undefined) searchParams.append('tlsEnabled', params.tlsEnabled.toString());
    if (params.status !== undefined) searchParams.append('status', params.status.toString());
    const endpoint = searchParams.toString() ? `/inbounds?${searchParams.toString()}` : '/inbounds';

    const raw: any = await this.request<any>(endpoint);
    if (raw && Array.isArray(raw.items)) {
      const total = typeof raw.total === 'number' ? raw.total : raw.items.length;
      const size = typeof raw.pageSize === 'number' ? raw.pageSize : raw.items.length;
      const number = typeof raw.page === 'number' ? raw.page : 0;
      const totalPages = size > 0 ? Math.ceil(total / size) : 1;
      const normalized: PageResponse<InboundConfigDTO> = {
        content: raw.items,
        totalElements: total,
        totalPages,
        size,
        number,
        first: number === 0,
        last: number + 1 >= totalPages,
      };
      return normalized;
    }
    return raw as PageResponse<InboundConfigDTO>;
  }

  async getInboundById(id: number): Promise<InboundConfigDTO> {
    return this.request<InboundConfigDTO>(`/inbounds/${id}`);
  }

  async createInbound(data: InboundConfigCreateRequest): Promise<InboundConfigDTO> {
    return this.request<InboundConfigDTO>('/inbounds', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  }

  async updateInbound(id: number, data: InboundConfigUpdateRequest): Promise<InboundConfigDTO> {
    return this.request<InboundConfigDTO>(`/inbounds/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    });
  }

  async deleteInbound(id: number): Promise<void> {
    return this.request<void>(`/inbounds/${id}`, {
      method: 'DELETE',
    });
  }
  
  // 根据ID查询路由详情
  async getRouteById(id: number): Promise<RouteDTO> {
    return this.request<RouteDTO>(`/routes/${id}`);
  }
  
  // 创建路由
  async createRoute(data: CreateRouteRequest): Promise<RouteDTO> {
    return this.request<RouteDTO>('/routes', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  }
  
  // 更新路由信息
  async updateRoute(id: number, data: UpdateRouteRequest): Promise<RouteDTO> {
    return this.request<RouteDTO>(`/routes/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    });
  }
  
  // 删除路由
  async deleteRoute(id: number): Promise<void> {
    return this.request<void>(`/routes/${id}`, {
      method: 'DELETE',
    });
  }

  // ========== 日志查询与聚合 ==========
  // 分页查询访问日志
  async getAccessLogs(params: AccessLogQueryParams = {}): Promise<PageResponse<AccessLogListItem>> {
    const searchParams = new URLSearchParams();
    if (params.from) searchParams.append('from', params.from);
    if (params.to) searchParams.append('to', params.to);
    if (params.userId !== undefined) searchParams.append('userId', String(params.userId));
    if (params.username) searchParams.append('username', params.username);
    if (params.proxyName) searchParams.append('proxyName', params.proxyName);
    if (params.inboundId !== undefined) searchParams.append('inboundId', String(params.inboundId));
    if (params.clientIp) searchParams.append('clientIp', params.clientIp);
    if (params.status !== undefined) searchParams.append('status', String(params.status));
    if (params.protocol) searchParams.append('protocol', params.protocol);
    if (params.routePolicyId !== undefined) searchParams.append('routePolicyId', String(params.routePolicyId));
    if (params.srcGeoCountry) searchParams.append('srcGeoCountry', params.srcGeoCountry);
    if (params.srcGeoCity) searchParams.append('srcGeoCity', params.srcGeoCity);
    if (params.dstGeoCountry) searchParams.append('dstGeoCountry', params.dstGeoCountry);
    if (params.dstGeoCity) searchParams.append('dstGeoCity', params.dstGeoCity);
    if (params.host) searchParams.append('host', params.host);
    if (params.originalTargetHost) searchParams.append('originalTargetHost', params.originalTargetHost);
    if (params.rewriteTargetHost) searchParams.append('rewriteTargetHost', params.rewriteTargetHost);
    if (params.q) searchParams.append('q', params.q);
    if (params.page !== undefined) searchParams.append('page', String(params.page));
    if (params.size !== undefined) searchParams.append('size', String(params.size));
    if (params.sort) searchParams.append('sort', params.sort);

    const queryString = searchParams.toString();
    const endpoint = queryString ? `/logs/access?${queryString}` : '/logs/access';
    const raw = await this.request<any>(endpoint);
    // 兼容两种分页响应结构：ApiPageResponse { items, page, size, total } 与 PageResponse { content, number, size, totalElements }
    if (raw && Array.isArray(raw.items)) {
      const size = raw.size ?? raw.pageSize ?? (params.size ?? 10);
      const page = (raw.page ?? 1) - 1; // 转为0基
      const total = raw.total ?? 0;
      const totalPages = size > 0 ? Math.ceil(total / size) : 0;
      return {
        content: raw.items,
        totalElements: total,
        totalPages,
        size,
        number: page,
        first: page <= 0,
        last: page + 1 >= totalPages,
      } as PageResponse<AccessLogListItem>;
    }
    return raw as PageResponse<AccessLogListItem>;
  }

  // 访问日志详情
  async getAccessLogById(id: number): Promise<AccessLogDetail> {
    return this.request<AccessLogDetail>(`/logs/access/${id}`);
  }

  // 时间序列聚合
  async getAccessTimeseries(params: AccessLogQueryParams & { metric: string; interval: string }): Promise<LogTimeSeriesPoint[]> {
    const searchParams = new URLSearchParams();
    if (params.from) searchParams.append('from', params.from);
    if (params.to) searchParams.append('to', params.to);
    searchParams.append('interval', params.interval);
    searchParams.append('metric', params.metric);
    if (params.userId !== undefined) searchParams.append('userId', String(params.userId));
    if (params.username) searchParams.append('username', params.username);
    if (params.proxyName) searchParams.append('proxyName', params.proxyName);
    if (params.inboundId !== undefined) searchParams.append('inboundId', String(params.inboundId));
    if (params.clientIp) searchParams.append('clientIp', params.clientIp);
    if (params.status !== undefined) searchParams.append('status', String(params.status));
    if (params.protocol) searchParams.append('protocol', params.protocol);
    if (params.routePolicyId !== undefined) searchParams.append('routePolicyId', String(params.routePolicyId));
    if (params.srcGeoCountry) searchParams.append('srcGeoCountry', params.srcGeoCountry);
    if (params.srcGeoCity) searchParams.append('srcGeoCity', params.srcGeoCity);
    if (params.dstGeoCountry) searchParams.append('dstGeoCountry', params.dstGeoCountry);
    if (params.dstGeoCity) searchParams.append('dstGeoCity', params.dstGeoCity);
    if (params.host) searchParams.append('host', params.host);
    if (params.originalTargetHost) searchParams.append('originalTargetHost', params.originalTargetHost);
    if (params.rewriteTargetHost) searchParams.append('rewriteTargetHost', params.rewriteTargetHost);

    const endpoint = `/logs/access/aggregate/timeseries?${searchParams.toString()}`;
    return this.request<LogTimeSeriesPoint[]>(endpoint);
  }

  // TopN 聚合
  async getAccessTop(params: AccessLogQueryParams & { metric: string; dimension: string; limit?: number }): Promise<TopItem[]> {
    const searchParams = new URLSearchParams();
    if (params.from) searchParams.append('from', params.from);
    if (params.to) searchParams.append('to', params.to);
    searchParams.append('dimension', params.dimension);
    searchParams.append('metric', params.metric);
    if (params.limit !== undefined) searchParams.append('limit', String(params.limit));
    if (params.userId !== undefined) searchParams.append('userId', String(params.userId));
    if (params.username) searchParams.append('username', params.username);
    if (params.proxyName) searchParams.append('proxyName', params.proxyName);
    if (params.inboundId !== undefined) searchParams.append('inboundId', String(params.inboundId));
    if (params.clientIp) searchParams.append('clientIp', params.clientIp);
    if (params.status !== undefined) searchParams.append('status', String(params.status));
    if (params.protocol) searchParams.append('protocol', params.protocol);
    if (params.routePolicyId !== undefined) searchParams.append('routePolicyId', String(params.routePolicyId));
    if (params.srcGeoCountry) searchParams.append('srcGeoCountry', params.srcGeoCountry);
    if (params.srcGeoCity) searchParams.append('srcGeoCity', params.srcGeoCity);
    if (params.dstGeoCountry) searchParams.append('dstGeoCountry', params.dstGeoCountry);
    if (params.dstGeoCity) searchParams.append('dstGeoCity', params.dstGeoCity);
    if (params.host) searchParams.append('host', params.host);
    if (params.originalTargetHost) searchParams.append('originalTargetHost', params.originalTargetHost);
    if (params.rewriteTargetHost) searchParams.append('rewriteTargetHost', params.rewriteTargetHost);

    const endpoint = `/logs/access/aggregate/top?${searchParams.toString()}`;
    return this.request<TopItem[]>(endpoint);
  }

  // 日度聚合表的 TopN（支持时间区间，按天汇总后按月范围查询）
  async getAccessDailyTop(params: { from?: string; to?: string; dimension?: string; metric?: string; limit?: number; userId?: number }): Promise<TopItem[]> {
    const searchParams = new URLSearchParams();
    if (params.from) searchParams.append('from', params.from);
    if (params.to) searchParams.append('to', params.to);
    if (params.dimension) searchParams.append('dimension', params.dimension);
    if (params.metric) searchParams.append('metric', params.metric);
    if (params.limit !== undefined) searchParams.append('limit', String(params.limit));
    if (params.userId !== undefined) searchParams.append('userId', String(params.userId));
    const endpoint = `/logs/access/aggregate/daily/top?${searchParams.toString()}`;
    return this.request<TopItem[]>(endpoint);
  }

  // 分布聚合
  async getAccessDistribution(params: AccessLogQueryParams & { field: string }): Promise<DistributionBucket[]> {
    const searchParams = new URLSearchParams();
    if (params.from) searchParams.append('from', params.from);
    if (params.to) searchParams.append('to', params.to);
    searchParams.append('field', params.field);
    if (params.userId !== undefined) searchParams.append('userId', String(params.userId));
    if (params.username) searchParams.append('username', params.username);
    if (params.proxyName) searchParams.append('proxyName', params.proxyName);
    if (params.inboundId !== undefined) searchParams.append('inboundId', String(params.inboundId));
    if (params.clientIp) searchParams.append('clientIp', params.clientIp);
    if (params.status !== undefined) searchParams.append('status', String(params.status));
    if (params.protocol) searchParams.append('protocol', params.protocol);
    if (params.routePolicyId !== undefined) searchParams.append('routePolicyId', String(params.routePolicyId));
    if (params.srcGeoCountry) searchParams.append('srcGeoCountry', params.srcGeoCountry);
    if (params.srcGeoCity) searchParams.append('srcGeoCity', params.srcGeoCity);
    if (params.dstGeoCountry) searchParams.append('dstGeoCountry', params.dstGeoCountry);
    if (params.dstGeoCity) searchParams.append('dstGeoCity', params.dstGeoCity);
    if (params.host) searchParams.append('host', params.host);
    if (params.originalTargetHost) searchParams.append('originalTargetHost', params.originalTargetHost);
    if (params.rewriteTargetHost) searchParams.append('rewriteTargetHost', params.rewriteTargetHost);

    const endpoint = `/logs/access/aggregate/distribution?${searchParams.toString()}`;
    return this.request<DistributionBucket[]>(endpoint);
  }
  
  // ========== 限流管理接口 ==========
  
  async getRateLimits(params: RateLimitQueryParams = {}): Promise<RateLimitPageResponse<RateLimitDTO>> {
    const searchParams = new URLSearchParams();
    if (params.page !== undefined) searchParams.append('page', params.page.toString());
    if (params.size !== undefined) searchParams.append('size', params.size.toString());
    if (params.sortBy) searchParams.append('sortBy', params.sortBy);
    if (params.sortDir) searchParams.append('sortDir', params.sortDir);
    if (params.scopeType) searchParams.append('scopeType', params.scopeType);
    if (params.enabled !== undefined) searchParams.append('enabled', params.enabled.toString());
    const qs = searchParams.toString();
    const endpoint = qs ? `/rate-limits?${qs}` : '/rate-limits';
    return this.request<RateLimitPageResponse<RateLimitDTO>>(endpoint);
  }

  async getRateLimitById(id: number): Promise<RateLimitDTO> {
    return this.request<RateLimitDTO>(`/rate-limits/${id}`);
  }

  async createRateLimit(data: RateLimitCreateRequest): Promise<RateLimitDTO> {
    return this.request<RateLimitDTO>('/rate-limits', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  }

  async updateRateLimit(id: number, data: RateLimitUpdateRequest): Promise<RateLimitDTO> {
    return this.request<RateLimitDTO>(`/rate-limits/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    });
  }

  async deleteRateLimit(id: number): Promise<void> {
    return this.request<void>(`/rate-limits/${id}`, {
      method: 'DELETE',
    });
  }

  async getRateLimitsByScopeType(scopeType: RateLimitScopeType): Promise<RateLimitDTO[]> {
    return this.request<RateLimitDTO[]>(`/rate-limits/scope/${scopeType}`);
  }

  async getRateLimitsByEnabled(enabled: boolean): Promise<RateLimitDTO[]> {
    return this.request<RateLimitDTO[]>(`/rate-limits/enabled/${enabled}`);
  }
  // 获取所有启用的路由（用于下拉选择）
  async getEnabledRoutes(): Promise<RouteDTO[]> {
    return this.request<RouteDTO[]>('/routes/enabled');
  }

  // Dashboard API methods
  
  // 用户流量统计相关API
  async getDailyUserTrafficStats(params: TrafficStatsParams = {}): Promise<UserTrafficStats[]> {
    const queryParams = new URLSearchParams();
    if (params.date) {
      queryParams.append('date', params.date);
    }
    const url = `/user-traffic-stats/daily${queryParams.toString() ? '?' + queryParams.toString() : ''}`;
    return this.request<UserTrafficStats[]>(url);
  }

  async getMonthlyUserTrafficStats(params: TrafficStatsParams = {}): Promise<UserTrafficStats[]> {
    const queryParams = new URLSearchParams();
    if (params.yearMonth) {
      queryParams.append('yearMonth', params.yearMonth);
    }
    const url = `/user-traffic-stats/monthly${queryParams.toString() ? '?' + queryParams.toString() : ''}`;
    return this.request<UserTrafficStats[]>(url);
  }

  // 流量趋势相关API
  async getGlobalTrafficTrend(params: TrafficTrendParams): Promise<TimeSeriesPoint[]> {
    const queryParams = new URLSearchParams({
      startTime: params.startTime,
      endTime: params.endTime
    });
    return this.request<TimeSeriesPoint[]>(`/logs/traffic/minute/global?${queryParams.toString()}`);
  }

  async getUserTrafficTrend(userId: number, params: TrafficTrendParams): Promise<TimeSeriesPoint[]> {
    const queryParams = new URLSearchParams({
      startTime: params.startTime,
      endTime: params.endTime
    });
    return this.request<TimeSeriesPoint[]>(`/logs/traffic/minute/user/${userId}?${queryParams.toString()}`);
  }

  // WOL相关API
  async getWolConfigs(): Promise<WolConfig[]> {
    return this.request<WolConfig[]>('/wol/configs');
  }

  async getWolConfigById(id: number): Promise<WolConfig> {
    return this.request<WolConfig>(`/wol/configs/${id}`);
  }

  async createWolConfig(data: Omit<WolConfig, 'id' | 'createdAt' | 'updatedAt' | 'online'>): Promise<WolConfig> {
    return this.request<WolConfig>('/wol/configs', {
      method: 'POST',
      body: JSON.stringify(data)
    });
  }

  async updateWolConfig(id: number, data: Partial<WolConfig>): Promise<WolConfig> {
    return this.request<WolConfig>(`/wol/configs/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    });
  }

  async deleteWolConfig(id: number): Promise<void> {
    await this.request<void>(`/wol/configs/${id}`, {
      method: 'DELETE'
    });
  }

  async enableWolConfig(id: number): Promise<WolConfig> {
    return this.request<WolConfig>(`/wol/configs/${id}/enable`, {
      method: 'POST'
    });
  }

  async disableWolConfig(id: number): Promise<WolConfig> {
    return this.request<WolConfig>(`/wol/configs/${id}/disable`, {
      method: 'POST'
    });
  }

  async getAllPcStatus(): Promise<PcStatus[]> {
    return this.request<PcStatus[]>('/wol/status');
  }

  async getIpStatus(ip: string): Promise<IpStatusResponse> {
    return this.request<IpStatusResponse>(`/wol/status/${ip}`);
  }

  async checkIpStatus(ip: string): Promise<IpStatusResponse> {
    return this.request<IpStatusResponse>(`/wol/status/${ip}/check`, {
      method: 'POST'
    });
  }

  async wakeById(id: number): Promise<WolResponse> {
    return this.request<WolResponse>(`/wol/wake/${id}`, {
      method: 'POST'
    });
  }

  async wakeByIp(ip: string): Promise<WolResponse> {
    return this.request<WolResponse>(`/wol/wake/ip/${ip}`, {
      method: 'POST'
    });
  }

  async wakeByName(name: string): Promise<WolResponse> {
    return this.request<WolResponse>(`/wol/wake/name/${name}`, {
      method: 'POST'
    });
  }

  async refreshWolConfigs(): Promise<WolResponse> {
    return this.request<WolResponse>('/wol/refresh', {
      method: 'POST'
    });
  }

  async startWolMonitor(): Promise<WolResponse> {
    return this.request<WolResponse>('/wol/monitor/start', {
      method: 'POST'
    });
  }

  async stopWolMonitor(): Promise<WolResponse> {
    return this.request<WolResponse>('/wol/monitor/stop', {
      method: 'POST'
    });
  }
}

// 导出API服务实例
export const apiService = new ApiService();
export default apiService;
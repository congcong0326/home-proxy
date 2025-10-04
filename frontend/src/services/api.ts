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
}

// 导出API服务实例
export const apiService = new ApiService();
export default apiService;
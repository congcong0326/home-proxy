import { LoginRequest, LoginResponse, ChangePasswordRequest, UserResponse, ApiResponse } from '../types/auth';

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
}

// 导出API服务实例
export const apiService = new ApiService();
export default apiService;
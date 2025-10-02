// 登录请求类型
export interface LoginRequest {
  username: string;
  password: string;
}

// 用户信息类型
export interface UserResponse {
  id: number;
  username: string;
  roles: string[];
  mustChangePassword: boolean;
}

// 登录响应类型
export interface LoginResponse {
  token: string;
  mustChangePassword: boolean;
  expiresIn: number;
  user: UserResponse;
}

// 修改密码请求类型
export interface ChangePasswordRequest {
  oldPassword?: string;
  newPassword: string;
}

// API响应基础类型
export interface ApiResponse<T = any> {
  ok?: boolean;
  message?: string;
  data?: T;
}

// 认证状态类型
export interface AuthState {
  isAuthenticated: boolean;
  user: UserResponse | null;
  token: string | null;
  loading: boolean;
  error: string | null;
}
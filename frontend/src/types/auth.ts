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

// 初始化状态响应类型
export interface SetupStatusResponse {
  setupRequired: boolean;
}

// 首个管理员初始化请求类型
export interface SetupAdminRequest {
  username: string;
  password: string;
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
  setupLoading: boolean;
  setupChecked: boolean;
  setupRequired: boolean;
  error: string | null;
}

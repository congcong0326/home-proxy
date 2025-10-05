// 用户信息类型
export interface User {
  id: number;
  username: string;
  status: number; // 0: 禁用, 1: 启用
  createdAt: string;
  updatedAt: string;
}

// 用户DTO类型
export interface UserDTO {
  id: number;
  username: string;
  status: number;
  remark?: string;
  createdAt: string;
  updatedAt: string;
}

// 创建用户请求类型
export interface CreateUserRequest {
  username: string;
  credential: string;
  status?: number;
}

// 更新用户请求类型
export interface UpdateUserRequest {
  username?: string;
  status?: number;
}

// 重置凭证请求类型
export interface ResetCredentialRequest {
  newCredential: string;
}

// 更新状态请求类型
export interface UpdateStatusRequest {
  status: number;
}

// 分页响应类型
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

// 用户查询参数类型
export interface UserQueryParams {
  page?: number;
  pageSize?: number;
  q?: string; // 搜索关键字
  status?: number; // 状态过滤
  sortBy?: string;
  sortDir?: 'asc' | 'desc';
}

// 用户状态枚举
export enum UserStatus {
  DISABLED = 0,
  ENABLED = 1
}

// 用户状态标签映射
export const USER_STATUS_LABELS = {
  [UserStatus.DISABLED]: '禁用',
  [UserStatus.ENABLED]: '启用'
};

// 用户状态颜色映射
export const USER_STATUS_COLORS = {
  [UserStatus.DISABLED]: 'red',
  [UserStatus.ENABLED]: 'green'
};
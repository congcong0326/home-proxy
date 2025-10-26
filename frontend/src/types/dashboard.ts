// Dashboard相关的类型定义

// 用户流量统计
export interface UserTrafficStats {
  userId: number;
  username: string;
  byteIn: number;
  byteOut: number;
  totalBytes: number;
  period?: string;
}

// 时间序列数据点
export interface TimeSeriesPoint {
  ts: string; // LocalDateTime
  byteIn: number;
  byteOut: number;
}

// WOL配置
export interface WolConfig {
  id: number;
  name: string;
  ipAddress: string;
  subnetMask: string;
  macAddress: string;
  wolPort: number;
  status: number;
  enabled: boolean;
  notes?: string;
  createdAt: string;
  updatedAt: string;
  online?: boolean;
}

// PC状态
export interface PcStatus {
  name: string;
  ip: string;
  online?: boolean;
  enabled: boolean;
  macAddress: string;
  wolPort: number;
  notes?: string;
}

// IP状态响应
export interface IpStatusResponse {
  ip: string;
  online: boolean;
  config?: WolConfig;
}

// WOL操作响应
export interface WolResponse {
  message: string;
}

// 流量趋势查询参数
export interface TrafficTrendParams {
  startTime: string;
  endTime: string;
  userId?: number;
}

// 流量统计查询参数
export interface TrafficStatsParams {
  date?: string; // yyyy-MM-dd 格式
  yearMonth?: string; // yyyy-MM 格式
}
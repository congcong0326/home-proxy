export interface ScheduledTask {
  id: number;
  taskKey: string;
  taskType: string;
  bizKey?: string;
  cronExpression: string;
  description?: string;
  enabled: boolean;
  lastExecutedAt?: string;
  createdAt?: string;
  updatedAt?: string;
  config?: Record<string, any>;
}

export interface ScheduledTaskRequest {
  taskKey: string;
  taskType: string;
  bizKey?: string;
  cronExpression: string;
  description?: string;
  config?: Record<string, any>;
  enabled?: boolean;
}

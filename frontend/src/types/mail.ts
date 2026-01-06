export type MailSendStatus = 'SUCCESS' | 'FAILED';

export interface MailGateway {
  id?: number;
  name: string;
  host: string;
  port: number;
  username: string;
  password: string;
  protocol?: string;
  sslEnabled?: boolean;
  starttlsEnabled?: boolean;
  fromAddress?: string;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface MailTarget {
  id?: number;
  bizKey: string;
  toList: string;
  ccList?: string;
  bccList?: string;
  gatewayId?: number;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface MailSendRequest {
  bizKey: string;
  subject: string;
  content: string;
  contentType?: string;
  requestId?: string;
}

export interface MailSendResponse {
  sendLogId: number;
  status: MailSendStatus;
  errorMessage?: string;
}

export interface MailSendLog {
  id: number;
  bizKey: string;
  gatewayId?: number;
  toList?: string;
  ccList?: string;
  bccList?: string;
  subject?: string;
  contentType?: string;
  contentSize?: number;
  status: MailSendStatus;
  errorMessage?: string;
  requestId?: string;
  createdAt: string;
}

export interface MailSendLogQuery {
  bizKey?: string;
  status?: MailSendStatus;
  timeRange?: string;
  page?: number;
  size?: number;
}

export interface MailSendLogPage {
  items: MailSendLog[];
  page: number;
  size: number;
  total: number;
}

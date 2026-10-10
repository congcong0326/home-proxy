import { ConnectOutcome, TrafficCategory } from './observability';

export type DistributionDimension = 'users' | 'targets' | 'user_targets' | 'routes' | 'client_ips' | 'auth_geo' | 'categories';
export type DistributionMetric = 'connections' | 'bytes' | 'upload' | 'download' | 'authentications';
export interface DistributionQuery {
  workerId: string;
  from: string;
  to: string;
  dimension: DistributionDimension;
  metric: DistributionMetric;
  limit: number;
  trafficCategory: TrafficCategory | 'ALL';
  connectOutcome: ConnectOutcome | 'ALL';
  authOutcome?: 'ALL' | 'SUCCESS' | 'FAILURE';
  username?: string;
  clientIp?: string;
}
export interface DistributionCounters {
  connections: string;
  upload: string;
  download: string;
  bytes: string;
}
export interface AuthenticationCounts {
  attempts: string;
  success: string;
  failure: string;
  other: string;
}
export interface DistributionItem {
  keys: string[];
  counters?: DistributionCounters;
  auth?: AuthenticationCounts;
  unknownReasons?: Record<string, string>;
  sources?: { clientIp: string; counts: AuthenticationCounts }[];
  value: string;
}
export interface DistributionData {
  from: string;
  to: string;
  dimension: DistributionDimension;
  metric: DistributionMetric;
  items: DistributionItem[];
  totals?: DistributionCounters;
  authTotals?: AuthenticationCounts;
  totalValue: string;
  otherValue: string;
  groups: string;
  missingConnections?: string;
  firstSessionAt?: string | null;
  historyBasis: 'ENDED_SESSIONS_ONLY' | 'AUTH_ATTEMPTS_ONLY';
  timeBoundary: '[from,to)';
}

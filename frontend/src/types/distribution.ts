import { ConnectOutcome, TrafficCategory } from './observability';

export type DistributionDimension = 'users' | 'targets' | 'user_targets' | 'routes' | 'client_ips' | 'categories';
export type DistributionMetric = 'connections' | 'bytes' | 'upload' | 'download';
export interface DistributionQuery {
  workerId: string;
  from: string;
  to: string;
  dimension: DistributionDimension;
  metric: DistributionMetric;
  limit: number;
  trafficCategory: TrafficCategory | 'ALL';
  connectOutcome: ConnectOutcome | 'ALL';
  username?: string;
  clientIp?: string;
}
export interface DistributionCounters {
  connections: string;
  upload: string;
  download: string;
  bytes: string;
}
export interface DistributionItem {
  keys: string[];
  counters: DistributionCounters;
  value: string;
}
export interface DistributionData {
  from: string;
  to: string;
  dimension: DistributionDimension;
  metric: DistributionMetric;
  items: DistributionItem[];
  totals: DistributionCounters;
  totalValue: string;
  otherValue: string;
  groups: string;
  missingConnections: string;
  firstSessionAt: string | null;
  historyBasis: 'ENDED_SESSIONS_ONLY';
  timeBoundary: '[from,to)';
}

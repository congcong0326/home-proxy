import { TrafficCategory } from './observability';

export type ResultFilter = 'ALL' | 'SUCCESS' | 'FAILURE' | 'BLOCKED' | 'CANCELLED' | 'CHALLENGE' | 'UNKNOWN';
export interface OverviewQuery {
  workerId: string;
  from: string;
  to: string;
  timeZone: string;
  clientIp?: string;
  username?: string;
  trafficCategory: TrafficCategory | 'ALL';
  trafficResult: ResultFilter;
  connectionResult: ResultFilter;
  dnsResult: ResultFilter;
  authResult: ResultFilter;
  dnsOrigin: 'CLIENT' | 'PROXY_RESOLVE' | 'ALL';
}
export interface OverviewPoint {
  ts: string;
  [key: string]: string;
}
export interface CountOverview {
  series: OverviewPoint[];
  totals: Record<string, string>;
  total: string;
}
export interface LiveConnections {
  available: boolean;
  count?: string;
  sampledAt?: string;
  reason?: 'UNSUPPORTED' | 'STALE' | 'GROUPS_TRUNCATED';
}
export interface OverviewData {
  from: string;
  to: string;
  timeZone: string;
  bucketSeconds: number;
  firstSessionAt: string;
  historyBasis: 'ENDED_SESSIONS_ONLY';
  traffic: {
    upload: string;
    download: string;
    total: string;
    daily: string;
    monthly: string;
    series: OverviewPoint[];
    sources: { userId: string | null; username: string | null; clientIp: string | null; upload: string; download: string; connections: string }[];
    categories: { category: TrafficCategory; bytes: string }[];
  };
  connections: CountOverview;
  concurrency: { series: { ts: string; average: string; peak: string }[]; average: string; peak: string };
  live: LiveConnections;
  dns: CountOverview;
  auth: CountOverview;
}

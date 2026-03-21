export enum RuleSetCategory {
  BUILTIN = 'BUILTIN',
  AI = 'AI',
  GEO = 'GEO',
  AD = 'AD',
  STREAMING = 'STREAMING',
  SAAS = 'SAAS',
  DEV = 'DEV',
  CUSTOM = 'CUSTOM',
}

export enum RuleSetMatchTarget {
  DOMAIN = 'DOMAIN',
  IP_CIDR = 'IP_CIDR',
}

export enum RuleSetSourceType {
  MANUAL = 'MANUAL',
  GIT_RAW_FILE = 'GIT_RAW_FILE',
  GITHUB_RELEASE_ASSET = 'GITHUB_RELEASE_ASSET',
  HTTP_FILE = 'HTTP_FILE',
}

export enum RuleSetItemType {
  DOMAIN = 'DOMAIN',
  DOMAIN_SUFFIX = 'DOMAIN_SUFFIX',
  DOMAIN_KEYWORD = 'DOMAIN_KEYWORD',
}

export enum RuleSetSourceFormat {
  DOMAIN_LIST_COMMUNITY = 'DOMAIN_LIST_COMMUNITY',
  CLASH_CLASSICAL = 'CLASH_CLASSICAL',
  PLAIN_DOMAIN_LIST = 'PLAIN_DOMAIN_LIST',
}

export enum RuleSetSyncStatus {
  UPDATED = 'UPDATED',
  UNCHANGED = 'UNCHANGED',
  FAILED = 'FAILED',
  SKIPPED = 'SKIPPED',
}

export interface RuleSetItem {
  type: RuleSetItemType;
  value: string;
}

export interface RuleSetDTO {
  id: number;
  ruleKey: string;
  name: string;
  category: RuleSetCategory;
  matchTarget: RuleSetMatchTarget;
  sourceType: RuleSetSourceType;
  sourceConfig?: string;
  enabled: boolean;
  published: boolean;
  versionNo: number;
  description?: string;
  items: RuleSetItem[];
  itemCount?: number;
  createdAt: string;
  updatedAt: string;
}

export interface RuleSetSummaryDTO {
  id: number;
  ruleKey: string;
  name: string;
  category: RuleSetCategory;
  matchTarget: RuleSetMatchTarget;
  sourceType: RuleSetSourceType;
  sourceConfig?: string;
  enabled: boolean;
  published: boolean;
  versionNo: number;
  description?: string;
  itemCount?: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateRuleSetRequest {
  ruleKey: string;
  name: string;
  category: RuleSetCategory;
  matchTarget: RuleSetMatchTarget;
  sourceType: RuleSetSourceType;
  sourceConfig?: string;
  enabled?: boolean;
  published?: boolean;
  description?: string;
  items: RuleSetItem[];
}

export interface UpdateRuleSetRequest {
  ruleKey?: string;
  name?: string;
  category?: RuleSetCategory;
  matchTarget?: RuleSetMatchTarget;
  sourceType?: RuleSetSourceType;
  sourceConfig?: string;
  enabled?: boolean;
  published?: boolean;
  description?: string;
  items?: RuleSetItem[];
}

export interface RuleSetQueryParams {
  page?: number;
  size?: number;
  sort?: string;
  direction?: 'asc' | 'desc';
  name?: string;
  category?: RuleSetCategory;
  enabled?: boolean;
  published?: boolean;
}

export interface RuleSetItemQueryParams {
  page?: number;
  size?: number;
}

export interface RuleSetPageResponse<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

export interface RuleSetBatchSyncRequest {
  ruleSetIds?: number[];
  enabledOnly?: boolean;
  publishedOnly?: boolean;
}

export interface RuleSetSyncResult {
  ruleSetId: number;
  ruleKey: string;
  name: string;
  status: RuleSetSyncStatus;
  versionNo: number;
  itemCount: number;
  message?: string;
}

export const RULE_SET_CATEGORY_LABELS: Record<RuleSetCategory, string> = {
  [RuleSetCategory.BUILTIN]: '内置',
  [RuleSetCategory.AI]: 'AI',
  [RuleSetCategory.GEO]: '地理',
  [RuleSetCategory.AD]: '广告',
  [RuleSetCategory.STREAMING]: '流媒体',
  [RuleSetCategory.SAAS]: 'SaaS',
  [RuleSetCategory.DEV]: '开发',
  [RuleSetCategory.CUSTOM]: '自定义',
};

export const RULE_SET_SOURCE_LABELS: Record<RuleSetSourceType, string> = {
  [RuleSetSourceType.MANUAL]: '手工维护',
  [RuleSetSourceType.GIT_RAW_FILE]: 'Git Raw 文件',
  [RuleSetSourceType.GITHUB_RELEASE_ASSET]: 'GitHub Release 资产',
  [RuleSetSourceType.HTTP_FILE]: 'HTTP 文件',
};

export const RULE_SET_MATCH_TARGET_LABELS: Record<RuleSetMatchTarget, string> = {
  [RuleSetMatchTarget.DOMAIN]: '域名',
  [RuleSetMatchTarget.IP_CIDR]: 'IP/CIDR',
};

export const RULE_SET_ITEM_TYPE_LABELS: Record<RuleSetItemType, string> = {
  [RuleSetItemType.DOMAIN]: '精确域名',
  [RuleSetItemType.DOMAIN_SUFFIX]: '域名后缀',
  [RuleSetItemType.DOMAIN_KEYWORD]: '域名关键字',
};

export const RULE_SET_SYNC_STATUS_COLORS: Record<RuleSetSyncStatus, string> = {
  [RuleSetSyncStatus.UPDATED]: 'processing',
  [RuleSetSyncStatus.UNCHANGED]: 'default',
  [RuleSetSyncStatus.FAILED]: 'error',
  [RuleSetSyncStatus.SKIPPED]: 'warning',
};

export const RULE_SET_SOURCE_FORMAT_OPTIONS = [
  { value: RuleSetSourceFormat.DOMAIN_LIST_COMMUNITY, label: 'domain-list-community' },
  { value: RuleSetSourceFormat.CLASH_CLASSICAL, label: 'Clash Classical' },
  { value: RuleSetSourceFormat.PLAIN_DOMAIN_LIST, label: 'Plain Domain List' },
];

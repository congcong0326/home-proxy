export interface RewriteEntryDTO {
  id?: number;
  domainPattern: string;
  targetHost: string;
  targetPort: number;
  enabled: boolean;
  notes?: string;
}

export interface RewriteTableDTO {
  id: number;
  name: string;
  notes?: string;
  entries: RewriteEntryDTO[];
  routeCount: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface RewriteTableRequest {
  name: string;
  notes?: string;
  entries: RewriteEntryDTO[];
}

export interface RewriteTableImportRequest {
  text: string;
  replace: boolean;
}

export interface RewriteMergeRequest {
  routeIds: number[];
  tableName: string;
  routeName: string;
  dryRun: boolean;
}

export interface RewriteMergeBindingChange {
  inboundId: number;
  inboundName: string;
  bindingIndex: number;
  userIds?: number[];
  before: number[];
  /** null 代表新建的改写表路由 */
  after: (number | null)[];
  reorderedRoutes: string[];
  gainedRoutes: string[];
}

export interface RewriteMergePreview {
  entries: RewriteEntryDTO[];
  bindings: RewriteMergeBindingChange[];
  tableId?: number;
  routeId?: number;
}

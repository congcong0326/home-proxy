/** Independent V1 API contract. All UInt64 fields are decimal strings, never JS numbers. */
export type UInt64String = string;
export type TelemetryType = 'sessions' | 'dns' | 'events' | 'credential-auth';
export type TrafficCategory = 'INTERNAL_REWRITE' | 'EXTERNAL' | 'OTHER_INTERNAL' | 'UNKNOWN';
export type NetworkScope = 'INTERNAL' | 'EXTERNAL' | 'UNKNOWN';
export type ConnectOutcome = 'CONNECTED' | 'FAILED' | 'BLOCKED' | 'CANCELLED' | 'NOT_ATTEMPTED';
export type SessionOutcome = 'NORMAL_END' | 'ERROR_END' | 'BLOCKED' | 'CANCELLED';
export type AuthOutcome = 'SUCCESS' | 'FAILURE' | 'DENIED' | 'CHALLENGE' | 'MALFORMED' | 'CANCELLED' | 'ERROR';
export type IdentitySource = 'ACCOUNT_AUTH' | 'DEVICE_IP' | 'ANONYMOUS' | 'UNKNOWN';

export interface TelemetryRecordV1 {
  recordId: string;
  workerId: string;
  workerInstanceId: string;
  connectionId: string | null;
  inboundName: string | null;
  username: string | null;
  clientIp: string | null;
  clientIpSource: string | null;
  transportPeerIp: string | null;
  configVersion: string | null;
  ruleSetVersion: string | null;
  inboundProtocol: string | null;
  schemaVersion: 1;
  clientPort: number | null;
  transportPeerPort: number | null;
  inboundId: UInt64String | null;
  userId: UInt64String | null;
  occurredAt: string;
  identitySource: IdentitySource | null;
  receivedAt: string;
}

export interface SessionLogV1 extends TelemetryRecordV1 {
  /** Query-time associations; these attempts do not establish verified user identity. */
  credentialAuthAttempts?: Pick<CredentialAuthLogV1, 'authAttemptId' | 'attemptedUsername' | 'authOutcome' | 'authMethod' | 'occurredAt'>[];
  credentialAuthAttemptsTruncated?: boolean;
  startedAt: string;
  connectedAt: string | null;
  endedAt: string;
  firstUploadAt: string | null;
  firstDownloadAt: string | null;
  lastActivityAt: string | null;
  connectOutcome: ConnectOutcome;
  sessionOutcome: SessionOutcome;
  failureStage: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  closeInitiator: string | null;
  closeReason: string | null;
  originalDstIp: string | null;
  logicalTargetHost: string | null;
  targetSource: string | null;
  effectiveTargetHost: string | null;
  selectedTargetIp: string | null;
  outboundPeerIp: string | null;
  outboundMode: string | null;
  outboundProtocol: string | null;
  outboundNodeId: string | null;
  routeName: string | null;
  routeAction: string | null;
  routeSelection: string | null;
  matchedRuleId: string | null;
  matchedRuleSet: string | null;
  targetAddressClass: string | null;
  trafficCategoryBasis: string | null;
  channelReadyBasis: string | null;
  targetConfirmationLevel: string | null;
  proxyHandshakeOutcome: string | null;
  originalDstPort: number | null;
  logicalTargetPort: number | null;
  effectiveTargetPort: number | null;
  outboundPeerPort: number | null;
  trafficClassificationVersion: number | null;
  uploadBytes: UInt64String;
  downloadBytes: UInt64String;
  sessionDurationUs: UInt64String | null;
  channelSetupDurationUs: UInt64String | null;
  clientHandshakeDurationUs: UInt64String | null;
  targetRecoveryDurationUs: UInt64String | null;
  routeSelectionDurationUs: UInt64String | null;
  dnsDurationUs: UInt64String | null;
  outboundTcpDurationUs: UInt64String | null;
  outboundTlsDurationUs: UInt64String | null;
  proxyHandshakeDurationUs: UInt64String | null;
  uploadBackpressureDurationUs: UInt64String | null;
  downloadBackpressureDurationUs: UInt64String | null;
  routeId: UInt64String | null;
  destinationOverrideApplied: boolean | null;
  targetNetworkScope: NetworkScope | null;
  clientNetworkScope: NetworkScope | null;
  outboundPeerNetworkScope: NetworkScope | null;
  trafficCategory: TrafficCategory | null;
}

export interface DnsQueryLogV1 extends TelemetryRecordV1 {
  queryId: string | null;
  parentConnectionId: string | null;
  queryOrigin: string | null;
  resolvePurpose: string | null;
  qname: string | null;
  qtype: string | null;
  qclass: string | null;
  transport: string | null;
  outcome: string | null;
  answerMode: string | null;
  emptyAnswerReason: string | null;
  cacheStatus: string | null;
  sharedLookupId: string | null;
  resolverEndpoint: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  mappingId: string | null;
  mappingRegistryId: string | null;
  rcode: number | null;
  ttlSeconds: number | null;
  upstreamAttemptCount: number | null;
  dnsRouteId: UInt64String | null;
  durationUs: UInt64String | null;
  upstreamDurationUs: UInt64String | null;
  requestBytes: UInt64String | null;
  responseBytes: UInt64String | null;
  responseSent: boolean | null;
  answerIps: string[];
}

export interface ProxyEventLogV1 extends TelemetryRecordV1 {
  eventId: string | null;
  queryId: string | null;
  eventType: string | null;
  stage: string | null;
  outcome: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  recoverySource: string | null;
  mappingOutcome: string | null;
  fallbackOutcome: string | null;
  eventSequence: UInt64String | null;
  durationUs: UInt64String | null;
  repeatCount: UInt64String | null;
  firstOccurredAt: string | null;
  lastOccurredAt: string | null;
}

export interface CredentialAuthAnalysisV1 {
  authAttemptId: string | null;
  sourceCountryCode: string | null;
  sourceCountry: string | null;
  sourceRegion: string | null;
  sourceCity: string | null;
  geoLookupStatus: string | null;
  geoDatabaseVersion: string | null;
  riskRuleVersion: string | null;
  baselineStatus: string | null;
  comparisonBasis: string | null;
  sourceNetworkScope: NetworkScope | null;
  riskTags: string[];
}

export interface CredentialAuthLogV1 extends TelemetryRecordV1 {
  authAttemptId: string | null;
  authMethod: string | null;
  authEventKind: string | null;
  attemptedUsername: string | null;
  verifiedUsername: string | null;
  reasonCode: string | null;
  authOutcome: AuthOutcome | null;
  authDurationUs: UInt64String | null;
  analysis: CredentialAuthAnalysisV1 | null;
}

export interface TelemetryQuery {
  workerId: string;
  from: string;
  to: string;
  limit?: number;
  offset?: number;
  connectionId?: string;
  username?: string;
  clientIp?: string;
  target?: string;
  protocol?: string;
  outcome?: string;
  connectOutcome?: string;
  route?: string;
  trafficCategory?: TrafficCategory;
  qtype?: string;
  eventType?: string;
  authMethod?: string;
  authOutcomeGroup?: 'SUCCESS' | 'FAILURE';
  exactAuthUsername?: string;
}

export type AuditRecordV1 = SessionLogV1 | DnsQueryLogV1 | ProxyEventLogV1 | CredentialAuthLogV1;

export interface TelemetryPageV1<T extends TelemetryRecordV1 = AuditRecordV1> {
  items: T[];
  total: UInt64String;
  limit: number;
  offset: number;
  maxOffset: number;
  timeBoundary: '[from,to)';
}

export interface TelemetryStatusV1 {
  schemaVersion: 1;
  ingestEnabled: boolean;
  queryEnabled: boolean;
  delivery: 'BEST_EFFORT_WITH_ACK';
  scope: 'STAGE_A_INFRASTRUCTURE';
  authenticationAnalysis: 'GEOIP_CONTRACT_ONLY';
  worker?: {
    capability: 'V1_NEGOTIATED' | 'UNSUPPORTED_OR_DISABLED';
    online: boolean;
    complete: boolean;
    enabled?: boolean;
    instanceId?: string;
    queueDepth?: number;
    retries?: number;
    acks?: number;
    lastSeenAt?: string;
    bufferMode?: 'MEMORY';
    restartRecoverySupported?: false;
    bufferBytes?: number;
    bufferCapacity?: number;
    bufferMaxBytes?: number;
    dropped?: number;
    serializationFailures?: number;
  };
}

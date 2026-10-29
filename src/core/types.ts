export enum RecordType {
  CALL = 'call',
  SMS = 'sms',
  CLIP = 'clip',
}

export enum OperationType {
  CREATE = 'create',
  UPDATE = 'update',
  DELETE = 'delete',
  LIST = 'list',
  GET = 'get',
  WRITE = 'write',
}

export enum ClipResult {
  SAVED = 'SAVED',
  DUPLICATE = 'DUPLICATE',
  SENSITIVE = 'SENSITIVE',
  EMPTY = 'EMPTY',
  TOO_LONG = 'TOO_LONG',
  DISABLED = 'DISABLED',
}

export enum PeriodFilter {
  ALL = 'ALL',
  D7 = 'D7',
  D30 = 'D30',
  Y1 = 'Y1',
}

export interface AccountSession {
  uid: string;
  username: string;
}

export interface CallFields {
  number: string;
  type: string;
  durationSec: number;
  name?: string;
}

export interface SmsFields {
  address: string;
  body: string;
  type: string;
}

export interface ClipFields {
  text: string;
}

export type RecordFields = Record<string, string | number | undefined>;

export interface PlainRecord {
  id: string;
  deviceId: string;
  type: RecordType;
  ts: number;
  fields: RecordFields;
}

export interface EncRecord {
  id: string;
  deviceId: string;
  type: RecordType;
  ts: number;
  iv: string;
  data: string;
}

export interface DeviceInfo {
  id: string;
  ownerId: string;
  name: string;
  model: string;
  enabled: RecordType[];
  lastSyncAt: number | null;
}

export type SyncStateCode = 0 | 1 | 2 | 3;

export interface SyncErrorDiagnostic {
  summary: string;
  remediation: string;
  occurredAt: number;
}

export interface LocalQueuedRecord {
  id: string;
  uid: string;
  deviceId: string;
  type: RecordType;
  ts: number;
  json: string;
  syncState: SyncStateCode;
  createdAt: number;
  attemptCount?: number;
  lastError?: string | null;
}

export interface FirestoreErrorInfo {
  error: string;
  operationType: OperationType;
  path: string | null;
  authInfo: {
    userId?: string | null;
    email?: string | null;
    emailVerified?: boolean | null;
    isAnonymous?: boolean | null;
    tenantId?: string | null;
    providerInfo?: {
      providerId?: string | null;
      email?: string | null;
    }[];
  };
}

export type ExportFormat = 'csv' | 'json' | 'txt';

export interface ExportRequest {
  deviceId: string;
  deviceName: string;
  types: RecordType[];
  formats: ExportFormat[];
  from: number | null;
  to: number | null;
  zip: boolean;
}

export interface ExportOutcome {
  fileName: string;
  recordCount: number;
  fileCount: number;
  verifiedHashes: Record<string, string>;
  warning?: string;
}

export interface NormalizedUsername {
  username: string;
  usernameLower: string;
  usernameId: string;
  syntheticEmail: string;
}

/**
 * Wire types of the Skeleton Backend API and of the restricted definition projections.
 *
 * Aligned with the backend DTOs (read only): LoginResponse/MeResponse (AuthService), RecordItem and
 * RecordItemPage (record package), InstanceView, HistoryEntryView (app package), DefinitionVersionRef
 * (definition package) and ApiError/ApiViolation (web package).
 *
 * Known mismatches between the design and the backend (recorded, not worked around):
 * - HistoryEntry.detail: the design says string; the backend sends a JsonNode (any JSON value or null),
 *   so it is typed `unknown` here and must be rendered through interpolation of a stringified value.
 * - RecordSummary: the backend also sends appCode, defVersion, entityCode, createdBy, createdAt and
 *   updatedAt. They are declared optional below because the design does not use them.
 * - InstanceView.fieldRules: the backend sends Map<String, String>; narrowed here to FieldRule.
 * - Instants (dueAt, eventTime, createdAt, updatedAt) and UUIDs are JSON strings.
 * - ApiError.violations is omitted by the backend when null (NON_NULL); toApiError() always fills it.
 */

export type FieldRule = 'hidden' | 'readonly' | 'optional' | 'required';

export type FieldType =
  | 'TEXT'
  | 'TEXTAREA'
  | 'NUMBER'
  | 'DECIMAL'
  | 'DATE'
  | 'DATETIME'
  | 'BOOLEAN'
  | 'SELECT'
  | 'MULTISELECT'
  | 'FILE'
  | 'USER'
  | 'REFERENCE'
  | 'EMAIL'
  | 'PHONE'
  | 'LOCATION';

export interface FieldDef {
  code: string;
  label: string;
  type: FieldType;
  required?: boolean;
  help?: string;
  default?: unknown;
  options?: { value: string; label: string }[];
  ref?: string;
  min?: number;
  max?: number;
  maxLength?: number;
  pattern?: string;
  accept?: string;
  maxFiles?: number;
  widget?: string;
}

export interface SectionDef {
  title?: string;
  columns?: 1 | 2 | 3;
  fields: string[];
}

export interface Violation {
  field: string;
  rule: string;
  message?: string;
}

export interface ApiError {
  status: number;
  code: string;
  message: string;
  requestId?: string;
  violations: Violation[];
  line?: number;
  column?: number;
}

export interface LoginResponse {
  token: string;
  tokenType: string;
  expiresInSeconds: number;
  userId: string;
  displayName: string;
  roles: string[];
}

export interface MeResponse {
  userId: string;
  displayName: string;
  roles: string[];
}

export interface Branding {
  primaryColor?: string;
  logo?: string;
  theme?: 'light' | 'dark' | 'auto';
}

export interface AppInfo {
  code: string;
  name: string;
  description?: string;
  branding?: Branding;
}

export interface FormDef {
  code: string;
  label?: string;
  entity: string;
  public?: boolean;
  roles?: string[];
  startsWorkflow?: string;
  sections: SectionDef[];
  fields?: FieldDef[];
}

export interface PublicSubset {
  app: AppInfo;
  forms: FormDef[];
  entities?: { code: string; fields: FieldDef[] }[];
}

export interface ViewDef {
  code: string;
  label?: string;
  type: 'TABLE' | 'KANBAN' | 'MY_TASKS';
  entity: string;
  columns?: string[];
  filters?: string[];
  defaultSort?: { field: string; direction?: 'ASC' | 'DESC' };
  scope?: string;
}

export interface EntityDef {
  code: string;
  label: string;
  titleField?: string;
  referencePrefix?: string;
  fields: FieldDef[];
}

export interface StatusDef {
  label: string;
  category: string;
  color?: string;
}

export interface StepPresentation {
  code: string;
  label?: string;
  type: string;
}

export interface WorkflowPresentation {
  code: string;
  label?: string;
  entity: string;
  statuses: StatusDef[];
  steps: StepPresentation[];
}

export interface RestrictedDefinition {
  app: AppInfo;
  roles: { code: string; label: string }[];
  entities: EntityDef[];
  forms: FormDef[];
  views: ViewDef[];
  workflows: WorkflowPresentation[];
}

export interface RecordSummary {
  id: string;
  data: Record<string, unknown>;
  referenceNo: string;
  instanceId: string | null;
  step?: string | null;
  stepLabel?: string | null;
  status?: string | null;
  statusColor?: string | null;
  assignee?: string | null;
  assigneeName?: string | null;
  dueAt?: string | null;
  ended?: boolean;
  // Sent by the backend (RecordItem), not used by the design.
  appCode?: string;
  defVersion?: number;
  entityCode?: string;
  createdBy?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  sort?: string | null;
}

export interface SubmitResult {
  recordId: string;
  referenceNo: string;
  instanceId: string | null;
}

export interface ActionInfo {
  code: string;
  label: string;
  style: 'primary' | 'default' | 'danger';
  confirm?: string | null;
  remarks: 'hidden' | 'optional' | 'required';
  attachment: 'hidden' | 'optional' | 'required';
}

export interface InstanceView {
  instanceId: string;
  recordId: string;
  step: string;
  status: string;
  ended: boolean;
  dueAt?: string | null;
  assignee?: string | null;
  assigneeName?: string | null;
  actions: ActionInfo[];
  fieldRules: Record<string, FieldRule>;
}

export interface ActionBody {
  fields: Record<string, unknown>;
  remarks?: string;
  attachment?: string;
}

/** Matches the backend HistoryEntryView exactly. `detail` is any JSON value (JsonNode) or null. */
export interface HistoryEntry {
  kind: string;
  actionCode?: string | null;
  fromStep?: string | null;
  toStep?: string | null;
  fromStatus?: string | null;
  toStatus?: string | null;
  actorId?: string | null;
  actorName?: string | null;
  remarks?: string | null;
  attachmentRef?: string | null;
  detail?: unknown;
  eventTime: string;
  timeInStepMs: number;
}

export interface VersionRef {
  id: string;
  version: number;
  status: 'DRAFT' | 'PUBLISHED';
  contentHash: string;
  createdAt?: string;
  publishedAt?: string;
  app?: string;
}

export type ValidatorReport = Violation[];

export interface ListQuery {
  page: number;
  size: number;
  sort?: string;
  view: string;
  filters: Record<string, string>;
}

import Network from "./network";

export interface AwsAccountSummary {
  id: number;
  name: string;
  accessKeyId?: string;
  groupName?: string;
  groupId?: number;
  proxyId?: number;
  region?: string;
  regions?: string;
  remark?: string;
  mfaSerialNumber?: string;
  roleArn?: string;
  roleSessionName?: string;
  proxyConfigured?: boolean;
  sessionTokenConfigured?: boolean;
  mfaConfigured?: boolean;
  enabled?: boolean;
  expiresAt?: number | null;
  instanceCount?: number;
  vcpuQuota?: number;
  vcpuUsed?: number;
}

export interface AwsAccountSecrets {
  accessKeyId?: string;
  accessSecret?: string;
  secretAccessKey?: string;
  sessionToken?: string;
  mfaSecret?: string;
  mfaSerial?: string;
  mfaSerialNumber?: string;
  proxy?: string;
  proxyUrl?: string;
  roleArn?: string;
  roleExternalId?: string;
  roleSessionName?: string;
}

export interface AwsAccountPayload {
  id?: number;
  name: string;
  accessKeyId: string;
  secretAccessKey: string;
  accessSecret?: string;
  sessionToken?: string;
  mfaSerial?: string;
  mfaSerialNumber?: string;
  mfaSecret?: string;
  proxy?: string;
  groupName?: string;
  regions?: string;
  note?: string;
  remark?: string;
  region?: string;
  group?: string;
  groupId?: number;
  proxyId?: number;
  roleArn?: string;
  roleExternalId?: string;
  roleSessionName?: string;
  clearSessionToken?: boolean;
  clearMfa?: boolean;
  clearProxy?: boolean;
  expiresAt?: number | null;
}

export interface AwsAccountGroup {
  id: number;
  name: string;
  remark?: string;
  accountCount?: number;
}
export interface AwsProxy {
  id: number;
  name: string;
  endpoint?: string;
  configured?: boolean;
  remark?: string;
}
export interface AwsQuota {
  vcpuQuota?: number;
  vcpuUsed?: number;
  spotVcpuQuota?: number;
  region?: string;
  message?: string;
}
export interface AwsImportResult {
  imported?: number;
  successCount?: number;
  failed?: number;
  errors?: Array<string | { line?: number; message?: string }>;
}

export interface AwsInstance {
  id?: string;
  instanceId: string;
  name?: string;
  state?: string;
  status?: string;
  accountId?: number;
  accountName?: string;
  region?: string;
  availabilityZone?: string;
  publicIpv4?: string;
  publicIp?: string;
  privateIpv4?: string;
  ipv6?: string;
  ipv6Addresses?: string[];
  instanceType?: string;
  imageId?: string;
  vcpu?: number;
  memoryGiB?: number;
  bandwidth?: string;
  createdAt?: string | number;
  launchTime?: string | number;
  configuredAt?: string | number;
  nodeId?: number;
  nodeName?: string;
  elasticIp?: boolean;
  gfwStatus?: string;
  spot?: boolean;
  stateReasonCode?: string;
  spotInterrupted?: boolean;
}

export interface AwsInstanceFilter {
  keyword?: string;
  accountId?: number | string;
  region?: string;
  state?: string;
}

export interface AwsTemplate {
  id: number;
  name: string;
  accountId?: number;
  region?: string;
  availabilityZone?: string;
  instanceType?: string;
  imageId?: string;
  architecture?: string;
  diskGiB?: number;
  rootVolumeSize?: string;
  keyName?: string;
  subnetId?: string;
  securityGroupIds?: string;
  tagsJson?: string;
  remark?: string;
  password?: string;
  userData?: string;
  spot?: boolean;
  enableIpv6?: boolean;
  disablePublicIp?: boolean;
  staticIp?: boolean;
  cidr?: string;
}

export interface AwsTemplatePayload extends Omit<AwsTemplate, "id"> {
  id?: number;
}

export interface AwsCreatePayload
  extends Omit<
    AwsTemplatePayload,
    "id" | "rootVolumeSize" | "securityGroupIds"
  > {
  templateId?: number;
  rootVolumeSize?: number;
  securityGroupIds?: string[];
  clientToken: string;
}

export interface AwsBoundNode {
  id: number;
  name: string;
  cloudProvider?: string;
  awsAccountId?: number;
  awsInstanceId?: string;
  awsRegion?: string;
  gfwStatus?: string;
  gfwSource?: string;
  gfwLastCheckAt?: number;
  wallMonitorExternalStatus?: string;
  wallMonitorExternalLastCheckAt?: number;
}

export interface AwsShareGroup {
  id: number;
  name: string;
  expiresAt?: number | null;
  permissions: "read" | "operate";
  nodeIds?: number[];
  instanceRefs?: AwsShareInstanceRef[];
  nodeCount?: number;
  accessToken?: string;
  url?: string;
}

export interface AwsShareInstanceRef {
  accountId: number;
  region: string;
  instanceId: string;
  name?: string;
}

export interface AwsFailoverConfig {
  enabled: boolean;
  backupGroupId?: number;
  maxInstances?: number;
  cron?: string;
  conditions: string[];
  telegramEnabled: boolean;
  nodeIds: number[];
  templateId?: number;
  accountSelection: "ordered" | "random";
  requiredVcpu?: number;
  trafficLimitBytes?: number;
  telegramBotToken?: string;
  telegramChatId?: string;
  telegramConfigured?: boolean;
  schedulerActive?: boolean;
}

export interface AwsFailoverLog {
  id: number;
  nodeId?: number;
  nodeName?: string;
  status?: string;
  condition?: string;
  reason?: string;
  message?: string;
  result?: string;
  createdTime?: number;
  updatedTime?: number;
  oldInstanceId?: string;
  newInstanceId?: string;
  oldAccountId?: number;
  newAccountId?: number;
}

export interface AwsActionPayload {
  instanceId: string;
  accountId?: number;
  region?: string;
  action:
    | "start"
    | "stop"
    | "reboot"
    | "terminate"
    | "modify"
    | "reinstall"
    | "ipv6-enable"
    | "ipv6-assign"
    | "ipv6-unassign";
  name?: string;
  userData?: string;
  instanceType?: string;
  rootVolumeSize?: number;
  rootVolumeType?: string;
  rootVolumeIops?: number;
  rootVolumeThroughput?: number;
  imageId?: string;
  clientToken?: string;
  requestId?: string;
  ipv6Addresses?: string[];
}

export interface AwsChangeIpPayload {
  instanceId: string;
  accountId?: number;
  region?: string;
  strategy: "AUTO" | "DYNAMIC" | "EIP";
  releaseOld: boolean;
  cidr?: string;
  maxAttempts?: number;
  requestId?: string;
}

export interface AwsOperation {
  id?: string;
  operationId?: string;
  status: "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED" | "UNKNOWN";
  action?: string;
  accountId?: number;
  region?: string;
  instanceId?: string;
  message?: string;
  result?: unknown;
  createdTime?: number;
  updatedTime?: number;
  createdAt?: number;
  finishedAt?: number;
}

export const listAwsAccounts = () =>
  Network.post<AwsAccountSummary[]>("/aws/account/list");

export const saveAwsAccount = (data: AwsAccountPayload) =>
  Network.post<void>("/aws/account/save", data);

export const deleteAwsAccount = (id: number) =>
  Network.post<void>("/aws/account/delete", { id });

export const setAwsAccountEnabled = (id: number, enabled: boolean) =>
  Network.post<void>("/aws/account/status", { id, enabled });

export const getAwsAccountSecrets = (id: number) =>
  Network.post<AwsAccountSecrets>("/aws/account/private-secret", { id });

export const testAwsAccount = (id: number) =>
  Network.post<{
    ok: boolean;
    instanceCount?: number;
    region?: string;
    message?: string;
  }>("/aws/account/test", { id });

export const getAwsAccountQuota = (id: number, region?: string) =>
  Network.post<AwsQuota>("/aws/account/quota", { id, region });
export const importAwsAccounts = (
  text: string,
  groupId?: number,
  region?: string,
) =>
  Network.post<AwsImportResult>("/aws/account/import", {
    text,
    groupId,
    region,
  });
export const listAwsAccountGroups = () =>
  Network.post<AwsAccountGroup[]>("/aws/account-group/list");
export const saveAwsAccountGroup = (data: Partial<AwsAccountGroup>) =>
  Network.post<void>("/aws/account-group/save", data);
export const deleteAwsAccountGroup = (id: number) =>
  Network.post<void>("/aws/account-group/delete", { id });
export const listAwsProxies = () => Network.post<AwsProxy[]>("/aws/proxy/list");
export const saveAwsProxy = (data: {
  id?: number;
  name: string;
  url?: string;
  remark?: string;
}) => Network.post<void>("/aws/proxy/save", data);
export const deleteAwsProxy = (id: number) =>
  Network.post<void>("/aws/proxy/delete", { id });

export const listAwsInstances = (filter: AwsInstanceFilter = {}) =>
  Network.post<AwsInstance[]>("/aws/ec2/list", filter);

export const syncAwsInstances = (accountId?: number, region?: string) =>
  Network.post<AwsInstance[]>("/aws/ec2/sync", { accountId, region });

export const operateAwsInstance = (data: AwsActionPayload) =>
  Network.post<AwsOperation>("/aws/ec2/action", data);

export const changeAwsInstanceIp = (data: AwsChangeIpPayload) =>
  Network.post<AwsOperation>("/aws/ec2/change-ip", data);

export const checkAwsInstanceGfw = (data: Omit<AwsActionPayload, "action">) =>
  Network.post<Record<string, unknown>>("/aws/ec2/gfw-check", data);

export const createAwsInstance = (data: AwsCreatePayload) =>
  Network.post<AwsOperation>("/aws/ec2/create", data);

export const getAwsOperation = (id: string) =>
  Network.post<AwsOperation>("/aws/operation/get", { id });
export const listAwsOperations = () =>
  Network.post<AwsOperation[]>("/aws/operation/list");

export const acknowledgeAwsOperation = (id: string) =>
  Network.post<void>("/aws/operation/acknowledge", { id });

export const listAwsTemplates = () =>
  Network.post<AwsTemplate[]>("/aws/template/list");

export const saveAwsTemplate = (data: AwsTemplatePayload) =>
  Network.post<void>("/aws/template/save", data);

export const deleteAwsTemplate = (id: number) =>
  Network.post<void>("/aws/template/delete", { id });

export const listAwsShareGroups = () =>
  Network.post<AwsShareGroup[]>("/aws/share-group/list");

export const saveAwsShareGroup = (data: Partial<AwsShareGroup>) =>
  Network.post<void>("/aws/share-group/save", data);

export const deleteAwsShareGroup = (id: number) =>
  Network.post<void>("/aws/share-group/delete", { id });

export const getAwsFailoverConfig = () =>
  Network.post<AwsFailoverConfig>("/aws/failover/get");

export const saveAwsFailoverConfig = (data: AwsFailoverConfig) =>
  Network.post<void>("/aws/failover/save", data);

export const listAwsFailoverLogs = () =>
  Network.post<AwsFailoverLog[]>("/aws/failover/logs");

export const submitAwsManualFailover = (data: {
  nodeId?: number;
  accountId?: number;
  region?: string;
  instanceId?: string;
  requestId: string;
}) => Network.post<AwsOperation>("/aws/failover/manual", data);

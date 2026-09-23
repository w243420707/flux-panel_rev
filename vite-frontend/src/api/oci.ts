import Network from "./network";

export interface OciAccountSummary {
  id: number;
  name: string;
  userOcid: string;
  tenancyOcid: string;
  fingerprint: string;
  region: string;
  privateKeyConfigured: boolean;
}

export interface OciAccountPayload {
  id?: number;
  name: string;
  configText: string;
  privateKey: string;
}

export interface OciTestResult {
  ok: boolean;
  instanceCount: number;
}

export interface OciInstance {
  instanceOcid: string;
  displayName: string;
  publicIp?: string | null;
  lifecycleState: string;
}

export const listOciAccounts = () =>
  Network.post<OciAccountSummary[]>("/oci/account/list");

export const saveOciAccount = (data: OciAccountPayload) =>
  Network.post<void>("/oci/account/save", data);

export const deleteOciAccount = (id: number) =>
  Network.post<void>("/oci/account/delete", { id });

export const testOciAccount = (id: number) =>
  Network.post<OciTestResult>("/oci/account/test", { id });

export const listOciInstances = (accountId: number) =>
  Network.post<OciInstance[]>("/oci/account/instances", { accountId });

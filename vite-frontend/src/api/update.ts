import Network from './network';

export interface UpdateSettings {
  enabled: boolean;
  checkIntervalSeconds: number;
  githubTokenConfigured: boolean;
  githubTokenMasked: string | null;
  repository: string;
}

export interface UpdateSettingsPayload {
  enabled: boolean;
  checkIntervalSeconds: number;
  githubToken?: string;
  clearGithubToken?: boolean;
}

export interface UpdateStatus {
  currentVersion: string;
  latestVersion: string | null;
  updateAvailable: boolean;
  lastCheckAt: string | number | null;
  lastCheckStatus: string | null;
  lastCheckMessage: string | null;
  nextCheckAt: string | number | null;
  updaterReady: boolean;
  updateStatus: string;
  updateMessage: string | null;
}

export const getUpdateSettings = () => Network.get<UpdateSettings>('/update/settings');

export const saveUpdateSettings = (payload: UpdateSettingsPayload) =>
  Network.put<UpdateSettings>('/update/settings', payload);

export const checkForUpdate = () => Network.post<UpdateStatus>('/update/check');

export const getUpdateStatus = () => Network.get<UpdateStatus>('/update/status');

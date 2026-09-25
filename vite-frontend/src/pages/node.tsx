import { useState, useEffect, useRef } from "react";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Textarea } from "@heroui/input";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter } from "@heroui/modal";
import { Chip } from "@heroui/chip";
import { Spinner } from "@heroui/spinner";
import { Alert } from "@heroui/alert";
import { Progress } from "@heroui/progress";
import { Select, SelectItem } from "@heroui/select";
import { Switch } from "@heroui/switch";
import toast from 'react-hot-toast';
import axios from 'axios';
import {
  listOciAccounts,
  listOciInstances,
  type OciAccountSummary,
  type OciInstance,
} from "@/api/oci";


import { 
  createNode, 
  getNodeList, 
  updateNode, 
  deleteNode,
  rebootNode,
  updateNodeRebootSchedule,
  getNodeUsageHistory,
  confirmNodeUsage,
  getNodeInstallCommand,
  type NodeInstallSource,
  type NodeRebootSchedule,
  type VpsUsage,
  type VpsUsageRecord
} from "@/api";

interface Node {
  id: number;
  name: string;
  ip: string;
  serverIp: string;
  serverIpv4?: string;
  serverIpv6?: string;
  portSta: number;
  portEnd: number;
  version?: string;
  status: number; // 1: 在线, 0: 离线
  connectionStatus: 'online' | 'offline';
  systemInfo?: {
    cpuUsage: number;
    memoryUsage: number;
    memoryUsed?: number;
    memoryTotal?: number;
    swapUsage?: number;
    swapUsed?: number;
    swapTotal?: number;
    uploadTraffic: number;
    downloadTraffic: number;
    uploadSpeed: number;
    downloadSpeed: number;
    uptime: number;
  } | null;
  copyLoading?: boolean;
  wallMonitorEnabled?: number;
  wallMonitorStatus?: string;
  wallMonitorLastCheckAt?: number;
  wallMonitorConsecutiveFailures?: number;
  wallMonitorMessage?: string;
  wallMonitorExternalStatus?: string;
  wallMonitorExternalLastCheckAt?: number;
  wallMonitorExternalConsecutiveFailures?: number;
  wallMonitorExternalMessage?: string;
  remoteChangeIpUrl?: string;
  oracleNode?: boolean | number;
  ociAccountId?: number | null;
  ociInstanceOcid?: string | null;
  changeIpMinIntervalMinutes?: number | null;
  changeIpLastAttemptAt?: number | null;
  changeIpLastResult?: 'PENDING' | 'SUCCEEDED' | 'FAILED' | string | null;
  rebootIntervalHours?: number;
  rebootNextAt?: number | null;
  vpsUsage?: VpsUsage;
}

interface RebootState {
  phase: 'sending' | 'waiting' | 'unknown' | 'unconfirmed';
  startedAt: number;
  sawOffline: boolean;
}

const supportsNodeVersion = (version: string | undefined, minimumPatch: number): boolean => {
  const match = version?.match(/^v?(\d+)\.(\d+)\.(\d+)$/);
  if (!match) return false;
  const [, major, minor, patch] = match.map(Number);
  return major > 3 || (major === 3 && (minor > 1 || (minor === 1 && patch >= minimumPatch)));
};

const supportsReboot = (version?: string) => supportsNodeVersion(version, 6);

interface NodeForm {
  id: number | null;
  name: string;
  ipString: string;
  serverIp: string;
  oracleNode: boolean;
  ociAccountId: number | null;
  ociInstanceOcid: string;
  changeIpMinIntervalMinutes: string;
  portSta: number;
  portEnd: number;
}

export default function NodePage() {
  const [nodeList, setNodeList] = useState<Node[]>([]);
  const [expandedNodeIds, setExpandedNodeIds] = useState<Set<number>>(() => new Set());
  const [loading, setLoading] = useState(false);
  const [dialogVisible, setDialogVisible] = useState(false);
  const [dialogTitle, setDialogTitle] = useState('');
  const [isEdit, setIsEdit] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [deleteModalOpen, setDeleteModalOpen] = useState(false);
  const [deleteLoading, setDeleteLoading] = useState(false);
  const [nodeToDelete, setNodeToDelete] = useState<Node | null>(null);
  const [rebootStates, setRebootStates] = useState<Record<number, RebootState>>({});
  const rebootStatesRef = useRef<Record<number, RebootState>>({});
  const rebootRequestsRef = useRef(new Set<number>());
  const [scheduleNodeId, setScheduleNodeId] = useState<number | null>(null);
  const [scheduleHours, setScheduleHours] = useState('0');
  const [scheduleError, setScheduleError] = useState('');
  const [scheduleLoading, setScheduleLoading] = useState(false);
  const scheduleSavingRef = useRef(false);
  const [usageHistoryNodeId, setUsageHistoryNodeId] = useState<number | null>(null);
  const [usageHistoryRecords, setUsageHistoryRecords] = useState<VpsUsageRecord[]>([]);
  const [usageHistoryLoading, setUsageHistoryLoading] = useState(false);
  const [usageHistoryError, setUsageHistoryError] = useState('');
  const [usageConfirmLoading, setUsageConfirmLoading] = useState<'same' | 'replace' | null>(null);
  const usageHistoryRef = useRef({ nodeId: null as number | null, request: 0, confirming: false });
  const [usageNow, setUsageNow] = useState(() => Date.now());
  const [form, setForm] = useState<NodeForm>({
    id: null,
    name: '',
    ipString: '',
    serverIp: '',
    oracleNode: false,
    ociAccountId: null,
    ociInstanceOcid: '',
    changeIpMinIntervalMinutes: '',
    portSta: 1000,
    portEnd: 65535
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [ociAccounts, setOciAccounts] = useState<OciAccountSummary[]>([]);
  const [ociAccountsLoading, setOciAccountsLoading] = useState(false);
  const [ociInstances, setOciInstances] = useState<OciInstance[]>([]);
  const [ociInstancesLoading, setOciInstancesLoading] = useState(false);
  
  // 安装命令相关状态
  const [installCommandModal, setInstallCommandModal] = useState(false);
  const [installCommand, setInstallCommand] = useState('');
  const [currentNodeName, setCurrentNodeName] = useState('');
  const [installCommandSource, setInstallCommandSource] = useState<NodeInstallSource>('github');
  
  const websocketRef = useRef<WebSocket | null>(null);
  const reconnectTimerRef = useRef<NodeJS.Timeout | null>(null);
  const reconnectAttemptsRef = useRef(0);
  const runtimeIpCacheRef = useRef<Map<number, Partial<Node>>>(new Map());
  const systemInfoCacheRef = useRef<Map<number, NonNullable<Node['systemInfo']>>>(new Map());
  const statusUpdatedAtRef = useRef<Map<number, { at: number; status: number }>>(new Map());
  const scheduleUpdatedAtRef = useRef<Map<number, { at: number; fields: NodeRebootSchedule }>>(new Map());
  const changeIpUpdatedAtRef = useRef<Map<number, { at: number; fields: Pick<Node, 'changeIpLastAttemptAt' | 'changeIpLastResult'> }>>(new Map());
  const usageUpdatedAtRef = useRef<Map<number, { at: number; value: VpsUsage }>>(new Map());
  const nodeListRef = useRef<Node[]>([]);
  const quietRefreshTimerRef = useRef<number | null>(null);
  const loadNodesPendingRef = useRef(0);
  const ociInstancesRequestRef = useRef(0);
  const maxReconnectAttempts = 5;

  const toggleNodeDetails = (nodeId: number) => {
    setExpandedNodeIds(previous => {
      const next = new Set(previous);
      if (next.has(nodeId)) next.delete(nodeId);
      else next.add(nodeId);
      return next;
    });
  };

  const expandAllNodeDetails = () => {
    setExpandedNodeIds(new Set(nodeListRef.current.map(node => node.id)));
  };

  const collapseAllNodeDetails = () => {
    setExpandedNodeIds(new Set());
  };

  const [totalSpeed, setTotalSpeed] = useState({ upload: 0, download: 0 });
  nodeListRef.current = nodeList;

  useEffect(() => {
    initWebSocket();
    loadNodes();
    
    return () => {
      if (quietRefreshTimerRef.current !== null) {
        window.clearTimeout(quietRefreshTimerRef.current);
      }
      closeWebSocket();
    };
  }, []);

  useEffect(() => {
    const timer = window.setInterval(() => setUsageNow(Date.now()), 60000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!dialogVisible || !form.oracleNode) return;
    let active = true;
    setOciAccountsLoading(true);
    listOciAccounts()
      .then((res: any) => {
        if (!active) return;
        if (res.code === 0) {
          setOciAccounts(Array.isArray(res.data) ? res.data : []);
        } else {
          setOciAccounts([]);
          toast.error(res.msg || '加载 OCI 账号失败');
        }
      })
      .catch(() => {
        if (!active) return;
        setOciAccounts([]);
        toast.error('加载 OCI 账号失败');
      })
      .finally(() => {
        if (active) setOciAccountsLoading(false);
      });
    return () => { active = false; };
  }, [dialogVisible, form.oracleNode]);

  useEffect(() => {
    const requestId = ++ociInstancesRequestRef.current;
    if (!dialogVisible || !form.ociAccountId) {
      setOciInstances([]);
      setOciInstancesLoading(false);
      return;
    }
    setOciInstances([]);
    setOciInstancesLoading(true);
    listOciInstances(form.ociAccountId)
      .then((res: any) => {
        if (requestId !== ociInstancesRequestRef.current) return;
        if (res.code === 0) {
          setOciInstances(Array.isArray(res.data) ? res.data : []);
        } else {
          setOciInstances([]);
          toast.error(res.msg || '加载 OCI 实例失败');
        }
      })
      .catch(() => {
        if (requestId !== ociInstancesRequestRef.current) return;
        setOciInstances([]);
        toast.error('加载 OCI 实例失败');
      })
      .finally(() => {
        if (requestId === ociInstancesRequestRef.current) setOciInstancesLoading(false);
      });
  }, [dialogVisible, form.ociAccountId]);

  useEffect(() => {
    const refreshTimer = window.setInterval(() => {
      let changed = false;
      const next = { ...rebootStatesRef.current };
      Object.entries(next).forEach(([id, state]) => {
        if (state.phase !== 'unconfirmed' && Date.now() - state.startedAt >= 120000) {
          next[Number(id)] = { ...state, phase: 'unconfirmed' };
          changed = true;
        }
      });
      if (changed) {
        rebootStatesRef.current = next;
        setRebootStates(next);
      }
      if (changed || Object.values(next).some(state => state.phase !== 'unconfirmed') ||
          nodeListRef.current.some(node => (node.rebootIntervalHours || 0) > 0)) {
        void loadNodes(true);
      }
    }, 30000);
    return () => window.clearInterval(refreshTimer);
  }, []);

  useEffect(() => {
    const refreshTotalSpeed = () => {
      const nextTotalSpeed = nodeListRef.current.reduce(
        (totals, node) => {
          if (node.connectionStatus === 'online' && node.systemInfo) {
            totals.upload += node.systemInfo.uploadSpeed || 0;
            totals.download += node.systemInfo.downloadSpeed || 0;
          }
          return totals;
        },
        { upload: 0, download: 0 }
      );

      setTotalSpeed(nextTotalSpeed);
    };

    refreshTotalSpeed();
    const refreshTimer = window.setInterval(refreshTotalSpeed, 2000);
    return () => window.clearInterval(refreshTimer);
  }, []);

  // 加载节点列表
  const loadNodes = async (quiet = false) => {
    if (quiet && loadNodesPendingRef.current > 0) return;
    loadNodesPendingRef.current++;
    const requestedAt = Date.now();
    if (!quiet) setLoading(true);
    try {
      const res = await getNodeList();
      if (res.code === 0) {
        const currentNodes = new Map(nodeListRef.current.map(node => [node.id, node]));
        const nextNodes = res.data.map((node: Node) => {
          const current = currentNodes.get(node.id);
          const latestStatus = statusUpdatedAtRef.current.get(node.id);
          const latestSchedule = scheduleUpdatedAtRef.current.get(node.id);
          const latestChangeIp = changeIpUpdatedAtRef.current.get(node.id);
          const latestUsage = usageUpdatedAtRef.current.get(node.id);
          const status = latestStatus && latestStatus.at >= requestedAt ? latestStatus.status : node.status;
          return {
            ...node,
            ...(latestSchedule && latestSchedule.at >= requestedAt ? latestSchedule.fields : {}),
            ...(latestChangeIp && latestChangeIp.at >= requestedAt ? latestChangeIp.fields : {}),
            vpsUsage: latestUsage && latestUsage.at >= requestedAt ? latestUsage.value : node.vpsUsage,
            status,
            connectionStatus: status === 1 ? 'online' : 'offline',
            systemInfo: systemInfoCacheRef.current.get(node.id) || null,
            copyLoading: current?.copyLoading || false,
          };
        });
        const nodeIds = new Set(nextNodes.map((node: Node) => node.id));
        runtimeIpCacheRef.current.forEach((_, nodeId) => {
          if (!nodeIds.has(nodeId)) {
            runtimeIpCacheRef.current.delete(nodeId);
          }
        });
        systemInfoCacheRef.current.forEach((_, nodeId) => {
          if (!nodeIds.has(nodeId)) {
            systemInfoCacheRef.current.delete(nodeId);
          }
        });
        setNodeList(nextNodes.map((node: Node) => {
          const cachedRuntime = runtimeIpCacheRef.current.get(node.id);
          return cachedRuntime ? { ...node, ...cachedRuntime } : node;
        }));
        const historyNodeId = usageHistoryRef.current.nodeId;
        if (historyNodeId !== null) {
          const previousUsageId = currentNodes.get(historyNodeId)?.vpsUsage?.current?.id;
          const nextUsageId = nextNodes.find((node: Node) => node.id === historyNodeId)?.vpsUsage?.current?.id;
          if (nextUsageId !== previousUsageId) void loadUsageHistory(historyNodeId, true);
        }
        nextNodes.forEach((node: Node) => observeRebootStatus(node.id, node.connectionStatus, requestedAt));
      } else {
        if (!quiet) toast.error(res.msg || '加载节点列表失败');
      }
    } catch (error) {
      if (!quiet) toast.error('网络错误，请重试');
    } finally {
      loadNodesPendingRef.current--;
      if (!quiet) setLoading(false);
    }
  };

  const refreshNodesQuietly = () => {
    if (quietRefreshTimerRef.current !== null) return;
    quietRefreshTimerRef.current = window.setTimeout(() => {
      quietRefreshTimerRef.current = null;
      void loadNodes(true);
    }, 500);
  };

  const setNodeRebootState = (id: number, state?: RebootState) => {
    const next = { ...rebootStatesRef.current };
    if (state) next[id] = state;
    else delete next[id];
    rebootStatesRef.current = next;
    setRebootStates(next);
  };

  const applyRebootSchedule = (id: number, data: NodeRebootSchedule, requestedAt = Date.now()) => {
    const cached = scheduleUpdatedAtRef.current.get(id);
    if (cached && cached.at > requestedAt) return;
    const fields = { rebootIntervalHours: data.rebootIntervalHours, rebootNextAt: data.rebootNextAt };
    scheduleUpdatedAtRef.current.set(id, { at: Date.now(), fields });
    setNodeList(prev => prev.map(node => node.id === id ? { ...node, ...fields } : node));
  };

  const applyChangeIpStatus = (id: number, data: Pick<Node, 'changeIpLastAttemptAt' | 'changeIpLastResult'>, requestedAt = Date.now()) => {
    const cached = changeIpUpdatedAtRef.current.get(id);
    if (cached && cached.at > requestedAt) return;
    const fields = {
      changeIpLastAttemptAt: data.changeIpLastAttemptAt,
      changeIpLastResult: data.changeIpLastResult,
    };
    changeIpUpdatedAtRef.current.set(id, { at: Date.now(), fields });
    setNodeList(prev => prev.map(node => node.id === id ? { ...node, ...fields } : node));
  };

  const applyVpsUsage = (id: number, data: VpsUsage, requestedAt = Date.now()) => {
    const cached = usageUpdatedAtRef.current.get(id);
    if (cached && cached.at > requestedAt) return;
    const value = { status: data.status, current: data.current, pending: data.pending };
    usageUpdatedAtRef.current.set(id, { at: Date.now(), value });
    setNodeList(prev => prev.map(node => node.id === id ? { ...node, vpsUsage: value } : node));
  };

  const observeRebootStatus = (id: number, status: Node['connectionStatus'], observedAt = Date.now()) => {
    const state = rebootStatesRef.current[id];
    if (!state || observedAt < state.startedAt) return;
    if (status === 'offline' && !state.sawOffline) {
      setNodeRebootState(id, { ...state, sawOffline: true });
    } else if (status === 'online' && state.sawOffline) {
      setNodeRebootState(id);
      const name = nodeListRef.current.find(node => node.id === id)?.name || '节点';
      toast.success(`${name} 已重新上线`);
    }
  };

  // 初始化WebSocket连接
  const initWebSocket = () => {
    if (websocketRef.current && 
        (websocketRef.current.readyState === WebSocket.OPEN || 
         websocketRef.current.readyState === WebSocket.CONNECTING)) {
      return;
    }
    
    if (websocketRef.current) {
      closeWebSocket();
    }
    
    // 构建WebSocket URL，使用axios的baseURL
    const baseUrl = axios.defaults.baseURL || (import.meta.env.VITE_API_BASE ? `${import.meta.env.VITE_API_BASE}/api/v1/` : '/api/v1/');
    const wsUrl = baseUrl.replace(/^http/, 'ws').replace(/\/api\/v1\/$/, '') + `/system-info?type=0&secret=${localStorage.getItem('token')}`;
    
    try {
      websocketRef.current = new WebSocket(wsUrl);
      
      websocketRef.current.onopen = () => {
        reconnectAttemptsRef.current = 0;
        refreshNodesQuietly();
      };
      
      websocketRef.current.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          handleWebSocketMessage(data);
        } catch (error) {
          // 解析失败时不输出错误信息
        }
      };
      
      websocketRef.current.onerror = () => {
        // WebSocket错误时不输出错误信息
      };
      
      websocketRef.current.onclose = () => {
        websocketRef.current = null;
        attemptReconnect();
      };
    } catch (error) {
      attemptReconnect();
    }
  };

  // 处理WebSocket消息
  const handleWebSocketMessage = (data: any) => {
    const { id, type, data: messageData } = data;
    
    if (type === 'status') {
      const nextConnectionStatus = messageData === 1 ? 'online' : 'offline';
      statusUpdatedAtRef.current.set(Number(id), { at: Date.now(), status: messageData === 1 ? 1 : 0 });
      if (nextConnectionStatus === 'offline') {
        systemInfoCacheRef.current.delete(Number(id));
      }
      observeRebootStatus(Number(id), nextConnectionStatus);
      setNodeList(prev => prev.map(node => {
        if (node.id == id) {
          return {
            ...node,
            status: messageData === 1 ? 1 : 0,
            connectionStatus: nextConnectionStatus,
            systemInfo: node.systemInfo
          };
        }
        return node;
      }));
      refreshNodesQuietly();
    } else if (type === 'reboot' || type === 'rebootSchedule') {
      const nodeId = Number(id);
      applyRebootSchedule(nodeId, messageData);
      if (type === 'reboot') {
        const current = rebootStatesRef.current[nodeId];
        if (messageData.status === 'failed') {
          setNodeRebootState(nodeId);
          toast.error(messageData.message || '节点未能执行重启');
        } else if (messageData.status === 'accepted' || messageData.status === 'unknown') {
          setNodeRebootState(nodeId, {
            startedAt: current?.startedAt || Date.now(),
            sawOffline: current?.sawOffline || false,
            phase: messageData.status === 'accepted' ? 'waiting' : 'unknown',
          });
        }
      }
    } else if (type === 'usage') {
      const nodeId = Number(id);
      const previous = usageUpdatedAtRef.current.get(nodeId)?.value || nodeListRef.current.find(node => node.id === nodeId)?.vpsUsage;
      applyVpsUsage(nodeId, messageData);
      if (usageHistoryRef.current.nodeId === nodeId && previous?.current?.id !== messageData.current?.id) {
        void loadUsageHistory(nodeId, true);
      }
    } else if (type === 'changeIp') {
      applyChangeIpStatus(Number(id), messageData);
    } else if (type === 'wallMonitor') {
      const monitorUpdates = { ...messageData };
      delete monitorUpdates.remoteChangeIpUrl;
      setNodeList(prev => prev.map(node => {
        if (node.id == id) {
          return {
            ...node,
            ...monitorUpdates,
          };
        }
        return node;
      }));
    } else if (type === 'info') {
      try {
        const systemInfo = typeof messageData === 'string'
          ? JSON.parse(messageData)
          : messageData;
        const nodeId = Number(id);
        const getRuntimeIp = (...keys: string[]) => {
          for (const key of keys) {
            const value = systemInfo?.[key];
            if (typeof value === "string" && value.trim()) {
              return value.trim();
            }
          }
          return "";
        };
        const publicIp = getRuntimeIp("public_ip", "publicIp", "host_ip");
        const publicIpv4 = getRuntimeIp("public_ipv4", "publicIpv4");
        const publicIpv6 = getRuntimeIp("public_ipv6", "publicIpv6");
        const hasRuntimeIp = Boolean(publicIp || publicIpv4 || publicIpv6);
        const nextServerIp = publicIp || publicIpv4 || publicIpv6;
        const isIpv4Literal = (value: string) => /^\d{1,3}(?:\.\d{1,3}){3}$/.test(value.trim());
        const isIpv6Literal = (value: string) => value.includes(':');
        const getMetric = (...keys: string[]): number | undefined => {
          for (const key of keys) {
            const value = systemInfo?.[key];
            if (value === null || value === undefined || value === '') {
              continue;
            }
            const numberValue = typeof value === 'number' ? value : Number(value);
            if (Number.isFinite(numberValue)) {
              return numberValue;
            }
          }
          return undefined;
        };

        const baseRuntimeIpPatch = hasRuntimeIp ? {
          serverIp: nextServerIp,
          serverIpv4: publicIpv4 || (isIpv4Literal(nextServerIp) ? nextServerIp : ''),
          serverIpv6: publicIpv6 || (isIpv6Literal(nextServerIp) ? nextServerIp : ''),
        } : {};
        if (hasRuntimeIp) {
          runtimeIpCacheRef.current.set(nodeId, {
            ...baseRuntimeIpPatch,
          });
        }

        const memoryUsage = getMetric("memory_usage", "memoryUsage");
        const memoryUsed = getMetric("memory_used", "memoryUsed");
        const memoryTotal = getMetric("memory_total", "memoryTotal");
        const swapUsage = getMetric("swap_usage", "swapUsage");
        const swapUsed = getMetric("swap_used", "swapUsed");
        const swapTotal = getMetric("swap_total", "swapTotal");
        const hasSystemMetrics =
          systemInfo &&
          [
            memoryUsage,
            memoryUsed,
            memoryTotal,
            swapUsage,
            swapUsed,
            swapTotal,
            getMetric("cpu_usage", "cpuUsage"),
            getMetric("bytes_received", "bytesReceived"),
            getMetric("bytes_transmitted", "bytesTransmitted"),
            getMetric("uptime")
          ].some(value => value !== undefined);
        let nextSystemInfo: NonNullable<Node['systemInfo']> | null = null;
        if (hasSystemMetrics) {
          const currentUpload = getMetric("bytes_transmitted", "bytesTransmitted") || 0;
          const currentDownload = getMetric("bytes_received", "bytesReceived") || 0;
          const currentUptime = getMetric("uptime") || 0;
          const previousSystemInfo = systemInfoCacheRef.current.get(nodeId);
          // 页面刚连接时后端可能只回放一份快照，优先使用后端缓存的最近速度，避免先显示 0。
          let uploadSpeed = getMetric("upload_speed", "uploadSpeed") || 0;
          let downloadSpeed = getMetric("download_speed", "downloadSpeed") || 0;

          if (previousSystemInfo && previousSystemInfo.uptime) {
            const timeDiff = currentUptime - previousSystemInfo.uptime;
            if (timeDiff > 0 && timeDiff <= 10) {
              const uploadDiff = currentUpload - previousSystemInfo.uploadTraffic;
              const downloadDiff = currentDownload - previousSystemInfo.downloadTraffic;
              if (uploadDiff >= 0) {
                uploadSpeed = uploadDiff / timeDiff;
              }
              if (downloadDiff >= 0) {
                downloadSpeed = downloadDiff / timeDiff;
              }
            }
          }

          nextSystemInfo = {
            cpuUsage: getMetric("cpu_usage", "cpuUsage") || 0,
            memoryUsage: memoryUsage || 0,
            memoryUsed,
            memoryTotal,
            swapUsage,
            swapUsed,
            swapTotal,
            uploadTraffic: currentUpload,
            downloadTraffic: currentDownload,
            uploadSpeed,
            downloadSpeed,
            uptime: currentUptime
          };
          systemInfoCacheRef.current.set(nodeId, nextSystemInfo);
        }

        setNodeList(prev => prev.map(node => {
          if (node.id != id) {
            return node;
          }
          const currentRuntimeIps = [node.serverIp, node.serverIpv4, node.serverIpv6].filter(Boolean);
          const entryFollowsRuntimeIp = !node.ip || currentRuntimeIps.includes(node.ip);
          const runtimeIpPatch = hasRuntimeIp ? {
            ip: entryFollowsRuntimeIp ? nextServerIp : node.ip,
            ...baseRuntimeIpPatch,
          } : {};
          if (hasRuntimeIp) {
            runtimeIpCacheRef.current.set(nodeId, {
              ...runtimeIpPatch,
            });
          }
          return {
            ...node,
            ...runtimeIpPatch,
            systemInfo: nextSystemInfo || node.systemInfo
          };
        }));
      } catch (error) {
        // 忽略非系统信息或格式异常的 WebSocket 消息。
      }
    }
  };

  // 尝试重新连接
  const attemptReconnect = () => {
    if (reconnectAttemptsRef.current < maxReconnectAttempts) {
      reconnectAttemptsRef.current++;
      
      reconnectTimerRef.current = setTimeout(() => {
        initWebSocket();
      }, 3000 * reconnectAttemptsRef.current);
    }
  };

  // 关闭WebSocket连接
  const closeWebSocket = () => {
    if (reconnectTimerRef.current) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
    
    reconnectAttemptsRef.current = 0;
    
    if (websocketRef.current) {
      websocketRef.current.onopen = null;
      websocketRef.current.onmessage = null;
      websocketRef.current.onerror = null;
      websocketRef.current.onclose = null;
      
      if (websocketRef.current.readyState === WebSocket.OPEN || 
          websocketRef.current.readyState === WebSocket.CONNECTING) {
        websocketRef.current.close();
      }
      
      websocketRef.current = null;
    }
    
    setNodeList(prev => prev.map(node => ({
      ...node,
      connectionStatus: 'offline'
    })));
  };


  
  // 格式化速度
  const formatSpeed = (bytesPerSecond: number): string => {
    if (bytesPerSecond === 0) return '0 B/s';
    
    const k = 1024;
    const sizes = ['B/s', 'KB/s', 'MB/s', 'GB/s', 'TB/s'];
    const i = Math.floor(Math.log(bytesPerSecond) / Math.log(k));
    
    return parseFloat((bytesPerSecond / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  // 格式化开机时间
  const formatUptime = (seconds: number): string => {
    if (seconds === 0) return '-';
    
    const days = Math.floor(seconds / 86400);
    const hours = Math.floor((seconds % 86400) / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    
    if (days > 0) {
      return `${days}天${hours}小时`;
    } else if (hours > 0) {
      return `${hours}小时${minutes}分钟`;
    } else {
      return `${minutes}分钟`;
    }
  };

  // 格式化流量
  const formatTraffic = (bytes: number): string => {
    if (bytes === 0) return '0 B';
    
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  const formatResourceSize = (bytes?: number): string => {
    if (!bytes || bytes <= 0) return '0B';

    const units = ['B', 'K', 'M', 'G', 'T'];
    const exponent = Math.min(
      Math.floor(Math.log(bytes) / Math.log(1024)),
      units.length - 1
    );
    const value = bytes / Math.pow(1024, exponent);
    const precision = exponent >= 2 ? (value >= 10 ? 0 : 1) : 0;
    return `${value.toFixed(precision)}${units[exponent]}`;
  };

  const formatMemoryMetric = (systemInfo: NonNullable<Node['systemInfo']>): string => {
    if (systemInfo.memoryTotal && systemInfo.memoryUsed !== undefined) {
      return `${formatResourceSize(systemInfo.memoryUsed)} / ${formatResourceSize(systemInfo.memoryTotal)}`;
    }
    return Number.isFinite(systemInfo.memoryUsage)
      ? `${systemInfo.memoryUsage.toFixed(1)}%`
      : '-';
  };

  const formatSwapMetric = (systemInfo: NonNullable<Node['systemInfo']>): string => {
    if (systemInfo.swapUsage === undefined) {
      return '-';
    }
    if (systemInfo.swapTotal !== undefined) {
      if (!systemInfo.swapTotal) {
        return '未配置';
      }
      return `${formatResourceSize(systemInfo.swapUsed)} / ${formatResourceSize(systemInfo.swapTotal)}`;
    }
    return `${systemInfo.swapUsage.toFixed(1)}%`;
  };

  const clampMetric = (value: number): number => Math.max(0, Math.min(100, Number.isFinite(value) ? value : 0));

  const getMetricCircleClass = (value: number, offline = false): string => {
    const color = getProgressColor(clampMetric(value), offline);
    return {
      default: 'text-default-400',
      primary: 'text-primary-500',
      secondary: 'text-secondary-500',
      success: 'text-success-500',
      warning: 'text-warning-500',
      danger: 'text-danger-500',
    }[color];
  };

  const MetricRing = ({ label, value, offline = false, unavailable = false }: {
    label: string;
    value: number;
    offline?: boolean;
    unavailable?: boolean;
  }) => {
    const percentage = unavailable || offline ? 0 : clampMetric(value);
    const radius = 15;
    const circumference = 2 * Math.PI * radius;
    return (
      <div className="relative h-9 w-9" title={`${label}: ${unavailable ? '未配置' : offline ? '离线' : `${percentage.toFixed(1)}%`}`} aria-label={label}>
        <svg viewBox="0 0 36 36" className="h-full w-full -rotate-90" aria-hidden="true">
          <circle cx="18" cy="18" r={radius} fill="none" stroke="currentColor" strokeWidth="3" className="text-default-200 dark:text-default-700" />
          <circle
            cx="18"
            cy="18"
            r={radius}
            fill="none"
            stroke="currentColor"
            strokeWidth="3"
            strokeLinecap="round"
            className={getMetricCircleClass(percentage, offline || unavailable)}
            strokeDasharray={circumference}
            strokeDashoffset={circumference * (1 - percentage / 100)}
          />
        </svg>
        <span className="absolute inset-0 flex items-center justify-center text-[8px] font-semibold text-default-600 dark:text-default-300">
          {unavailable || offline ? '-' : `${percentage.toFixed(0)}%`}
        </span>
      </div>
    );
  };

  // 获取进度条颜色
  const getProgressColor = (value: number, offline = false): "default" | "primary" | "secondary" | "success" | "warning" | "danger" => {
    if (offline) return "default";
    if (value <= 50) return "success";
    if (value <= 80) return "warning";
    return "danger";
  };

  const getWallMonitorColor = (node: Node): "default" | "primary" | "secondary" | "success" | "warning" | "danger" => {
    if (node.wallMonitorEnabled === 0) return "default";
    switch (getEffectiveWallMonitorStatus(node)) {
      case "OK":
        return "success";
      case "SUSPECTED_BLOCKED":
        return "danger";
      case "NODE_OFFLINE":
        return "default";
      default:
        return "secondary";
    }
  };

  const getWallMonitorLabel = (node: Node): string => {
    if (node.wallMonitorEnabled === 0) return "未开启";
    switch (getEffectiveWallMonitorStatus(node)) {
      case "OK":
        return "正常";
      case "SUSPECTED_BLOCKED":
        return "疑似被墙";
      case "NODE_OFFLINE":
        return "节点离线";
      default:
        return "等待 APK 回报";
    }
  };

  const getEffectiveWallMonitorStatus = (node: Node): string => {
    return node.wallMonitorExternalStatus || "UNKNOWN";
  };

  const formatMonitorTime = (timestamp?: number): string => {
    if (!timestamp) return "-";
    return new Date(timestamp).toLocaleString();
  };

  const getChangeIpAttemptStatus = (node: Node): {
    label: string;
    color: "default" | "primary" | "secondary" | "success" | "warning" | "danger";
  } => {
    if (!(node.oracleNode === true || node.oracleNode === 1)) return { label: "未启用", color: "default" };
    if (!node.ociAccountId || !node.ociInstanceOcid) return { label: "未绑定实例", color: "default" };
    if (node.changeIpLastResult === "SUCCEEDED") return { label: "已更换", color: "success" };
    if (node.changeIpLastResult === "FAILED") return { label: "失败", color: "danger" };
    if (node.changeIpLastResult === "PENDING") return { label: "处理中", color: "warning" };
    return { label: "待触发", color: "secondary" };
  };

  const getRebootNextLabel = (node?: Node): string => {
    if (!node || !node.rebootIntervalHours) return '已关闭';
    if (node.rebootNextAt) return formatMonitorTime(node.rebootNextAt);
    return node.connectionStatus === 'online' ? '等待重新上线或重新保存' : '等待节点上线后计时';
  };

  const formatUsageDuration = (record: VpsUsageRecord): string => {
    const seconds = Math.max(0, Math.floor(((record.replacedAt ?? usageNow) - record.startedAt) / 1000));
    return seconds < 60 ? '不足1分钟' : formatUptime(seconds);
  };

  // 验证IP地址格式
  const validateIp = (ip: string): boolean => {
    if (!ip || !ip.trim()) return false;
    
    const trimmedIp = ip.trim();
    
    // IPv4格式验证
    const ipv4Regex = /^(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$/;
    
    // IPv6格式验证
    const ipv6Regex = /^(([0-9a-fA-F]{1,4}:){7,7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]{1,}|::(ffff(:0{1,4}){0,1}:){0,1}((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9]))$/;
    
    if (ipv4Regex.test(trimmedIp) || ipv6Regex.test(trimmedIp) || trimmedIp === 'localhost') {
      return true;
    }
    
    // 验证域名格式
    if (/^\d+$/.test(trimmedIp)) return false;
    
    const domainRegex = /^[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?)+$/;
    const singleLabelDomain = /^[a-zA-Z][a-zA-Z0-9\-]{0,62}$/;
    
    return domainRegex.test(trimmedIp) || singleLabelDomain.test(trimmedIp);
  };

  // 表单验证
  const validateForm = (): boolean => {
    const newErrors: Record<string, string> = {};
    
    if (!form.name.trim()) {
      newErrors.name = '请输入节点名称';
    } else if (form.name.trim().length < 2) {
      newErrors.name = '节点名称长度至少2位';
    } else if (form.name.trim().length > 50) {
      newErrors.name = '节点名称长度不能超过50位';
    }
    
    if (form.ipString.trim()) {
      const ips = form.ipString.split('\n').map(ip => ip.trim()).filter(ip => ip);
      for (let i = 0; i < ips.length; i++) {
        if (!validateIp(ips[i])) {
          newErrors.ipString = `第${i + 1}行IP地址格式错误: ${ips[i]}`;
          break;
        }
      }
    }
    
    if (form.serverIp.trim() && !validateIp(form.serverIp.trim())) {
      newErrors.serverIp = '请输入有效的IPv4、IPv6地址或域名';
    }

    const interval = form.changeIpMinIntervalMinutes.trim()
      ? Number(form.changeIpMinIntervalMinutes)
      : 3;
    if (!Number.isInteger(interval) || interval < 3) {
      newErrors.changeIpMinIntervalMinutes = '最小间隔不能低于 3 分钟';
    }

    if (form.oracleNode) {
      if (!form.ociAccountId) newErrors.ociAccountId = '请选择 OCI 账号';
      if (!form.ociInstanceOcid.trim()) newErrors.ociInstanceOcid = '请选择 OCI 实例';
    }
    
    if (!form.portSta || form.portSta < 1 || form.portSta > 65535) {
      newErrors.portSta = '端口范围必须在1-65535之间';
    }
    
    if (!form.portEnd || form.portEnd < 1 || form.portEnd > 65535) {
      newErrors.portEnd = '端口范围必须在1-65535之间';
    } else if (form.portEnd < form.portSta) {
      newErrors.portEnd = '结束端口不能小于起始端口';
    }
    
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  // 新增节点
  const handleAdd = () => {
    setDialogTitle('新增节点');
    setIsEdit(false);
    setDialogVisible(true);
    resetForm();
  };

  // 编辑节点
  const handleEdit = (node: Node) => {
    setDialogTitle('编辑节点');
    setIsEdit(true);
    setForm({
      id: node.id,
      name: node.name,
      ipString: node.ip ? node.ip.split(',').map(ip => ip.trim()).join('\n') : '',
      serverIp: node.serverIp || '',
      oracleNode: node.oracleNode === true || node.oracleNode === 1,
      ociAccountId: node.ociAccountId ?? null,
      ociInstanceOcid: node.ociInstanceOcid || '',
      changeIpMinIntervalMinutes: node.changeIpMinIntervalMinutes?.toString() || '',
      portSta: node.portSta,
      portEnd: node.portEnd
    });
    setDialogVisible(true);
  };

  // 删除节点
  const handleDelete = (node: Node) => {
    setNodeToDelete(node);
    setDeleteModalOpen(true);
  };

  const isRebootPending = (id: number) => {
    const state = rebootStatesRef.current[id];
    return rebootRequestsRef.current.has(id) || Boolean(state && state.phase !== 'unconfirmed');
  };

  const handleReboot = async (node: Node) => {
    if (isRebootPending(node.id) || node.connectionStatus !== 'online' || !supportsReboot(node.version)) return;
    rebootRequestsRef.current.add(node.id);
    const requestedAt = Date.now();
    setNodeRebootState(node.id, { phase: 'sending', startedAt: requestedAt, sawOffline: false });
    try {
      const res = await rebootNode(node.id);
      const current = rebootStatesRef.current[node.id];
      if (res.code === 0) {
        if (res.data) applyRebootSchedule(node.id, res.data, requestedAt);
        if (current) setNodeRebootState(node.id, { ...current, phase: 'waiting' });
        if (current) toast.success(`${node.name} 已收到重启指令，正在等待节点重新上线`);
      } else if (res.code === -2 || (res.code === -1 && /timeout|network|网络|结果未知|request failed|aborted|econn/i.test(res.msg || ''))) {
        if (current) setNodeRebootState(node.id, { ...current, phase: 'unknown' });
        if (current) toast.error('未确认节点是否收到重启指令，请等待状态更新，不要重复点击');
      } else {
        setNodeRebootState(node.id);
        toast.error(res.msg || '发送重启指令失败');
      }
    } catch {
      const current = rebootStatesRef.current[node.id];
      if (current) setNodeRebootState(node.id, { ...current, phase: 'unknown' });
      if (current) toast.error('网络中断，重启结果未知，请等待节点状态更新');
    } finally {
      rebootRequestsRef.current.delete(node.id);
      setRebootStates({ ...rebootStatesRef.current });
      refreshNodesQuietly();
    }
  };

  const openRebootSchedule = (node: Node) => {
    setScheduleNodeId(node.id);
    setScheduleHours(String(node.rebootIntervalHours || 0));
    setScheduleError('');
  };

  const saveRebootSchedule = async () => {
    if (scheduleNodeId === null || scheduleSavingRef.current) return;
    const intervalHours = Number(scheduleHours);
    if (!/^\d+$/.test(scheduleHours) || !Number.isInteger(intervalHours) || intervalHours < 0 || intervalHours > 720) {
      setScheduleError('请输入 0–720 的整数小时数，0 表示关闭');
      return;
    }
    const node = nodeListRef.current.find(item => item.id === scheduleNodeId);
    if (!node) {
      setScheduleError('节点已不存在，请关闭后刷新列表');
      return;
    }
    if (intervalHours > 0 && !supportsReboot(node.version)) {
      setScheduleError('请先将节点升级至 3.1.6 或以上版本');
      return;
    }
    scheduleSavingRef.current = true;
    const requestedAt = Date.now();
    setScheduleLoading(true);
    setScheduleError('');
    try {
      const res = await updateNodeRebootSchedule(node.id, intervalHours);
      if (res.code === 0) {
        applyRebootSchedule(node.id, res.data, requestedAt);
        toast.success(intervalHours === 0 ? '定时重启已关闭' : '定时重启已保存');
        setScheduleNodeId(null);
      } else {
        setScheduleError(res.msg || '保存失败，请重试');
      }
    } catch {
      setScheduleError('网络错误，请检查连接后重试');
    } finally {
      scheduleSavingRef.current = false;
      setScheduleLoading(false);
      refreshNodesQuietly();
    }
  };

  const loadUsageHistory = async (id: number, quiet = false) => {
    const request = ++usageHistoryRef.current.request;
    const requestedAt = Date.now();
    if (!quiet) setUsageHistoryLoading(true);
    try {
      const res = await getNodeUsageHistory(id);
      if (usageHistoryRef.current.nodeId !== id || usageHistoryRef.current.request !== request) return;
      if (res.code === 0) {
        applyVpsUsage(id, res.data, requestedAt);
        setUsageHistoryRecords(res.data.records.slice(0, 3));
      } else {
        setUsageHistoryError(res.msg || '加载使用记录失败');
      }
    } catch {
      if (usageHistoryRef.current.nodeId === id && usageHistoryRef.current.request === request) {
        setUsageHistoryError('网络错误，暂时无法加载使用记录');
      }
    } finally {
      if (usageHistoryRef.current.nodeId === id && usageHistoryRef.current.request === request) {
        setUsageHistoryLoading(false);
      }
    }
  };

  const openUsageHistory = (node: Node) => {
    usageHistoryRef.current.nodeId = node.id;
    setUsageHistoryNodeId(node.id);
    setUsageHistoryRecords([]);
    setUsageHistoryError('');
    setUsageNow(Date.now());
    void loadUsageHistory(node.id);
  };

  const closeUsageHistory = () => {
    if (usageHistoryRef.current.confirming) return;
    usageHistoryRef.current.nodeId = null;
    setUsageHistoryNodeId(null);
  };

  const handleConfirmUsage = async (decision: 'same' | 'replace') => {
    const { nodeId, confirming } = usageHistoryRef.current;
    if (nodeId === null || confirming) return;
    const usage = nodeListRef.current.find(node => node.id === nodeId)?.vpsUsage;
    if (!usage?.pending || (decision === 'same' && (!usage.current || !usage.pending.canKeepCurrent))) return;
    usageHistoryRef.current.confirming = true;
    setUsageConfirmLoading(decision);
    setUsageHistoryError('');
    const requestedAt = Date.now();
    try {
      const res = await confirmNodeUsage({
        id: nodeId,
        expectedUsageId: usage.current?.id ?? null,
        candidateId: usage.pending.candidateId,
        decision,
      });
      if (res.code === 0) {
        applyVpsUsage(nodeId, res.data, requestedAt);
        toast.success(decision === 'same' ? '已继续累计当前 VPS 的使用记录' : '已开始新的 VPS 使用记录');
      } else {
        const networkError = res.code === -1 && /timeout|network|request failed|aborted|econn/i.test(res.msg || '');
        setUsageHistoryError(networkError ? '确认结果未知，正在刷新最新状态，请勿重复操作' : res.msg || '确认失败，已重新获取最新状态');
      }
    } catch {
      setUsageHistoryError('确认结果未知，正在刷新最新状态，请勿重复操作');
    } finally {
      await loadUsageHistory(nodeId, true);
      usageHistoryRef.current.confirming = false;
      setUsageConfirmLoading(null);
    }
  };

  const confirmDelete = async () => {
    if (!nodeToDelete) return;
    
    setDeleteLoading(true);
    try {
      const res = await deleteNode(nodeToDelete.id);
      if (res.code === 0) {
        toast.success('删除成功');
        systemInfoCacheRef.current.delete(nodeToDelete.id);
        runtimeIpCacheRef.current.delete(nodeToDelete.id);
        statusUpdatedAtRef.current.delete(nodeToDelete.id);
        scheduleUpdatedAtRef.current.delete(nodeToDelete.id);
        changeIpUpdatedAtRef.current.delete(nodeToDelete.id);
        usageUpdatedAtRef.current.delete(nodeToDelete.id);
        setExpandedNodeIds(previous => {
          const next = new Set(previous);
          next.delete(nodeToDelete.id);
          return next;
        });
        setNodeRebootState(nodeToDelete.id);
        setNodeList(prev => prev.filter(n => n.id !== nodeToDelete.id));
        setDeleteModalOpen(false);
        setNodeToDelete(null);
      } else {
        toast.error(res.msg || '删除失败');
      }
    } catch (error) {
      toast.error('网络错误，请重试');
    } finally {
      setDeleteLoading(false);
    }
  };

  // 复制安装命令
  const handleCopyInstallCommand = async (node: Node, assetMode: NodeInstallSource) => {
    setNodeList(prev => prev.map(n => 
      n.id === node.id ? { ...n, copyLoading: true } : n
    ));
    
    try {
      const res = await getNodeInstallCommand(node.id, window.location.origin, assetMode);
      if (res.code === 0 && res.data) {
        try {
          await navigator.clipboard.writeText(res.data);
          toast.success(`${assetMode === 'local' ? '本地' : 'GitHub'}安装命令已复制`);
        } catch (copyError) {
          // 复制失败，显示安装命令模态框
          setInstallCommand(res.data);
          setCurrentNodeName(node.name);
          setInstallCommandSource(assetMode);
          setInstallCommandModal(true);
        }
      } else {
        toast.error(res.msg || '获取安装命令失败');
      }
    } catch (error) {
      toast.error('获取安装命令失败');
    } finally {
      setNodeList(prev => prev.map(n => 
        n.id === node.id ? { ...n, copyLoading: false } : n
      ));
    }
  };

  // 手动复制安装命令
  const handleManualCopy = async () => {
    try {
      await navigator.clipboard.writeText(installCommand);
      toast.success('安装命令已复制到剪贴板');
      setInstallCommandModal(false);
    } catch (error) {
      toast.error('复制失败，请手动选择文本复制。原因：请使用https访问面板（例如nginx反代），http无法复制。');
    }
  };

  // 提交表单
  const handleSubmit = async () => {
    if (!validateForm()) return;
    
    setSubmitLoading(true);
    
    try {
      const ipString = form.ipString
        .split('\n')
        .map(ip => ip.trim())
        .filter(ip => ip)
        .join(',');
        
      const changeIpMinIntervalMinutes = form.changeIpMinIntervalMinutes.trim()
        ? Number(form.changeIpMinIntervalMinutes)
        : null;
      const submitData = {
        ...form,
        ip: ipString,
        changeIpMinIntervalMinutes,
        oracleNode: form.oracleNode ? 1 : 0,
        ociAccountId: form.oracleNode ? form.ociAccountId : null,
        ociInstanceOcid: form.oracleNode ? form.ociInstanceOcid : null,
      };
      delete (submitData as any).ipString;
      
      const apiCall = isEdit ? updateNode : createNode;
      const data = isEdit ? submitData : { 
        name: form.name, 
        ip: ipString,
        serverIp: form.serverIp,
        changeIpMinIntervalMinutes,
        oracleNode: form.oracleNode ? 1 : 0,
        ociAccountId: form.oracleNode ? form.ociAccountId : null,
        ociInstanceOcid: form.oracleNode ? form.ociInstanceOcid : null,
        portSta: form.portSta,
        portEnd: form.portEnd
      };
      
      const res = await apiCall(data);
      if (res.code === 0) {
        toast.success(isEdit ? '更新成功' : '创建成功');
        setDialogVisible(false);
        
        if (isEdit) {
          setNodeList(prev => prev.map(n => 
            n.id === form.id ? {
              ...n,
              name: form.name,
              ip: ipString,
              serverIp: form.serverIp,
              oracleNode: form.oracleNode ? 1 : 0,
              ociAccountId: form.oracleNode ? form.ociAccountId : null,
              ociInstanceOcid: form.oracleNode ? form.ociInstanceOcid : null,
              changeIpMinIntervalMinutes: form.changeIpMinIntervalMinutes.trim()
                ? Number(form.changeIpMinIntervalMinutes)
                : null,
              portSta: form.portSta,
              portEnd: form.portEnd
            } : n
          ));
        } else {
          loadNodes();
        }
      } else {
        toast.error(res.msg || (isEdit ? '更新失败' : '创建失败'));
      }
    } catch (error) {
      toast.error('网络错误，请重试');
    } finally {
      setSubmitLoading(false);
    }
  };

  // 重置表单
  const resetForm = () => {
    setForm({
      id: null,
      name: '',
      ipString: '',
      serverIp: '',
      oracleNode: false,
      ociAccountId: null,
      ociInstanceOcid: '',
      changeIpMinIntervalMinutes: '',
      portSta: 1000,
      portEnd: 65535
    });
    setErrors({});
  };

  const getNodeEntryAddresses = (node: Node): string[] => {
    return (node.ip || node.serverIp || node.serverIpv4 || node.serverIpv6 || '')
      .split(',')
      .map(ip => ip.trim())
      .filter(ip => ip);
  };

  const getNodePrimaryAddress = (node: Node): string => {
    return node.serverIp || node.serverIpv4 || node.serverIpv6 || node.ip || '待自动识别';
  };

  const handleCopyRemoteChangeIpUrl = async (node: Node) => {
    if (!node.remoteChangeIpUrl) {
      toast.error('当前节点没有可用的 APK 回调地址');
      return;
    }
    try {
      await navigator.clipboard.writeText(node.remoteChangeIpUrl);
      toast.success('独立 APK 回调地址已复制');
    } catch {
      toast.error('复制失败，请使用 HTTPS 访问面板后重试');
    }
  };

  const scheduleNode = nodeList.find(node => node.id === scheduleNodeId);
  const usageHistoryNode = nodeList.find(node => node.id === usageHistoryNodeId);
  const currentUsage = usageHistoryNode?.vpsUsage?.current;
  const visibleUsageRecords = [
    ...(currentUsage ? [currentUsage] : []),
    ...usageHistoryRecords.filter(record => record.id !== currentUsage?.id && record.replacedAt !== null),
  ].slice(0, 3);
  const pendingUsage = usageHistoryNode?.vpsUsage?.pending;

  return (
    
      <div className="px-3 lg:px-6 py-8">
        {/* 页面头部 */}
        <div className="flex items-center justify-between gap-3 mb-6">
        <div className="min-w-0 flex flex-1 flex-wrap items-center justify-end gap-2">
            <Chip
              variant="flat"
              color="primary"
              size="sm"
              className="text-xs whitespace-nowrap"
            >
              总上传 {formatSpeed(totalSpeed.upload)}
            </Chip>
            <Chip
              variant="flat"
              color="success"
              size="sm"
              className="text-xs whitespace-nowrap"
            >
              总下载 {formatSpeed(totalSpeed.download)}
            </Chip>
        </div>

        <Button
              size="sm"
              variant="flat"
              color="default"
              className="shrink-0"
              onPress={expandAllNodeDetails}
              isDisabled={nodeList.length === 0}
            >
              全部展开
            </Button>
        <Button
              size="sm"
              variant="flat"
              color="default"
              className="shrink-0"
              onPress={collapseAllNodeDetails}
              isDisabled={expandedNodeIds.size === 0}
            >
              全部收起
            </Button>
        <Button
              size="sm"
              variant="flat"
              color="primary"
              className="shrink-0"
              onPress={handleAdd}
             
            >
              新增
            </Button>
     
        </div>

        {/* 节点列表 */}
        {loading ? (
          <div className="flex items-center justify-center h-64">
            <div className="flex items-center gap-3">
              <Spinner size="sm" />
              <span className="text-default-600">正在加载...</span>
            </div>
          </div>
        ) : nodeList.length === 0 ? (
          <Card className="shadow-sm border border-gray-200 dark:border-gray-700">
            <CardBody className="text-center py-16">
              <div className="flex flex-col items-center gap-4">
                <div className="w-16 h-16 bg-default-100 rounded-full flex items-center justify-center">
                  <svg className="w-8 h-8 text-default-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M5 12h14M5 12l4-4m-4 4l4 4" />
                  </svg>
                </div>
                <div>
                  <h3 className="text-lg font-semibold text-foreground">暂无节点配置</h3>
                  <p className="text-default-500 text-sm mt-1">还没有创建任何节点配置，点击上方按钮开始创建</p>
                </div>
              </div>
            </CardBody>
          </Card>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5 gap-4">
            {nodeList.map((node) => (
              <Card 
                key={node.id} 
                className="shadow-sm border border-divider hover:shadow-md transition-shadow duration-200"
              >
                <CardHeader className="pb-2">
                  <div className="flex justify-between items-start w-full">
                    <div className="flex-1 min-w-0">
                      <h3 className="font-semibold text-foreground truncate text-sm">{node.name}</h3>
                      <p className="text-xs text-default-500 truncate">{getNodePrimaryAddress(node)}</p>
                    </div>
                    <div className="flex items-center gap-1.5 ml-2">
                      <Chip
                        color={getWallMonitorColor(node)}
                        variant="flat"
                        size="sm"
                        className="text-xs"
                      >
                        {getWallMonitorLabel(node)}
                      </Chip>
                      <Chip 
                        color={node.connectionStatus === 'online' ? 'success' : 'danger'} 
                        variant="flat" 
                        size="sm"
                        className="text-xs"
                      >
                        {node.connectionStatus === 'online' ? '在线' : '离线'}
                      </Chip>
                    </div>
                  </div>
                </CardHeader>

                <CardBody className="pt-0 pb-3">
                  <div className="space-y-3">
                    <div className="grid grid-cols-2 gap-2 text-xs">
                      <div className="rounded bg-default-50 p-2 text-center dark:bg-default-100">
                        <div className="text-default-600 mb-0.5">上传速度</div>
                        <div className="font-mono">
                          {node.connectionStatus === 'online' && node.systemInfo
                            ? formatSpeed(node.systemInfo.uploadSpeed)
                            : '-'}
                        </div>
                      </div>
                      <div className="rounded bg-default-50 p-2 text-center dark:bg-default-100">
                        <div className="text-default-600 mb-0.5">下载速度</div>
                        <div className="font-mono">
                          {node.connectionStatus === 'online' && node.systemInfo
                            ? formatSpeed(node.systemInfo.downloadSpeed)
                            : '-'}
                        </div>
                      </div>
                    </div>
                    <div className="space-y-1 text-xs">
                      <div className="flex justify-between gap-2">
                        <span className="text-default-500">累计总流量</span>
                        <span className="font-mono">
                          {node.vpsUsage?.current ? formatTraffic(node.vpsUsage.current.totalBytes) : '-'}
                        </span>
                      </div>
                      <div className="flex justify-between gap-2">
                        <span className="text-default-500">累计使用时长</span>
                        <span>{node.vpsUsage?.current ? formatUsageDuration(node.vpsUsage.current) : '-'}</span>
                      </div>
                    </div>
                    <div className="flex items-center justify-around rounded border border-default-200 bg-default-50 px-2 py-1.5 dark:bg-default-100/20">
                      <MetricRing
                        label="CPU"
                        value={node.connectionStatus === 'online' && node.systemInfo ? node.systemInfo.cpuUsage : 0}
                        offline={node.connectionStatus !== 'online'}
                      />
                      <MetricRing
                        label="内存"
                        value={node.connectionStatus === 'online' && node.systemInfo ? node.systemInfo.memoryUsage : 0}
                        offline={node.connectionStatus !== 'online'}
                      />
                      <MetricRing
                        label="Swap"
                        value={node.connectionStatus === 'online' && node.systemInfo?.swapUsage !== undefined ? node.systemInfo.swapUsage : 0}
                        offline={node.connectionStatus !== 'online'}
                        unavailable={node.systemInfo?.swapUsage === undefined || !node.systemInfo.swapTotal}
                      />
                    </div>
                    <Button
                      size="sm"
                      variant="light"
                      className="w-full min-h-8 justify-between px-2 text-default-500"
                      onPress={() => toggleNodeDetails(node.id)}
                      aria-expanded={expandedNodeIds.has(node.id)}
                    >
                      <span>{expandedNodeIds.has(node.id) ? '收起详情' : '更多详情'}</span>
                      <svg
                        className={`h-4 w-4 transition-transform ${expandedNodeIds.has(node.id) ? 'rotate-180' : ''}`}
                        viewBox="0 0 20 20"
                        fill="currentColor"
                        aria-hidden="true"
                      >
                        <path fillRule="evenodd" d="M5.23 7.21a.75.75 0 011.06.02L10 11.168l3.71-3.938a.75.75 0 111.08 1.04l-4.25 4.51a.75.75 0 01-1.08 0l-4.25-4.51a.75.75 0 01.02-1.06z" clipRule="evenodd" />
                      </svg>
                    </Button>
                  </div>

                  {expandedNodeIds.has(node.id) && (
                    <div className="mt-3 space-y-3 border-t border-divider pt-3">
                    <div className="grid grid-cols-1 gap-3">
                      <div>
                        <div className="flex justify-between text-xs mb-1">
                          <span>CPU</span>
                          <span className="font-mono">
                            {node.connectionStatus === 'online' && node.systemInfo ? `${node.systemInfo.cpuUsage.toFixed(1)}%` : '-'}
                          </span>
                        </div>
                        <Progress
                          value={node.connectionStatus === 'online' && node.systemInfo ? node.systemInfo.cpuUsage : 0}
                          color={getProgressColor(
                            node.connectionStatus === 'online' && node.systemInfo ? node.systemInfo.cpuUsage : 0,
                            node.connectionStatus !== 'online'
                          )}
                          size="sm"
                          aria-label="CPU使用率"
                        />
                      </div>
                      <div>
                        <div className="flex justify-between text-xs mb-1">
                          <span>内存</span>
                          <span className="whitespace-nowrap font-mono">
                            {node.connectionStatus === 'online' && node.systemInfo ? formatMemoryMetric(node.systemInfo) : '-'}
                          </span>
                        </div>
                        <Progress
                          value={node.connectionStatus === 'online' && node.systemInfo ? node.systemInfo.memoryUsage : 0}
                          color={getProgressColor(
                            node.connectionStatus === 'online' && node.systemInfo ? node.systemInfo.memoryUsage : 0,
                            node.connectionStatus !== 'online'
                          )}
                          size="sm"
                          aria-label="内存使用率"
                        />
                      </div>
                      <div>
                        <div className="flex justify-between gap-1 text-xs mb-1">
                          <span>Swap</span>
                          <span className="whitespace-nowrap font-mono">
                            {node.connectionStatus === 'online' && node.systemInfo ? formatSwapMetric(node.systemInfo) : '-'}
                          </span>
                        </div>
                        <Progress
                          value={node.connectionStatus === 'online' && node.systemInfo?.swapUsage !== undefined ? node.systemInfo.swapUsage : 0}
                          color={getProgressColor(
                            node.connectionStatus === 'online' && node.systemInfo?.swapUsage !== undefined ? node.systemInfo.swapUsage : 0,
                            node.connectionStatus !== 'online' || node.systemInfo?.swapUsage === undefined || !node.systemInfo.swapTotal
                          )}
                          size="sm"
                          aria-label="Swap使用率"
                        />
                      </div>
                    </div>
                  {/* 基础信息 */}
                  <div className="space-y-2 mb-4">
                    <div className="flex justify-between items-center text-sm min-w-0">
                      <span className="text-default-600 flex-shrink-0">入口IP</span>
                      <div className="text-right text-xs min-w-0 flex-1 ml-2">
                        {getNodeEntryAddresses(node).length > 0 ? (
                          getNodeEntryAddresses(node).length > 1 ? (
                            <span className="font-mono truncate block" title={getNodeEntryAddresses(node)[0]}>
                              {getNodeEntryAddresses(node)[0]} +{getNodeEntryAddresses(node).length - 1}个
                            </span>
                          ) : (
                            <span className="font-mono truncate block" title={getNodeEntryAddresses(node)[0]}>
                              {getNodeEntryAddresses(node)[0]}
                            </span>
                          )
                        ) : '-'}
                      </div>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-default-600">公网IPv4</span>
                      <span className="text-xs font-mono truncate ml-2 text-right max-w-[65%]" title={node.serverIpv4 || '-'}>
                        {node.serverIpv4 || '-'}
                      </span>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-default-600">公网IPv6</span>
                      <span className="text-xs font-mono truncate ml-2 text-right max-w-[65%]" title={node.serverIpv6 || '-'}>
                        {node.serverIpv6 || '-'}
                      </span>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-default-600">端口</span>
                      <span className="text-xs">{node.portSta}-{node.portEnd}</span>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-default-600">版本</span>
                      <span className="text-xs">{node.version || '未知'}</span>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-default-600">本次开机时间</span>
                      <span className="text-xs">
                        {node.connectionStatus === 'online' && node.systemInfo 
                          ? formatUptime(node.systemInfo.uptime)
                          : '-'
                        }
                      </span>
                    </div>
                    <div className="rounded border border-default-200 bg-default-50 dark:bg-default-100/20 p-2 text-xs">
                      <div className="flex items-center justify-between gap-2">
                        <span className="text-default-600">被墙监测</span>
                        <Chip
                          color={getWallMonitorColor(node)}
                          variant="flat"
                          size="sm"
                          className="text-xs"
                        >
                          {getWallMonitorLabel(node)}
                        </Chip>
                      </div>
                      <div
                        className="mt-1 truncate text-default-500"
                        title={node.wallMonitorExternalMessage || ""}
                      >
                        {node.wallMonitorExternalMessage || "等待 APK 回报"}
                      </div>
                      <div className="mt-1 flex items-center justify-between gap-2 text-default-400">
                        <span>来源：独立 APK</span>
                        <span>{formatMonitorTime(node.wallMonitorExternalLastCheckAt)}</span>
                      </div>
                      <div className="mt-2 flex items-center justify-between gap-2 text-default-400">
                        <span>Oracle 自动换 IP</span>
                        <Chip color={getChangeIpAttemptStatus(node).color} variant="flat" size="sm">
                          {getChangeIpAttemptStatus(node).label}
                        </Chip>
                      </div>
                      <div className="mt-1 flex items-center justify-between gap-2 text-default-400">
                        <span>{node.changeIpLastAttemptAt ? '最近操作时间' : '最小间隔'} </span>
                        <span>{node.changeIpLastAttemptAt
                          ? formatMonitorTime(node.changeIpLastAttemptAt)
                          : `${node.changeIpMinIntervalMinutes || 3} 分钟`}</span>
                      </div>
                      {node.remoteChangeIpUrl && (
                        <div className="mt-2 flex items-center gap-2">
                          <span className="min-w-0 flex-1 truncate text-default-500" title={node.remoteChangeIpUrl}>
                            独立 APK 回调 API
                          </span>
                          <Button
                            size="sm"
                            variant="flat"
                            color="primary"
                            onPress={() => handleCopyRemoteChangeIpUrl(node)}
                            className="min-h-7"
                          >
                            复制
                          </Button>
                        </div>
                      )}
                    </div>
                  </div>

                  {/* 系统监控 */}
                  <div className="space-y-3 mb-4">
                    <div className="grid grid-cols-2 gap-2 text-xs">
                      <div className="text-center p-2 bg-default-50 dark:bg-default-100 rounded">
                        <div className="text-default-600 mb-0.5">上传</div>
                        <div className="font-mono">
                          {node.connectionStatus === 'online' && node.systemInfo 
                            ? formatSpeed(node.systemInfo.uploadSpeed) 
                            : '-'
                          }
                        </div>
                      </div>
                      <div className="text-center p-2 bg-default-50 dark:bg-default-100 rounded">
                        <div className="text-default-600 mb-0.5">下载</div>
                        <div className="font-mono">
                          {node.connectionStatus === 'online' && node.systemInfo 
                            ? formatSpeed(node.systemInfo.downloadSpeed) 
                            : '-'
                          }
                        </div>
                      </div>
                    </div>

                    {/* 流量统计 */}
                    <div className="grid grid-cols-2 gap-2 text-xs">
                      <div className="text-center p-2 bg-primary-50 dark:bg-primary-100/20 rounded border border-primary-200 dark:border-primary-300/20">
                        <div className="text-primary-600 dark:text-primary-400 mb-0.5">
                          {node.vpsUsage?.current || supportsNodeVersion(node.version, 7) ? '累计上传' : '↑ 上行流量'}
                        </div>
                        <div className="font-mono text-primary-700 dark:text-primary-300">
                          {node.vpsUsage?.current ? formatTraffic(node.vpsUsage.current.uploadBytes)
                            : !supportsNodeVersion(node.version, 7) && node.connectionStatus === 'online' && node.systemInfo
                              ? formatTraffic(node.systemInfo.uploadTraffic) : '-'}
                        </div>
                      </div>
                      <div className="text-center p-2 bg-success-50 dark:bg-success-100/20 rounded border border-success-200 dark:border-success-300/20">
                        <div className="text-success-600 dark:text-success-400 mb-0.5">
                          {node.vpsUsage?.current || supportsNodeVersion(node.version, 7) ? '累计下载' : '↓ 下行流量'}
                        </div>
                        <div className="font-mono text-success-700 dark:text-success-300">
                          {node.vpsUsage?.current ? formatTraffic(node.vpsUsage.current.downloadBytes)
                            : !supportsNodeVersion(node.version, 7) && node.connectionStatus === 'online' && node.systemInfo
                              ? formatTraffic(node.systemInfo.downloadTraffic) : '-'}
                        </div>
                      </div>
                    </div>
                    {(node.vpsUsage?.current || supportsNodeVersion(node.version, 7)) && (
                      <div className="space-y-1 text-xs">
                        <div className="flex justify-between gap-2">
                          <span className="text-default-500">累计总流量</span>
                          <span className="font-mono">{node.vpsUsage?.current ? formatTraffic(node.vpsUsage.current.totalBytes) : '-'}</span>
                        </div>
                        <div className="flex justify-between gap-2">
                          <span className="text-default-500">累计使用时长</span>
                          <span>{node.vpsUsage?.current ? formatUsageDuration(node.vpsUsage.current) : '-'}</span>
                        </div>
                      </div>
                    )}
                    {!supportsNodeVersion(node.version, 7) ? (
                      <p className="text-xs text-default-500">升级节点至 3.1.7 后启用累计统计。</p>
                    ) : node.vpsUsage?.pending ? (
                      <p className="text-xs text-warning-600">VPS 身份待确认，请在使用记录中选择是否更换或重装。</p>
                    ) : !node.vpsUsage?.current ? (
                      <p className="text-xs text-default-500">等待节点上报累计统计。</p>
                    ) : null}
                    <Button size="sm" variant="flat" className="w-full min-h-8" onPress={() => openUsageHistory(node)}>
                      VPS 使用记录{node.vpsUsage?.pending ? ' · 待确认' : ''}
                    </Button>
                  </div>

                  {/* 操作按钮 */}
                  <div className="space-y-1.5">
                    <div className="mb-2 text-xs text-default-500">
                      <div className="flex justify-between gap-2">
                        <span>定时重启</span>
                        <span>{(node.rebootIntervalHours || 0) > 0 ? `每 ${node.rebootIntervalHours} 小时 + 随机延迟` : '已关闭'}</span>
                      </div>
                      {(node.rebootIntervalHours || 0) > 0 && (
                        <p className="mt-1">
                          下次：{getRebootNextLabel(node)}
                        </p>
                      )}
                    </div>
                    <div className="grid grid-cols-2 gap-1.5">
                      <Button
                        size="sm"
                        variant="flat"
                        color="success"
                        onPress={() => handleCopyInstallCommand(node, 'github')}
                        isLoading={node.copyLoading}
                        className="flex-1 min-h-8"
                      >
                        GitHub 安装
                      </Button>
                      <Button
                        size="sm"
                        variant="flat"
                        color="secondary"
                        onPress={() => handleCopyInstallCommand(node, 'local')}
                        isLoading={node.copyLoading}
                        className="flex-1 min-h-8"
                      >
                        本地安装
                      </Button>
                      <Button
                        size="sm"
                        variant="flat"
                        color="warning"
                        onPress={() => handleReboot(node)}
                        isDisabled={node.connectionStatus !== 'online' || !supportsReboot(node.version) || isRebootPending(node.id)}
                        isLoading={rebootStates[node.id]?.phase === 'sending'}
                        title="点击立即重启 VPS，连接会短暂中断"
                        className="flex-1 min-h-8"
                      >
                        {rebootStates[node.id]?.phase === 'sending' ? '发送中…'
                          : rebootStates[node.id]?.phase === 'waiting' ? '重启中…'
                            : rebootStates[node.id]?.phase === 'unknown' ? '等待状态…' : '重启 VPS'}
                      </Button>
                      <Button
                        size="sm"
                        variant="flat"
                        color="default"
                        onPress={() => openRebootSchedule(node)}
                        className="flex-1 min-h-8"
                      >
                        定时重启
                      </Button>
                      <Button
                        size="sm"
                        variant="flat"
                        color="primary"
                        onPress={() => handleEdit(node)}
                        className="flex-1 min-h-8"
                      >
                        编辑
                      </Button>
                      <Button
                        size="sm"
                        variant="flat"
                        color="danger"
                        onPress={() => handleDelete(node)}
                        className="flex-1 min-h-8"
                      >
                        删除
                      </Button>
                    </div>
                    {!supportsReboot(node.version) ? (
                      <p className="text-xs text-default-500">重启功能需要节点 3.1.6 或以上版本，请先升级节点。</p>
                    ) : rebootStates[node.id]?.phase === 'unknown' ? (
                      <p className="text-xs text-warning-600">结果未知，请等待节点状态更新，勿重复操作。</p>
                    ) : rebootStates[node.id]?.phase === 'unconfirmed' ? (
                      <p className="text-xs text-warning-600">尚未确认重启完成，请检查节点后再操作。</p>
                    ) : rebootStates[node.id]?.phase === 'waiting' ? (
                      <p className="text-xs text-default-500">节点已收到指令，等待重新上线。</p>
                    ) : node.connectionStatus !== 'online' ? (
                      <p className="text-xs text-default-500">节点离线，暂时无法发送重启指令。</p>
                    ) : null}
                  </div>
                  </div>
                  )}
                </CardBody>
              </Card>
            ))}
          </div>
        )}

        {/* VPS 当前与最近两份历史记录 */}
        <Modal
          isOpen={usageHistoryNodeId !== null}
          onClose={closeUsageHistory}
          isDismissable={!usageConfirmLoading}
          isKeyboardDismissDisabled={Boolean(usageConfirmLoading)}
          hideCloseButton={Boolean(usageConfirmLoading)}
          size="2xl"
          scrollBehavior="inside"
          backdrop="blur"
          placement="center"
        >
          <ModalContent>
            <ModalHeader>VPS 使用记录 - {usageHistoryNode?.name || '节点'}</ModalHeader>
            <ModalBody className="gap-4">
              <p className="text-sm text-default-500">
                保留当前 VPS 和最近 2 份历史记录。首次启用从 0 开始累计；重启、更新和更换 IP 会继续累计，离线时间也计入使用时长。
              </p>
              {usageHistoryError && <p role="alert" className="text-sm text-danger">{usageHistoryError}</p>}
              {pendingUsage && (
                <div className="space-y-2 border-b border-divider pb-4">
                  <p className="text-sm font-medium text-warning-600">待确认 VPS 身份</p>
                  <p className="text-sm break-words">地址：{pendingUsage.address || '未上报'}</p>
                  <p className="text-sm text-default-500">发现时间：{formatMonitorTime(pendingUsage.detectedAt)}</p>
                  <p className="text-sm text-default-600">{pendingUsage.reason}</p>
                  <p className="text-xs text-default-500">确认更换或重装后会开始新记录；超出 3 份时删除最早一份。</p>
                  <div className="flex flex-wrap gap-2">
                    <Button
                      size="sm"
                      variant="flat"
                      color="primary"
                      isDisabled={!currentUsage || !pendingUsage.canKeepCurrent || Boolean(usageConfirmLoading)}
                      isLoading={usageConfirmLoading === 'same'}
                      onPress={() => handleConfirmUsage('same')}
                    >
                      仍是同一台
                    </Button>
                    <Button
                      size="sm"
                      variant="flat"
                      color="warning"
                      isDisabled={Boolean(usageConfirmLoading)}
                      isLoading={usageConfirmLoading === 'replace'}
                      onPress={() => handleConfirmUsage('replace')}
                    >
                      已更换／重装
                    </Button>
                  </div>
                </div>
              )}
              {usageHistoryLoading ? (
                <div className="flex items-center gap-2 py-4 text-sm text-default-500"><Spinner size="sm" />正在加载记录…</div>
              ) : visibleUsageRecords.length ? visibleUsageRecords.map((record, index) => (
                <div key={record.id} className="space-y-2 border-b border-divider pb-4 last:border-0">
                  <div className="flex flex-wrap justify-between gap-2 text-sm">
                    <span className="font-medium">{record.replacedAt === null ? '当前 VPS'
                      : index - (currentUsage ? 1 : 0) === 0 ? '上一台 VPS' : '上上一台 VPS'}</span>
                    <span className="break-all font-mono text-xs text-default-500">{record.address || '未上报地址'}</span>
                  </div>
                  <dl className="grid grid-cols-1 gap-x-6 gap-y-2 text-xs sm:grid-cols-2">
                    <div className="flex justify-between gap-2"><dt className="text-default-500">开始时间</dt><dd>{formatMonitorTime(record.startedAt)}</dd></div>
                    <div className="flex justify-between gap-2"><dt className="text-default-500">更换时间</dt><dd>{record.replacedAt ? formatMonitorTime(record.replacedAt) : '使用中'}</dd></div>
                    <div className="flex justify-between gap-2"><dt className="text-default-500">累计上传</dt><dd>{formatTraffic(record.uploadBytes)}</dd></div>
                    <div className="flex justify-between gap-2"><dt className="text-default-500">累计下载</dt><dd>{formatTraffic(record.downloadBytes)}</dd></div>
                    <div className="flex justify-between gap-2"><dt className="text-default-500">累计总流量</dt><dd>{formatTraffic(record.totalBytes)}</dd></div>
                    <div className="flex justify-between gap-2"><dt className="text-default-500">使用时长</dt><dd>{formatUsageDuration(record)}</dd></div>
                  </dl>
                </div>
              )) : (
                <p className="py-4 text-sm text-default-500">
                  {supportsNodeVersion(usageHistoryNode?.version, 7) ? '暂无使用记录，等待节点上报或完成身份确认。' : '升级节点至 3.1.7 后启用累计统计。'}
                </p>
              )}
            </ModalBody>
            <ModalFooter>
              <Button variant="flat" isDisabled={Boolean(usageConfirmLoading)} onPress={closeUsageHistory}>关闭</Button>
            </ModalFooter>
          </ModalContent>
        </Modal>

        {/* 定时重启设置 */}
        <Modal
          isOpen={scheduleNodeId !== null}
          onClose={() => { if (!scheduleSavingRef.current) setScheduleNodeId(null); }}
          isDismissable={!scheduleLoading}
          isKeyboardDismissDisabled={scheduleLoading}
          hideCloseButton={scheduleLoading}
          size="md"
          scrollBehavior="outside"
          backdrop="blur"
          placement="center"
        >
          <ModalContent>
            <ModalHeader>定时重启 - {scheduleNode?.name || '节点'}</ModalHeader>
            <ModalBody>
              <Input
                label="重启间隔（小时）"
                type="number"
                min={0}
                max={720}
                step={1}
                value={scheduleHours}
                onValueChange={(value) => { setScheduleHours(value); setScheduleError(''); }}
                isDisabled={scheduleLoading}
                isInvalid={Boolean(scheduleError)}
                errorMessage={scheduleError}
                variant="bordered"
                description="请输入 0–720 的整数，0 表示关闭定时重启。"
              />
              <p className="text-sm text-default-500">
                每轮在所填间隔后，额外随机等待 0–59 分 59 秒再重启 VPS。节点离线时暂停，重新上线后开始下一轮计时。
              </p>
              <p className="text-sm text-default-500">
                当前计划：{getRebootNextLabel(scheduleNode)}
              </p>
              {scheduleNode && !supportsReboot(scheduleNode.version) && (
                <p className="text-sm text-warning-600">请先将节点升级至 3.1.6 或以上版本；现在仍可填写 0 关闭已有计划。</p>
              )}
            </ModalBody>
            <ModalFooter>
              <Button variant="flat" isDisabled={scheduleLoading} onPress={() => setScheduleNodeId(null)}>
                取消
              </Button>
              <Button color="primary" isLoading={scheduleLoading} onPress={saveRebootSchedule}>
                保存
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>

        {/* 新增/编辑节点对话框 */}
        <Modal 
          isOpen={dialogVisible} 
          onClose={() => setDialogVisible(false)}
          size="2xl"
          scrollBehavior="outside"
          backdrop="blur"
          placement="center"
        >
          <ModalContent>
            <ModalHeader>{dialogTitle}</ModalHeader>
            <ModalBody>
              <div className="space-y-4">
                <Input
                  label="节点名称"
                  placeholder="请输入节点名称"
                  value={form.name}
                  onChange={(e) => setForm(prev => ({ ...prev, name: e.target.value }))}
                  isInvalid={!!errors.name}
                  errorMessage={errors.name}
                  variant="bordered"
                />

                <Input
                  label="服务器IP（可选）"
                  placeholder="可留空，节点上线后自动识别公网 IP"
                  value={form.serverIp}
                  onChange={(e) => setForm(prev => ({ ...prev, serverIp: e.target.value }))}
                  isInvalid={!!errors.serverIp}
                  errorMessage={errors.serverIp}
                  variant="bordered"
                  description="手动填写时会作为覆盖值；动态 IP 节点建议留空自动回填"
                />

                <Textarea
                  label="入口IP（可选）"
                  placeholder="可留空；需要展示多个入口地址时再填写，一行一个"
                  value={form.ipString}
                  onChange={(e) => setForm(prev => ({ ...prev, ipString: e.target.value }))}
                  isInvalid={!!errors.ipString}
                  errorMessage={errors.ipString}
                  variant="bordered"
                  minRows={3}
                  maxRows={5}
                  description="留空时会默认跟随节点公网 IP；填写后用于转发页展示，不影响节点连接面板"
                />

                <div className="space-y-4 rounded border border-default-200 p-3">
                  <Switch
                    isSelected={form.oracleNode}
                    onValueChange={(enabled) => setForm(prev => ({
                      ...prev,
                      oracleNode: enabled,
                      ociAccountId: enabled ? prev.ociAccountId : null,
                      ociInstanceOcid: enabled ? prev.ociInstanceOcid : '',
                    }))}
                    color="primary"
                  >
                    <span className="text-sm">是否为 Oracle 节点</span>
                  </Switch>

                  {form.oracleNode && (
                    <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                      <Select
                        label="OCI 账号"
                        placeholder={ociAccountsLoading ? '正在加载账号...' : '选择 OCI 账号'}
                        selectedKeys={form.ociAccountId ? [String(form.ociAccountId)] : []}
                        onSelectionChange={(keys) => {
                          const key = Array.from(keys)[0] as string | undefined;
                          const accountId = key ? Number(key) : null;
                          setForm(prev => ({ ...prev, ociAccountId: accountId, ociInstanceOcid: '' }));
                          setErrors(prev => ({ ...prev, ociAccountId: '', ociInstanceOcid: '' }));
                        }}
                        isLoading={ociAccountsLoading}
                        isDisabled={ociAccountsLoading || ociAccounts.length === 0}
                        isInvalid={!!errors.ociAccountId}
                        errorMessage={errors.ociAccountId}
                        variant="bordered"
                      >
                        {ociAccounts.map(account => (
                          <SelectItem key={String(account.id)} textValue={account.name}>
                            {account.name}{account.region ? ` · ${account.region}` : ''}
                          </SelectItem>
                        ))}
                      </Select>

                      <Select
                        label="OCI 实例"
                        placeholder={!form.ociAccountId ? '请先选择 OCI 账号' : ociInstancesLoading ? '正在加载实例...' : '选择 OCI 实例'}
                        selectedKeys={form.ociInstanceOcid ? [form.ociInstanceOcid] : []}
                        onSelectionChange={(keys) => {
                          const ocid = Array.from(keys)[0] as string | undefined;
                          setForm(prev => ({ ...prev, ociInstanceOcid: ocid || '' }));
                          setErrors(prev => ({ ...prev, ociInstanceOcid: '' }));
                        }}
                        isLoading={ociInstancesLoading}
                        isDisabled={!form.ociAccountId || ociInstancesLoading || ociInstances.length === 0}
                        isInvalid={!!errors.ociInstanceOcid}
                        errorMessage={errors.ociInstanceOcid}
                        variant="bordered"
                      >
                        {ociInstances.map(instance => (
                          <SelectItem
                            key={instance.instanceOcid}
                            textValue={`${instance.displayName} ${instance.publicIp || ''} ${instance.instanceOcid.slice(-8)}`}
                          >
                            <div className="flex min-w-0 flex-col">
                              <span className="truncate">{instance.displayName}</span>
                              <span className="truncate text-xs text-default-500">
                                {instance.publicIp || '无公网 IP'} · OCID …{instance.instanceOcid.slice(-8)}
                              </span>
                            </div>
                          </SelectItem>
                        ))}
                      </Select>
                    </div>
                  )}

                  <Input
                    label="换 IP 最小间隔（分钟）"
                    placeholder="留空默认 3 分钟"
                    type="number"
                    min={3}
                    value={form.changeIpMinIntervalMinutes}
                    onChange={(e) => setForm(prev => ({ ...prev, changeIpMinIntervalMinutes: e.target.value }))}
                    isInvalid={!!errors.changeIpMinIntervalMinutes}
                    errorMessage={errors.changeIpMinIntervalMinutes}
                    variant="bordered"
                    description="留空按 3 分钟；填写时不能低于 3 分钟。仅 Oracle 节点在 APK 报告不可达时尝试换 IP。"
                  />
                </div>

                <div className="grid grid-cols-2 gap-4">
                  <Input
                    label="起始端口"
                    type="number"
                    placeholder="1000"
                    value={form.portSta.toString()}
                    onChange={(e) => setForm(prev => ({ ...prev, portSta: parseInt(e.target.value) || 1000 }))}
                    isInvalid={!!errors.portSta}
                    errorMessage={errors.portSta}
                    variant="bordered"
                    min={1}
                    max={65535}
                  />

                  <Input
                    label="结束端口"
                    type="number"
                    placeholder="65535"
                    value={form.portEnd.toString()}
                    onChange={(e) => setForm(prev => ({ ...prev, portEnd: parseInt(e.target.value) || 65535 }))}
                    isInvalid={!!errors.portEnd}
                    errorMessage={errors.portEnd}
                    variant="bordered"
                    min={1}
                    max={65535}
                  />
                </div>



                
                <Alert
                        color="primary"
                        variant="flat"
                        description="新增节点时只需要填写节点名称和端口范围。服务器 IP 会在节点上线后自动识别并回填；入口 IP 只是面向用户展示的访问地址，留空时默认跟随服务器 IP，需要绑定域名或多入口地址时再手动填写。"
                        className="mt-4"
                      />
              </div>
            </ModalBody>
            <ModalFooter>
              <Button
                variant="flat"
                onPress={() => setDialogVisible(false)}
              >
                取消
              </Button>
              <Button
                color="primary"
                onPress={handleSubmit}
                isLoading={submitLoading}
              >
                {submitLoading ? '提交中...' : '确定'}
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>

        {/* 删除确认模态框 */}
        <Modal 
          isOpen={deleteModalOpen}
          onOpenChange={setDeleteModalOpen}
          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            {(onClose) => (
              <>
                <ModalHeader className="flex flex-col gap-1">
                  <h2 className="text-xl font-bold">确认删除</h2>
                </ModalHeader>
                <ModalBody>
                  <p>确定要删除节点 <strong>"{nodeToDelete?.name}"</strong> 吗？</p>
                  <p className="text-small text-default-500">该节点最多 3 份 VPS 使用记录也会一并删除，此操作不可恢复。</p>
                </ModalBody>
                <ModalFooter>
                  <Button variant="light" onPress={onClose}>
                    取消
                  </Button>
                  <Button 
                    color="danger" 
                    onPress={confirmDelete}
                    isLoading={deleteLoading}
                  >
                    {deleteLoading ? '删除中...' : '确认删除'}
                  </Button>
                </ModalFooter>
              </>
            )}
          </ModalContent>
        </Modal>

        {/* 安装命令模态框 */}
        <Modal 
          isOpen={installCommandModal} 
          onClose={() => setInstallCommandModal(false)}
          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            <ModalHeader>
              {installCommandSource === 'local' ? '本地安装命令' : 'GitHub 安装命令'} - {currentNodeName}
            </ModalHeader>
            <ModalBody>
              <div className="space-y-4">
                <p className="text-sm text-default-600">
                  请复制以下安装命令到服务器上执行：
                </p>
                <div className="relative">
                  <Textarea
                    value={installCommand}
                    readOnly
                    variant="bordered"
                    minRows={6}
                    maxRows={10}
                    className="font-mono text-sm"
                    classNames={{
                      input: "font-mono text-sm"
                    }}
                  />
                  <Button
                    size="sm"
                    color="primary"
                    variant="flat"
                    className="absolute top-2 right-2"
                    onPress={handleManualCopy}
                  >
                    复制
                  </Button>
                </div>
                <div className="text-xs text-default-500">
                  💡 提示：如果复制按钮失效，请手动选择上方文本进行复制
                </div>
              </div>
            </ModalBody>
            <ModalFooter>
              <Button
                variant="flat"
                onPress={() => setInstallCommandModal(false)}
              >
                关闭
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>
      </div>
    
  );
} 

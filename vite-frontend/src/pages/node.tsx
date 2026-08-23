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
import toast from 'react-hot-toast';
import axios from 'axios';


import { 
  createNode, 
  getNodeList, 
  updateNode, 
  deleteNode,
  getNodeInstallCommand,
  checkNodeWallMonitor,
  type NodeInstallSource
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
  wallMonitorChinaSuccessCount?: number;
  wallMonitorChinaTotalCount?: number;
  wallMonitorGlobalSuccessCount?: number;
  wallMonitorGlobalTotalCount?: number;
  wallMonitorLatencyMs?: number;
  wallMonitorMessage?: string;
  remoteChangeIpUrl?: string;
  wallMonitorChecking?: boolean;
}

interface NodeForm {
  id: number | null;
  name: string;
  ipString: string;
  serverIp: string;
  portSta: number;
  portEnd: number;
}

export default function NodePage() {
  const [nodeList, setNodeList] = useState<Node[]>([]);
  const [loading, setLoading] = useState(false);
  const [dialogVisible, setDialogVisible] = useState(false);
  const [dialogTitle, setDialogTitle] = useState('');
  const [isEdit, setIsEdit] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [deleteModalOpen, setDeleteModalOpen] = useState(false);
  const [deleteLoading, setDeleteLoading] = useState(false);
  const [nodeToDelete, setNodeToDelete] = useState<Node | null>(null);
  const [form, setForm] = useState<NodeForm>({
    id: null,
    name: '',
    ipString: '',
    serverIp: '',
    portSta: 1000,
    portEnd: 65535
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  
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
  const maxReconnectAttempts = 5;

  useEffect(() => {
    initWebSocket();
    loadNodes();
    
    return () => {
      closeWebSocket();
    };
  }, []);

  // 加载节点列表
  const loadNodes = async () => {
    setLoading(true);
    try {
      const res = await getNodeList();
      if (res.code === 0) {
        const nextNodes = res.data.map((node: any) => ({
          ...node,
          connectionStatus: node.status === 1 ? 'online' : 'offline',
          systemInfo: systemInfoCacheRef.current.get(node.id) || null,
          copyLoading: false,
          wallMonitorChecking: false
        }));
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
      } else {
        toast.error(res.msg || '加载节点列表失败');
      }
    } catch (error) {
      toast.error('网络错误，请重试');
    } finally {
      setLoading(false);
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
      const cachedRuntime = runtimeIpCacheRef.current.get(Number(id));
      if (cachedRuntime) {
        runtimeIpCacheRef.current.set(Number(id), {
          ...cachedRuntime,
          connectionStatus: nextConnectionStatus,
        });
      }
      setNodeList(prev => prev.map(node => {
        if (node.id == id) {
          return {
            ...node,
            connectionStatus: nextConnectionStatus,
            systemInfo: node.systemInfo
          };
        }
        return node;
      }));
    } else if (type === 'wallMonitor') {
      setNodeList(prev => prev.map(node => {
        if (node.id == id) {
          return {
            ...node,
            ...messageData,
            wallMonitorChecking: false
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

        const baseRuntimeIpPatch = hasRuntimeIp ? {
          serverIp: nextServerIp,
          serverIpv4: publicIpv4 || (isIpv4Literal(nextServerIp) ? nextServerIp : ''),
          serverIpv6: publicIpv6 || (isIpv6Literal(nextServerIp) ? nextServerIp : ''),
        } : {};
        if (hasRuntimeIp) {
          runtimeIpCacheRef.current.set(nodeId, {
            ...baseRuntimeIpPatch,
            connectionStatus: 'online',
          });
        }

        const hasSystemMetrics =
          systemInfo &&
          (
            Object.prototype.hasOwnProperty.call(systemInfo, "memory_usage") ||
            Object.prototype.hasOwnProperty.call(systemInfo, "cpu_usage") ||
            Object.prototype.hasOwnProperty.call(systemInfo, "bytes_received") ||
            Object.prototype.hasOwnProperty.call(systemInfo, "bytes_transmitted") ||
            Object.prototype.hasOwnProperty.call(systemInfo, "uptime")
          );
        let nextSystemInfo: NonNullable<Node['systemInfo']> | null = null;
        if (hasSystemMetrics) {
          const currentUpload = parseInt(systemInfo.bytes_transmitted) || 0;
          const currentDownload = parseInt(systemInfo.bytes_received) || 0;
          const currentUptime = parseInt(systemInfo.uptime) || 0;
          const previousSystemInfo = systemInfoCacheRef.current.get(nodeId);
          // 页面刚连接时后端可能只回放一份快照，优先使用后端缓存的最近速度，避免先显示 0。
          let uploadSpeed = parseFloat(systemInfo.upload_speed) || 0;
          let downloadSpeed = parseFloat(systemInfo.download_speed) || 0;

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
            cpuUsage: parseFloat(systemInfo.cpu_usage) || 0,
            memoryUsage: parseFloat(systemInfo.memory_usage) || 0,
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
              connectionStatus: 'online',
            });
          }
          return {
            ...node,
            ...runtimeIpPatch,
            connectionStatus: 'online',
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

  // 获取进度条颜色
  const getProgressColor = (value: number, offline = false): "default" | "primary" | "secondary" | "success" | "warning" | "danger" => {
    if (offline) return "default";
    if (value <= 50) return "success";
    if (value <= 80) return "warning";
    return "danger";
  };

  const getWallMonitorColor = (node: Node): "default" | "primary" | "secondary" | "success" | "warning" | "danger" => {
    if (node.wallMonitorEnabled === 0) return "default";
    switch (node.wallMonitorStatus) {
      case "OK":
        return "success";
      case "OBSERVING":
        return "warning";
      case "SUSPECTED_BLOCKED":
        return "danger";
      case "CHECK_FAILED":
        return "warning";
      case "NODE_OFFLINE":
        return "default";
      default:
        return "secondary";
    }
  };

  const getWallMonitorLabel = (node: Node): string => {
    if (node.wallMonitorEnabled === 0) return "未开启";
    switch (node.wallMonitorStatus) {
      case "OK":
        return "正常";
      case "OBSERVING":
        return "观察中";
      case "SUSPECTED_BLOCKED":
        return "疑似被墙";
      case "CHECK_FAILED":
        return "检测异常";
      case "NODE_OFFLINE":
        return "节点离线";
      default:
        return "待检测";
    }
  };

  const formatMonitorTime = (timestamp?: number): string => {
    if (!timestamp) return "-";
    return new Date(timestamp).toLocaleString();
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

  const confirmDelete = async () => {
    if (!nodeToDelete) return;
    
    setDeleteLoading(true);
    try {
      const res = await deleteNode(nodeToDelete.id);
      if (res.code === 0) {
        toast.success('删除成功');
        systemInfoCacheRef.current.delete(nodeToDelete.id);
        runtimeIpCacheRef.current.delete(nodeToDelete.id);
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

  const handleCopyRemoteChangeIpUrl = async (node: Node) => {
    if (!node.remoteChangeIpUrl) {
      toast.error('当前节点没有可用的远程 API 地址');
      return;
    }
    try {
      await navigator.clipboard.writeText(node.remoteChangeIpUrl);
      toast.success('远程换 IP API 已复制');
    } catch (error) {
      toast.error('复制失败，请使用 HTTPS 访问面板后重试');
    }
  };

  // 节点被墙监测
  const handleWallMonitorCheck = async (node: Node) => {
    setNodeList(prev => prev.map(n =>
      n.id === node.id ? { ...n, wallMonitorChecking: true } : n
    ));

    try {
      const res = await checkNodeWallMonitor(node.id);
      if (res.code === 0 && res.data) {
        setNodeList(prev => prev.map(n =>
          n.id === node.id ? { ...n, ...res.data, wallMonitorChecking: false } : n
        ));
        toast.success('检测完成');
      } else {
        toast.error(res.msg || '检测失败');
      }
    } catch (error) {
      toast.error('检测失败，请稍后重试');
    } finally {
      setNodeList(prev => prev.map(n =>
        n.id === node.id ? { ...n, wallMonitorChecking: false } : n
      ));
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
        
      const submitData = {
        ...form,
        ip: ipString
      };
      delete (submitData as any).ipString;
      
      const apiCall = isEdit ? updateNode : createNode;
      const data = isEdit ? submitData : { 
        name: form.name, 
        ip: ipString,
        serverIp: form.serverIp,
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

  return (
    
      <div className="px-3 lg:px-6 py-8">
        {/* 页面头部 */}
        <div className="flex items-center justify-between mb-6">
        <div className="flex-1">
        </div>

        <Button
              size="sm"
              variant="flat"
              color="primary"
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
                      <span className="text-default-600">开机时间</span>
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
                      <div className="mt-1 truncate text-default-500" title={node.wallMonitorMessage || ""}>
                        {node.wallMonitorMessage || "等待定时检测"}
                      </div>
                      <div className="mt-1 flex items-center justify-between gap-2 text-default-400">
                        <span>国内 {node.wallMonitorChinaSuccessCount || 0}/{node.wallMonitorChinaTotalCount || 0}</span>
                        <span>国际 {node.wallMonitorGlobalSuccessCount || 0}/{node.wallMonitorGlobalTotalCount || 0}</span>
                      </div>
                      <div className="mt-1 flex items-center justify-between gap-2 text-default-400">
                        <span>延迟 {node.wallMonitorLatencyMs ? `${node.wallMonitorLatencyMs.toFixed(0)}ms` : "-"}</span>
                        <span>{formatMonitorTime(node.wallMonitorLastCheckAt)}</span>
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
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <div className="flex justify-between text-xs mb-1">
                          <span>CPU</span>
                          <span className="font-mono">
                            {node.connectionStatus === 'online' && node.systemInfo 
                              ? `${node.systemInfo.cpuUsage.toFixed(1)}%` 
                              : '-'
                            }
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
                          <span className="font-mono">
                            {node.connectionStatus === 'online' && node.systemInfo 
                              ? `${node.systemInfo.memoryUsage.toFixed(1)}%` 
                              : '-'
                            }
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
                    </div>

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
                        <div className="text-primary-600 dark:text-primary-400 mb-0.5">↑ 上行流量</div>
                        <div className="font-mono text-primary-700 dark:text-primary-300">
                          {node.connectionStatus === 'online' && node.systemInfo 
                            ? formatTraffic(node.systemInfo.uploadTraffic) 
                            : '-'
                          }
                        </div>
                      </div>
                      <div className="text-center p-2 bg-success-50 dark:bg-success-100/20 rounded border border-success-200 dark:border-success-300/20">
                        <div className="text-success-600 dark:text-success-400 mb-0.5">↓ 下行流量</div>
                        <div className="font-mono text-success-700 dark:text-success-300">
                          {node.connectionStatus === 'online' && node.systemInfo 
                            ? formatTraffic(node.systemInfo.downloadTraffic) 
                            : '-'
                          }
                        </div>
                      </div>
                    </div>
                  </div>

                  {/* 操作按钮 */}
                  <div className="space-y-1.5">
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
                    <Button
                      size="sm"
                      variant="flat"
                      color="warning"
                      onPress={() => handleWallMonitorCheck(node)}
                      isLoading={node.wallMonitorChecking}
                      isDisabled={node.connectionStatus !== 'online'}
                      className="w-full min-h-8"
                    >
                      立即检测
                    </Button>
                  </div>
                </CardBody>
              </Card>
            ))}
          </div>
        )}

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
                  <p className="text-small text-default-500">此操作不可恢复，请谨慎操作。</p>
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

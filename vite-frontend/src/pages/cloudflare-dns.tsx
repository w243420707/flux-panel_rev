import { useEffect, useState } from "react";
import { Alert } from "@heroui/alert";
import { Button } from "@heroui/button";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Chip } from "@heroui/chip";
import { Divider } from "@heroui/divider";
import { Input } from "@heroui/input";
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from "@heroui/modal";
import { Select, SelectItem } from "@heroui/select";
import { Spinner } from "@heroui/spinner";
import { Switch } from "@heroui/switch";
import toast from "react-hot-toast";

import {
  deleteCloudflareDnsBinding,
  getCloudflareDnsBindingList,
  getCloudflareDnsSetting,
  getNodeList,
  getTunnelList,
  saveCloudflareDnsBinding,
  syncCloudflareDnsAll,
  syncCloudflareDnsBinding,
  testCloudflareDnsSetting,
  updateCloudflareDnsSetting,
} from "@/api";

interface CloudflareDnsSetting {
  enabled: number;
  apiTokenConfigured?: boolean;
  zoneId?: string;
  zoneName?: string;
  ttl: number;
  recordType: string;
  syncIntervalSeconds: number;
  autoUpdateNodeIp: number;
  lastSyncAt?: number;
  lastSyncStatus?: string;
  lastSyncMessage?: string;
}

interface CloudflareDnsBinding {
  id: number;
  tunnelId: number;
  domain: string;
  nodeIds?: string | number[];
  useTunnelNodes?: number;
  recordType?: string;
  status?: number;
  lastSyncAt?: number;
  lastSyncStatus?: string;
  lastSyncMessage?: string;
  lastResolvedIps?: string;
}

interface Tunnel {
  id: number;
  name: string;
  inNodeId?: number;
  inNodeIds?: string | number[];
}

interface Node {
  id: number;
  name: string;
  serverIp?: string;
  status?: number;
}

interface BindingForm {
  id?: number;
  tunnelId: number | null;
  domain: string;
  useTunnelNodes: boolean;
  nodeIds: number[];
  recordType: string;
}

const defaultSetting: CloudflareDnsSetting = {
  enabled: 0,
  ttl: 1,
  recordType: "AUTO",
  syncIntervalSeconds: 120,
  autoUpdateNodeIp: 1,
};

const defaultBindingForm: BindingForm = {
  tunnelId: null,
  domain: "",
  useTunnelNodes: true,
  nodeIds: [],
  recordType: "AUTO",
};

export default function CloudflareDnsPage() {
  const [loading, setLoading] = useState(true);
  const [setting, setSetting] = useState<CloudflareDnsSetting>(defaultSetting);
  const [apiToken, setApiToken] = useState("");
  const [bindings, setBindings] = useState<CloudflareDnsBinding[]>([]);
  const [tunnels, setTunnels] = useState<Tunnel[]>([]);
  const [nodes, setNodes] = useState<Node[]>([]);
  const [settingLoading, setSettingLoading] = useState(false);
  const [testLoading, setTestLoading] = useState(false);
  const [syncAllLoading, setSyncAllLoading] = useState(false);
  const [bindingModalOpen, setBindingModalOpen] = useState(false);
  const [bindingSubmitLoading, setBindingSubmitLoading] = useState(false);
  const [bindingActionId, setBindingActionId] = useState<number | null>(null);
  const [bindingForm, setBindingForm] = useState<BindingForm>(defaultBindingForm);

  useEffect(() => {
    loadData();
  }, []);

  const loadData = async () => {
    setLoading(true);
    try {
      const [settingRes, bindingRes, tunnelRes, nodeRes] = await Promise.all([
        getCloudflareDnsSetting(),
        getCloudflareDnsBindingList(),
        getTunnelList(),
        getNodeList(),
      ]);

      if (settingRes.code === 0) {
        setSetting({ ...defaultSetting, ...(settingRes.data || {}) });
      } else {
        toast.error(settingRes.msg || "加载 Cloudflare 配置失败");
      }

      if (bindingRes.code === 0) {
        setBindings(bindingRes.data || []);
      } else {
        toast.error(bindingRes.msg || "加载 DNS 绑定失败");
      }

      if (tunnelRes.code === 0) {
        setTunnels(tunnelRes.data || []);
      }

      if (nodeRes.code === 0) {
        setNodes(nodeRes.data || []);
      }
    } catch (error) {
      toast.error("加载 Cloudflare DNS 数据失败");
    } finally {
      setLoading(false);
    }
  };

  const normalizeNodeIds = (value?: string | number[], fallback?: number | null): number[] => {
    const ids: number[] = [];
    const pushId = (raw: unknown) => {
      const id = typeof raw === "number" ? raw : parseInt(String(raw), 10);
      if (!Number.isNaN(id) && id > 0 && !ids.includes(id)) {
        ids.push(id);
      }
    };

    if (Array.isArray(value)) {
      value.forEach(pushId);
    } else if (typeof value === "string" && value.trim()) {
      try {
        const parsed = JSON.parse(value);
        if (Array.isArray(parsed)) {
          parsed.forEach(pushId);
        }
      } catch {
        value.replace(/[\[\]"']/g, "").split(",").forEach((part) => pushId(part.trim()));
      }
    }

    if (ids.length === 0 && fallback) {
      pushId(fallback);
    }
    return ids;
  };

  const selectedKeysToNodeIds = (keys: any): number[] => {
    if (keys === "all") {
      return nodes.map((node) => node.id);
    }
    return Array.from(keys || [])
      .map((key) => parseInt(String(key), 10))
      .filter((id) => !Number.isNaN(id));
  };

  const nodeIdsToSelectedKeys = (ids: number[]) => new Set(ids.map((id) => id.toString()));

  const getTunnel = (id?: number) => tunnels.find((tunnel) => tunnel.id === id);

  const getTunnelName = (id?: number) => getTunnel(id)?.name || `隧道 ${id || "-"}`;

  const getNodeName = (id?: number) => nodes.find((node) => node.id === id)?.name || `节点 ${id || "-"}`;

  const getBindingNodeIds = (binding: CloudflareDnsBinding) => {
    if (binding.useTunnelNodes === undefined || binding.useTunnelNodes === 1) {
      const tunnel = getTunnel(binding.tunnelId);
      return normalizeNodeIds(tunnel?.inNodeIds, tunnel?.inNodeId || null);
    }
    return normalizeNodeIds(binding.nodeIds);
  };

  const formatTime = (timestamp?: number) => {
    if (!timestamp) {
      return "-";
    }
    return new Date(timestamp).toLocaleString("zh-CN");
  };

  const getStatusColor = (status?: string) => {
    if (status === "SUCCESS") {
      return "success";
    }
    if (status === "FAILED") {
      return "danger";
    }
    if (status === "SKIPPED") {
      return "warning";
    }
    return "default";
  };

  const getStatusText = (status?: string) => {
    if (status === "SUCCESS") {
      return "成功";
    }
    if (status === "FAILED") {
      return "失败";
    }
    if (status === "SKIPPED") {
      return "跳过";
    }
    return "未同步";
  };

  const handleSaveSetting = async () => {
    if (setting.enabled === 1) {
      if (!apiToken.trim() && !setting.apiTokenConfigured) {
        toast.error("请输入 Cloudflare API Token");
        return;
      }
      if (!setting.zoneId?.trim()) {
        toast.error("请输入 Cloudflare Zone ID");
        return;
      }
    }

    setSettingLoading(true);
    try {
      const payload = {
        ...setting,
        apiToken: apiToken.trim() || undefined,
        proxied: 0,
      };
      const res = await updateCloudflareDnsSetting(payload);
      if (res.code === 0) {
        toast.success(res.msg || "配置已保存");
        setApiToken("");
        await loadData();
      } else {
        toast.error(res.msg || "配置保存失败");
      }
    } catch (error) {
      toast.error("配置保存失败");
    } finally {
      setSettingLoading(false);
    }
  };

  const handleTestSetting = async () => {
    setTestLoading(true);
    try {
      const res = await testCloudflareDnsSetting({
        apiToken: apiToken.trim() || undefined,
        zoneId: setting.zoneId,
      });
      if (res.code === 0) {
        const zoneName = res.data?.name ? `：${res.data.name}` : "";
        toast.success(`Cloudflare 连接正常${zoneName}`);
      } else {
        toast.error(res.msg || "Cloudflare 连接失败");
      }
    } catch (error) {
      toast.error("Cloudflare 连接失败");
    } finally {
      setTestLoading(false);
    }
  };

  const handleSyncAll = async () => {
    setSyncAllLoading(true);
    try {
      const res = await syncCloudflareDnsAll();
      if (res.code === 0) {
        toast.success(res.data || "同步完成");
      } else {
        toast.error(res.msg || "同步失败");
      }
      await loadData();
    } catch (error) {
      toast.error("同步失败");
    } finally {
      setSyncAllLoading(false);
    }
  };

  const openAddBinding = () => {
    setBindingForm(defaultBindingForm);
    setBindingModalOpen(true);
  };

  const openEditBinding = (binding: CloudflareDnsBinding) => {
    setBindingForm({
      id: binding.id,
      tunnelId: binding.tunnelId,
      domain: binding.domain,
      useTunnelNodes: binding.useTunnelNodes === undefined || binding.useTunnelNodes === 1,
      nodeIds: normalizeNodeIds(binding.nodeIds),
      recordType: binding.recordType || "AUTO",
    });
    setBindingModalOpen(true);
  };

  const handleSaveBinding = async () => {
    if (!bindingForm.tunnelId) {
      toast.error("请选择隧道");
      return;
    }
    if (!bindingForm.domain.trim()) {
      toast.error("请输入域名");
      return;
    }
    if (!bindingForm.useTunnelNodes && bindingForm.nodeIds.length === 0) {
      toast.error("请选择节点");
      return;
    }

    setBindingSubmitLoading(true);
    try {
      const res = await saveCloudflareDnsBinding({
        id: bindingForm.id,
        tunnelId: bindingForm.tunnelId,
        domain: bindingForm.domain,
        useTunnelNodes: bindingForm.useTunnelNodes ? 1 : 0,
        nodeIds: bindingForm.nodeIds,
        recordType: bindingForm.recordType,
      });
      if (res.code === 0) {
        toast.success(res.msg || "DNS 绑定已保存");
        setBindingModalOpen(false);
        await loadData();
      } else {
        toast.error(res.msg || "DNS 绑定保存失败");
      }
    } catch (error) {
      toast.error("DNS 绑定保存失败");
    } finally {
      setBindingSubmitLoading(false);
    }
  };

  const handleDeleteBinding = async (binding: CloudflareDnsBinding) => {
    if (!window.confirm(`确定删除 DNS 绑定 ${binding.domain} 吗？`)) {
      return;
    }
    setBindingActionId(binding.id);
    try {
      const res = await deleteCloudflareDnsBinding(binding.id);
      if (res.code === 0) {
        toast.success(res.data || "DNS 绑定已删除");
        await loadData();
      } else {
        toast.error(res.msg || "DNS 绑定删除失败");
      }
    } catch (error) {
      toast.error("DNS 绑定删除失败");
    } finally {
      setBindingActionId(null);
    }
  };

  const handleSyncBinding = async (binding: CloudflareDnsBinding) => {
    setBindingActionId(binding.id);
    try {
      const res = await syncCloudflareDnsBinding(binding.id);
      if (res.code === 0) {
        toast.success(res.data || "同步完成");
      } else {
        toast.error(res.msg || "同步失败");
      }
      await loadData();
    } catch (error) {
      toast.error("同步失败");
    } finally {
      setBindingActionId(null);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="flex items-center gap-3">
          <Spinner size="sm" />
          <span className="text-default-600">正在加载...</span>
        </div>
      </div>
    );
  }

  return (
    <div className="px-3 lg:px-6 py-8 space-y-5">
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-5">
        <Card className="shadow-sm border border-divider xl:col-span-1">
          <CardHeader className="pb-2">
            <div className="flex items-center justify-between w-full">
              <div>
                <h2 className="text-base font-semibold text-foreground">Cloudflare DNS</h2>
                <p className="text-xs text-default-500">DNS-only / DDNS</p>
              </div>
              <Chip color={setting.enabled === 1 ? "success" : "default"} variant="flat" size="sm">
                {setting.enabled === 1 ? "已启用" : "未启用"}
              </Chip>
            </div>
          </CardHeader>
          <CardBody className="space-y-4">
            <Switch
              isSelected={setting.enabled === 1}
              onValueChange={(checked) => setSetting((prev) => ({ ...prev, enabled: checked ? 1 : 0 }))}
              color="primary"
            >
              <span className="text-sm">{setting.enabled === 1 ? "启用同步" : "关闭同步"}</span>
            </Switch>

            <Input
              label="API Token"
              type="password"
              placeholder={setting.apiTokenConfigured ? "已配置，留空保留原 Token" : "请输入 Cloudflare API Token"}
              value={apiToken}
              onChange={(e) => setApiToken(e.target.value)}
              variant="bordered"
            />

            <Input
              label="Zone ID"
              placeholder="Cloudflare Zone ID"
              value={setting.zoneId || ""}
              onChange={(e) => setSetting((prev) => ({ ...prev, zoneId: e.target.value }))}
              variant="bordered"
            />

            <Input
              label="Zone 名称"
              placeholder="example.com"
              value={setting.zoneName || ""}
              onChange={(e) => setSetting((prev) => ({ ...prev, zoneName: e.target.value }))}
              variant="bordered"
            />

            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-1 2xl:grid-cols-2 gap-3">
              <Input
                label="同步间隔"
                type="number"
                min={60}
                value={setting.syncIntervalSeconds.toString()}
                onChange={(e) => setSetting((prev) => ({ ...prev, syncIntervalSeconds: parseInt(e.target.value, 10) || 120 }))}
                endContent={<span className="text-xs text-default-400">秒</span>}
                variant="bordered"
              />
              <Input
                label="TTL"
                type="number"
                min={1}
                value={setting.ttl.toString()}
                onChange={(e) => setSetting((prev) => ({ ...prev, ttl: parseInt(e.target.value, 10) || 1 }))}
                variant="bordered"
              />
            </div>

            <Select
              label="记录类型"
              selectedKeys={[setting.recordType || "AUTO"]}
              onSelectionChange={(keys) => {
                const selected = Array.from(keys)[0] as string;
                if (selected) {
                  setSetting((prev) => ({ ...prev, recordType: selected }));
                }
              }}
              variant="bordered"
            >
              <SelectItem key="AUTO">自动 A / AAAA</SelectItem>
              <SelectItem key="A">仅 A</SelectItem>
              <SelectItem key="AAAA">仅 AAAA</SelectItem>
            </Select>

            <Switch
              isSelected={setting.autoUpdateNodeIp === 1}
              onValueChange={(checked) => setSetting((prev) => ({ ...prev, autoUpdateNodeIp: checked ? 1 : 0 }))}
              color="primary"
            >
              <span className="text-sm">节点上线自动更新公网 IP</span>
            </Switch>

            <Alert
              color="primary"
              variant="flat"
              title="DNS-only"
              description="本功能只写入 DNS 记录，不开启 Cloudflare 代理。"
            />

            <div className="flex flex-wrap gap-2">
              <Button size="sm" color="primary" onPress={handleSaveSetting} isLoading={settingLoading}>
                保存配置
              </Button>
              <Button size="sm" variant="flat" onPress={handleTestSetting} isLoading={testLoading}>
                测试连接
              </Button>
              <Button size="sm" variant="flat" color="secondary" onPress={handleSyncAll} isLoading={syncAllLoading}>
                全量同步
              </Button>
            </div>

            <Divider />

            <div className="grid grid-cols-1 gap-2 text-xs">
              <div className="flex justify-between gap-2">
                <span className="text-default-500">最近同步</span>
                <span className="text-right">{formatTime(setting.lastSyncAt)}</span>
              </div>
              <div className="flex justify-between gap-2">
                <span className="text-default-500">同步状态</span>
                <Chip size="sm" variant="flat" color={getStatusColor(setting.lastSyncStatus) as any}>
                  {getStatusText(setting.lastSyncStatus)}
                </Chip>
              </div>
              {setting.lastSyncMessage && (
                <div className="text-default-500 break-words">{setting.lastSyncMessage}</div>
              )}
            </div>
          </CardBody>
        </Card>

        <div className="xl:col-span-2 space-y-4">
          <div className="flex justify-end">
            <Button size="sm" color="primary" variant="flat" onPress={openAddBinding}>
              新增绑定
            </Button>
          </div>

          {bindings.length === 0 ? (
            <Card className="shadow-sm border border-divider">
              <CardBody className="text-center py-16">
                <h3 className="text-base font-semibold text-foreground">暂无 DNS 绑定</h3>
              </CardBody>
            </Card>
          ) : (
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
              {bindings.map((binding) => {
                const nodeIds = getBindingNodeIds(binding);
                return (
                  <Card key={binding.id} className="shadow-sm border border-divider">
                    <CardHeader className="pb-2">
                      <div className="flex justify-between items-start w-full gap-3">
                        <div className="min-w-0">
                          <h3 className="font-semibold text-sm text-foreground truncate">{binding.domain}</h3>
                          <p className="text-xs text-default-500 truncate">{getTunnelName(binding.tunnelId)}</p>
                        </div>
                        <Chip size="sm" variant="flat" color={getStatusColor(binding.lastSyncStatus) as any}>
                          {getStatusText(binding.lastSyncStatus)}
                        </Chip>
                      </div>
                    </CardHeader>
                    <CardBody className="pt-0 space-y-3">
                      <div className="grid grid-cols-2 gap-2 text-xs">
                        <div className="p-2 rounded border border-default-200 bg-default-50 dark:bg-default-100/50">
                          <div className="text-default-500 mb-1">节点来源</div>
                          <div className="font-medium">
                            {binding.useTunnelNodes === undefined || binding.useTunnelNodes === 1 ? "跟随隧道入口" : "手动选择"}
                          </div>
                        </div>
                        <div className="p-2 rounded border border-default-200 bg-default-50 dark:bg-default-100/50">
                          <div className="text-default-500 mb-1">记录类型</div>
                          <div className="font-medium">{binding.recordType || "AUTO"}</div>
                        </div>
                      </div>

                      <div className="text-xs">
                        <div className="text-default-500 mb-1">当前节点</div>
                        <div className="flex flex-wrap gap-1">
                          {nodeIds.length > 0 ? nodeIds.map((nodeId) => (
                            <Chip key={nodeId} size="sm" variant="flat">
                              {getNodeName(nodeId)}
                            </Chip>
                          )) : (
                            <Chip size="sm" variant="flat" color="warning">无节点</Chip>
                          )}
                        </div>
                      </div>

                      <div className="text-xs text-default-500">
                        <div>{formatTime(binding.lastSyncAt)}</div>
                        {binding.lastSyncMessage && <div className="break-words mt-1">{binding.lastSyncMessage}</div>}
                      </div>

                      <div className="flex gap-2">
                        <Button
                          size="sm"
                          variant="flat"
                          color="primary"
                          onPress={() => handleSyncBinding(binding)}
                          isLoading={bindingActionId === binding.id}
                          className="flex-1"
                        >
                          同步
                        </Button>
                        <Button size="sm" variant="flat" onPress={() => openEditBinding(binding)} className="flex-1">
                          编辑
                        </Button>
                        <Button
                          size="sm"
                          variant="flat"
                          color="danger"
                          onPress={() => handleDeleteBinding(binding)}
                          isLoading={bindingActionId === binding.id}
                          className="flex-1"
                        >
                          删除
                        </Button>
                      </div>
                    </CardBody>
                  </Card>
                );
              })}
            </div>
          )}
        </div>
      </div>

      <Modal
        isOpen={bindingModalOpen}
        onOpenChange={setBindingModalOpen}
        size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
      >
        <ModalContent>
          {(onClose) => (
            <>
              <ModalHeader>{bindingForm.id ? "编辑 DNS 绑定" : "新增 DNS 绑定"}</ModalHeader>
              <ModalBody>
                <div className="space-y-4">
                  <Input
                    label="域名"
                    placeholder="tunnel.example.com"
                    value={bindingForm.domain}
                    onChange={(e) => setBindingForm((prev) => ({ ...prev, domain: e.target.value }))}
                    variant="bordered"
                  />

                  <Select
                    label="隧道"
                    selectedKeys={bindingForm.tunnelId ? [bindingForm.tunnelId.toString()] : []}
                    onSelectionChange={(keys) => {
                      const selected = Array.from(keys)[0] as string;
                      setBindingForm((prev) => ({ ...prev, tunnelId: selected ? parseInt(selected, 10) : null }));
                    }}
                    variant="bordered"
                  >
                    {tunnels.map((tunnel) => (
                      <SelectItem key={tunnel.id} textValue={tunnel.name}>
                        {tunnel.name}
                      </SelectItem>
                    ))}
                  </Select>

                  <Select
                    label="记录类型"
                    selectedKeys={[bindingForm.recordType]}
                    onSelectionChange={(keys) => {
                      const selected = Array.from(keys)[0] as string;
                      if (selected) {
                        setBindingForm((prev) => ({ ...prev, recordType: selected }));
                      }
                    }}
                    variant="bordered"
                  >
                    <SelectItem key="AUTO">自动 A / AAAA</SelectItem>
                    <SelectItem key="A">仅 A</SelectItem>
                    <SelectItem key="AAAA">仅 AAAA</SelectItem>
                  </Select>

                  <Switch
                    isSelected={bindingForm.useTunnelNodes}
                    onValueChange={(checked) => setBindingForm((prev) => ({ ...prev, useTunnelNodes: checked }))}
                    color="primary"
                  >
                    <span className="text-sm">跟随隧道入口节点</span>
                  </Switch>

                  {!bindingForm.useTunnelNodes && (
                    <Select
                      label="节点"
                      selectionMode="multiple"
                      selectedKeys={nodeIdsToSelectedKeys(bindingForm.nodeIds)}
                      onSelectionChange={(keys) => {
                        const nodeIds = selectedKeysToNodeIds(keys);
                        setBindingForm((prev) => ({ ...prev, nodeIds }));
                      }}
                      variant="bordered"
                    >
                      {nodes.map((node) => (
                        <SelectItem key={node.id} textValue={node.name}>
                          <div className="flex items-center justify-between gap-2">
                            <span>{node.name}</span>
                            <span className="text-xs text-default-500 truncate">{node.serverIp || "-"}</span>
                          </div>
                        </SelectItem>
                      ))}
                    </Select>
                  )}
                </div>
              </ModalBody>
              <ModalFooter>
                <Button variant="light" onPress={onClose}>
                  取消
                </Button>
                <Button color="primary" onPress={handleSaveBinding} isLoading={bindingSubmitLoading}>
                  保存
                </Button>
              </ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>
    </div>
  );
}

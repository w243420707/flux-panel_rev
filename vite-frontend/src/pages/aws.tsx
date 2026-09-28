import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useParams } from "react-router-dom";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Input, Textarea } from "@heroui/input";
import {
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
} from "@heroui/modal";
import { Spinner } from "@heroui/spinner";
import toast from "react-hot-toast";

import * as aws from "@/api/aws";
import { getNodeList } from "@/api";
import { isAdmin } from "@/utils/auth";

type WorkspaceTab = "instances" | "accounts" | "templates" | "sharing" | "logs";
type InstanceAction =
  | aws.AwsActionPayload["action"]
  | "rotate-ip"
  | "check"
  | "failover";
type ResourceEditor = {
  kind: "group" | "proxy";
  id?: number;
  name: string;
  url: string;
  remark: string;
};
type CreateForm = aws.AwsTemplatePayload & {
  templateId?: number;
  clientToken: string;
};
type ActionForm = {
  instance: aws.AwsInstance;
  action: InstanceAction;
  requestId: string;
  field: string;
  value: string;
  strategy: aws.AwsChangeIpPayload["strategy"];
  cidr: string;
  maxAttempts: number;
  releaseOld: boolean;
};
type BatchIpForm = {
  strategy: aws.AwsChangeIpPayload["strategy"];
  cidr: string;
  maxAttempts: number;
  releaseOld: boolean;
};
type ApiResult<T> = { code: number; msg?: string; data: T };

const emptyAccount: aws.AwsAccountPayload = {
  name: "",
  accessKeyId: "",
  secretAccessKey: "",
  regions: "us-east-1",
  region: "us-east-1",
  groupName: "",
  proxy: "",
  remark: "",
  expiresAt: null,
};
const emptyTemplate: aws.AwsTemplatePayload = {
  name: "",
  region: "us-east-1",
  availabilityZone: "",
  instanceType: "t3.micro",
  imageId: "",
  architecture: "x86_64",
  rootVolumeSize: "20",
  userData: "",
  spot: false,
  enableIpv6: false,
  disablePublicIp: false,
  staticIp: false,
  cidr: "",
  subnetId: "",
  securityGroupIds: "",
};
const emptyFailover: aws.AwsFailoverConfig = {
  enabled: false,
  conditions: [],
  maxInstances: 1,
  cron: "0 */10 * * * *",
  telegramEnabled: false,
  nodeIds: [],
  accountSelection: "ordered",
  requiredVcpu: 2,
};
const failoverConditions = [
  { value: "vcpu_zero", label: "账号 vCPU 配额为零" },
  { value: "vcpu_exhausted", label: "账号剩余 vCPU 不足" },
  { value: "spot_interrupted", label: "Spot 中断" },
  { value: "instance_missing", label: "实例不存在" },
  { value: "traffic_limit", label: "累计流量达到上限" },
];
const actionNames: Record<InstanceAction, string> = {
  start: "启动",
  stop: "停止",
  reboot: "重启",
  terminate: "删除",
  modify: "修改配置",
  reinstall: "重装镜像",
  "ipv6-enable": "启用 IPv6",
  "ipv6-assign": "分配 IPv6",
  "ipv6-unassign": "释放 IPv6",
  "rotate-ip": "更换公网 IP",
  check: "查看 APK 检测",
  failover: "替补",
};
const selectClass =
  "min-h-12 w-full rounded-medium border border-default-200 bg-content1 px-3 text-sm";

function unpack<T>(response: ApiResult<T>, fallback: string): T {
  if (response.code !== 0) throw new Error(response.msg || fallback);
  return response.data;
}
function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "操作失败，请稍后重试";
}
function asList<T>(value: unknown): T[] {
  return Array.isArray(value) ? value : [];
}
function dateLabel(value?: string | number | null) {
  if (!value) return "—";
  const date = new Date(
    typeof value === "number" && value < 10_000_000_000 ? value * 1000 : value,
  );
  return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString();
}
function accountExpiryLabel(value?: number | null) {
  if (!value) return "永不过期";
  const days = Math.ceil((value - Date.now()) / 86_400_000);
  return days <= 0 ? "已过期" : `剩余 ${days} 天`;
}
function localDateTime(value?: number | null) {
  if (!value) return "";
  const date = new Date(value);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 16);
}
function uuid() {
  return crypto.randomUUID();
}
function instanceKey(instance: aws.AwsInstance) {
  return [instance.accountId, instance.region, instance.instanceId].join(":");
}
function stateColor(
  state?: string,
): "success" | "warning" | "danger" | "default" {
  if (["running", "SUCCEEDED", "OK"].includes(state || "")) return "success";
  if (
    ["pending", "stopping", "PENDING", "RUNNING", "SUSPECTED_BLOCKED"].includes(
      state || "",
    )
  )
    return "warning";
  if (["terminated", "FAILED", "UNKNOWN"].includes(state || ""))
    return "danger";
  return "default";
}
function gfwLabel(status?: string) {
  return status === "OK"
    ? "APK：正常"
    : status === "SUSPECTED_BLOCKED"
      ? "APK：疑似被墙"
      : status === "NODE_OFFLINE"
        ? "APK：节点离线"
        : "等待 APK 回报";
}

function operationAccepted(operation: aws.AwsOperation) {
  return (
    !!operation.result &&
    typeof operation.result === "object" &&
    (operation.result as Record<string, unknown>).status === "ACCEPTED"
  );
}

function operationLabel(operation: aws.AwsOperation) {
  if (operationAccepted(operation)) return "AWS 已接受";
  return (
    (
      {
        PENDING: "等待执行",
        RUNNING: "执行中",
        SUCCEEDED: "已完成",
        FAILED: "失败",
        UNKNOWN: "结果待确认",
      } as Record<string, string>
    )[operation.status] || operation.status
  );
}
function Check({
  label,
  checked,
  onChange,
  disabled = false,
}: {
  label: string;
  checked?: boolean;
  onChange: (value: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <label className="flex items-center gap-2 text-sm">
      <input
        type="checkbox"
        checked={!!checked}
        disabled={disabled}
        onChange={(event) => onChange(event.target.checked)}
      />
      {label}
    </label>
  );
}
function SelectField({
  label,
  value,
  onChange,
  children,
  disabled,
}: {
  label: string;
  value?: string | number;
  onChange: (value: string) => void;
  children: ReactNode;
  disabled?: boolean;
}) {
  return (
    <label className="grid gap-1 text-xs text-default-500">
      <span>{label}</span>
      <select
        aria-label={label}
        className={selectClass}
        value={value ?? ""}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
      >
        {children}
      </select>
    </label>
  );
}
function NodeChoices({
  nodes,
  selected,
  onChange,
}: {
  nodes: aws.AwsBoundNode[];
  selected: number[];
  onChange: (value: number[]) => void;
}) {
  return (
    <div className="max-h-48 space-y-2 overflow-y-auto rounded-medium border border-default-200 p-3">
      {nodes.length ? (
        nodes.map((node) => (
          <Check
            key={node.id}
            label={
              node.name +
              " · " +
              (node.awsRegion || "") +
              " · " +
              (node.awsInstanceId || "未绑定")
            }
            checked={selected.includes(node.id)}
            onChange={(checked) =>
              onChange(
                checked
                  ? [...selected, node.id]
                  : selected.filter((id) => id !== node.id),
              )
            }
          />
        ))
      ) : (
        <p className="text-sm text-default-500">
          暂无已绑定 AWS 的节点，请先在节点管理中绑定实例。
        </p>
      )}
    </div>
  );
}

function LaunchFields({
  form,
  setForm,
  accounts,
  template = false,
}: {
  form: aws.AwsTemplatePayload;
  setForm: (form: aws.AwsTemplatePayload) => void;
  accounts: aws.AwsAccountSummary[];
  template?: boolean;
}) {
  const field = (key: keyof aws.AwsTemplatePayload, value: unknown) =>
    setForm({ ...form, [key]: value });
  return (
    <div className="space-y-4">
      <Input
        label={template ? "模板名称" : "实例名称"}
        value={form.name}
        onValueChange={(value) => field("name", value)}
        isRequired
      />
      <div className="grid gap-4 sm:grid-cols-2">
        <SelectField
          label={template ? "默认账号（可不指定）" : "AWS 账号"}
          value={form.accountId}
          onChange={(value) => {
            const account = accounts.find((item) => item.id === Number(value));
            setForm({
              ...form,
              accountId: value ? Number(value) : undefined,
              region: account?.region || form.region,
            });
          }}
        >
          <option value="">选择账号</option>
          {accounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.name}
            </option>
          ))}
        </SelectField>
        <Input
          label="区域"
          placeholder="us-east-1"
          value={form.region || ""}
          onValueChange={(value) => field("region", value)}
          isRequired
        />
        <Input
          label="可用区（可选）"
          placeholder="us-east-1a"
          value={form.availabilityZone || ""}
          onValueChange={(value) => field("availabilityZone", value)}
        />
        <Input
          label="实例型号"
          value={form.instanceType || ""}
          onValueChange={(value) => field("instanceType", value)}
          isRequired
        />
        <Input
          label="镜像 AMI ID"
          placeholder="ami-…"
          value={form.imageId || ""}
          onValueChange={(value) => field("imageId", value)}
          isRequired
        />
        <SelectField
          label="架构"
          value={form.architecture || "x86_64"}
          onChange={(value) => field("architecture", value)}
        >
          <option value="x86_64">x86_64</option>
          <option value="arm64">ARM64</option>
        </SelectField>
        <Input
          label="根磁盘大小（GiB）"
          type="number"
          min={1}
          value={form.rootVolumeSize || String(form.diskGiB || 20)}
          onValueChange={(value) => field("rootVolumeSize", value)}
        />
        <Input
          label="SSH 密钥对名称（可选）"
          value={form.keyName || ""}
          onValueChange={(value) => field("keyName", value)}
        />
        <Input
          label="登录密码（可选）"
          type="password"
          autoComplete="new-password"
          value={form.password || ""}
          onValueChange={(value) => field("password", value)}
          description="适用于支持 cloud-init 的 Linux 镜像，请使用强密码。"
        />
        <Input
          label="子网 ID（可选）"
          placeholder="subnet-…"
          value={form.subnetId || ""}
          onValueChange={(value) => field("subnetId", value)}
        />
        <Input
          label="安全组 ID（逗号分隔）"
          placeholder="sg-…,sg-…"
          value={form.securityGroupIds || ""}
          onValueChange={(value) => field("securityGroupIds", value)}
        />
        <Input
          label="目标公网 IPv4 CIDR（可选）"
          placeholder="例如 1.2.0.0/16"
          value={form.cidr || ""}
          onValueChange={(value) => field("cidr", value)}
          description="仅有限次数尝试，不能保证分配到指定网段。"
        />
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <Check
          label="Spot 实例（可能被 AWS 中断）"
          checked={form.spot}
          onChange={(value) => field("spot", value)}
        />
        <Check
          label="启用 IPv6（需要子网支持）"
          checked={form.enableIpv6}
          onChange={(value) => field("enableIpv6", value)}
        />
        <Check
          label="不分配动态公网 IPv4"
          checked={form.disablePublicIp}
          onChange={(value) => field("disablePublicIp", value)}
        />
        <Check
          label="分配静态 Elastic IP"
          checked={form.staticIp}
          onChange={(value) => field("staticIp", value)}
        />
      </div>
      <Textarea
        label="开机脚本 / cloud-init（可选）"
        minRows={5}
        classNames={{ input: "font-mono text-xs" }}
        value={form.userData || ""}
        onValueChange={(value) => field("userData", value)}
      />
      <p className="text-xs text-default-500">
        创建实例、磁盘和公网 IPv4 可能产生 AWS
        费用；面板不会自动放宽安全组或修改共享网络。
      </p>
    </div>
  );
}

export default function AwsPage() {
  const admin = isAdmin();
  const [tab, setTab] = useState<WorkspaceTab>("instances");
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState("");
  const [accounts, setAccounts] = useState<aws.AwsAccountSummary[]>([]);
  const [groups, setGroups] = useState<aws.AwsAccountGroup[]>([]);
  const [proxies, setProxies] = useState<aws.AwsProxy[]>([]);
  const [instances, setInstances] = useState<aws.AwsInstance[]>([]);
  const [nodes, setNodes] = useState<aws.AwsBoundNode[]>([]);
  const [templates, setTemplates] = useState<aws.AwsTemplate[]>([]);
  const [shares, setShares] = useState<aws.AwsShareGroup[]>([]);
  const [failover, setFailover] =
    useState<aws.AwsFailoverConfig>(emptyFailover);
  const [failoverLogs, setFailoverLogs] = useState<aws.AwsFailoverLog[]>([]);
  const [operations, setOperations] = useState<aws.AwsOperation[]>([]);
  const [filter, setFilter] = useState({
    keyword: "",
    accountId: "",
    region: "",
    state: "",
    shareId: "",
  });
  const [groupBy, setGroupBy] = useState("account");
  const [page, setPage] = useState(1);
  const [accountForm, setAccountForm] = useState<aws.AwsAccountPayload | null>(
    null,
  );
  const [resourceForm, setResourceForm] = useState<ResourceEditor | null>(null);
  const [importForm, setImportForm] = useState<{
    text: string;
    groupId?: number;
    region: string;
  } | null>(null);
  const [importResult, setImportResult] = useState<aws.AwsImportResult | null>(
    null,
  );
  const [templateForm, setTemplateForm] =
    useState<aws.AwsTemplatePayload | null>(null);
  const [createForm, setCreateForm] = useState<CreateForm | null>(null);
  const [actionForm, setActionForm] = useState<ActionForm | null>(null);
  const [batchIpForm, setBatchIpForm] = useState<BatchIpForm | null>(null);
  const [selectedInstanceKeys, setSelectedInstanceKeys] = useState<string[]>(
    [],
  );
  const [shareForm, setShareForm] = useState<Partial<aws.AwsShareGroup> | null>(
    null,
  );
  const [quota, setQuota] = useState<{
    name: string;
    data: aws.AwsQuota;
  } | null>(null);
  const [inspection, setInspection] = useState<{
    title: string;
    text: string;
  } | null>(null);
  const mounted = useRef(true);
  const currentAccounts = useRef(accounts);
  currentAccounts.current = accounts;

  const selectedInstances = useMemo(
    () =>
      instances.filter((instance) =>
        selectedInstanceKeys.includes(instanceKey(instance)),
      ),
    [instances, selectedInstanceKeys],
  );

  const accountName = (id?: number) =>
    accounts.find((account) => account.id === id)?.name ||
    String(id || "未知账号");
  const boundNode = useCallback(
    (instance: aws.AwsInstance) =>
      nodes.find(
        (node) =>
          node.awsAccountId === instance.accountId &&
          node.awsRegion === instance.region &&
          node.awsInstanceId === instance.instanceId,
      ),
    [nodes],
  );
  const regions = useMemo(
    () =>
      [
        ...new Set(
          [
            ...instances.map((item) => item.region || ""),
            ...accounts.flatMap((item) =>
              (item.regions || item.region || "").split(","),
            ),
          ].filter(Boolean),
        ),
      ].sort(),
    [accounts, instances],
  );

  const refreshInstances = useCallback(
    async (accountList = currentAccounts.current) => {
      if (!accountList.length) {
        setInstances([]);
        return;
      }
      const responses = await Promise.all(
        accountList.map((account) =>
          aws.listAwsInstances({ accountId: account.id }),
        ),
      );
      const failures = responses.filter((response) => response.code !== 0);
      if (mounted.current)
        setInstances(
          responses.flatMap((response) =>
            response.code === 0 ? asList<aws.AwsInstance>(response.data) : [],
          ),
        );
      if (failures.length)
        toast.error(failures[0].msg || "部分账号实例加载失败");
    },
    [],
  );

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const data = await Promise.all([
        aws.listAwsAccounts(),
        aws.listAwsAccountGroups(),
        aws.listAwsProxies(),
        aws.listAwsTemplates(),
        aws.listAwsShareGroups(),
        aws.getAwsFailoverConfig(),
        getNodeList(),
        aws.listAwsFailoverLogs(),
        aws.listAwsOperations(),
      ]);
      if (!mounted.current) return;
      const accountList = asList<aws.AwsAccountSummary>(
        unpack(data[0], "加载账号失败"),
      );
      setAccounts(accountList);
      setGroups(asList(unpack(data[1], "加载账号组失败")));
      setProxies(asList(unpack(data[2], "加载代理池失败")));
      setTemplates(asList(unpack(data[3], "加载模板失败")));
      setShares(asList(unpack(data[4], "加载分享组失败")));
      const config = unpack(data[5], "加载替补配置失败");
      if (config)
        setFailover({
          ...emptyFailover,
          ...config,
          nodeIds: config.nodeIds || [],
          conditions:
            typeof config.conditions === "string"
              ? JSON.parse(config.conditions)
              : config.conditions || [],
        });
      setNodes(
        asList<aws.AwsBoundNode>(unpack(data[6], "加载节点失败")).filter(
          (node) => node.cloudProvider === "AWS" && !!node.awsInstanceId,
        ),
      );
      setFailoverLogs(asList(unpack(data[7], "加载替补日志失败")));
      setOperations(asList(unpack(data[8], "加载操作记录失败")));
      await refreshInstances(accountList);
    } catch (error) {
      toast.error(errorMessage(error));
    } finally {
      if (mounted.current) setLoading(false);
    }
  }, [refreshInstances]);

  useEffect(() => {
    mounted.current = true;
    if (admin) void refresh();
    return () => {
      mounted.current = false;
    };
  }, [admin, refresh]);
  useEffect(() => {
    setPage(1);
  }, [filter, groupBy]);
  useEffect(() => {
    const active = operations.filter((operation) =>
      ["PENDING", "RUNNING"].includes(operation.status),
    );
    if (!active.length) return;
    const timer = window.setTimeout(async () => {
      try {
        const updates = await Promise.all(
          active.map(async (operation) =>
            unpack(
              await aws.getAwsOperation(
                operation.operationId || operation.id || "",
              ),
              "刷新操作状态失败",
            ),
          ),
        );
        if (!mounted.current) return;
        setOperations((existing) =>
          existing.map(
            (operation) =>
              updates.find(
                (item) =>
                  (item.operationId || item.id) ===
                  (operation.operationId || operation.id),
              ) || operation,
          ),
        );
        const completed = updates.filter(
          (operation) => !["PENDING", "RUNNING"].includes(operation.status),
        );
        if (completed.length) {
          completed.forEach((operation) =>
            operation.status === "SUCCEEDED"
              ? toast.success(
                  operationAccepted(operation)
                    ? "AWS 已接受请求，请稍后刷新确认结果"
                    : "AWS 操作已完成",
                )
              : toast.error(
                  operation.message || "AWS 操作失败，请查看操作记录",
                ),
          );
          await refreshInstances();
        }
      } catch (error) {
        toast.error(errorMessage(error));
      }
    }, 2500);
    return () => window.clearTimeout(timer);
  }, [operations, refreshInstances]);

  const perform = async (key: string, work: () => Promise<void>) => {
    if (!admin) {
      toast.error("仅管理员可操作");
      return;
    }
    if (busy) return;
    setBusy(key);
    try {
      await work();
    } catch (error) {
      toast.error(errorMessage(error));
    } finally {
      setBusy("");
    }
  };
  const acceptOperation = (operation: aws.AwsOperation) => {
    setOperations((existing) => [
      operation,
      ...existing.filter(
        (item) =>
          (item.operationId || item.id) !==
          (operation.operationId || operation.id),
      ),
    ]);
    if (operation.status === "FAILED" || operation.status === "UNKNOWN")
      throw new Error(operation.message || "AWS 操作失败");
    toast.success(
      operation.status === "SUCCEEDED"
        ? operationAccepted(operation)
          ? "AWS 已接受请求，请稍后刷新确认结果"
          : "操作已完成"
        : "操作已提交，完成结果会自动更新",
    );
  };

  const visibleInstances = useMemo(() => {
    const keyword = filter.keyword.trim().toLowerCase();
    const share = shares.find((item) => String(item.id) === filter.shareId);
    return instances
      .filter((instance) => {
        const node = boundNode(instance);
        return (
          (!filter.accountId ||
            String(instance.accountId) === filter.accountId) &&
          (!filter.region || instance.region === filter.region) &&
          (!filter.state ||
            (instance.state || instance.status) === filter.state) &&
          (!filter.shareId ||
            (!!node && !!share?.nodeIds?.includes(node.id))) &&
          (!keyword ||
            [
              instance.instanceId,
              instance.name,
              instance.publicIpv4,
              instance.publicIp,
              instance.ipv6,
              instance.accountName,
            ]
              .join(" ")
              .toLowerCase()
              .includes(keyword))
        );
      })
      .sort((a, b) => {
        const group = (instance: aws.AwsInstance) =>
          groupBy === "region"
            ? instance.region || ""
            : groupBy === "share"
              ? shares
                  .filter((share) =>
                    share.nodeIds?.includes(boundNode(instance)?.id || 0),
                  )
                  .map((share) => share.name)
                  .join(",")
              : groupBy === "account"
                ? String(instance.accountId || "")
                : "";
        return (
          group(a).localeCompare(group(b)) ||
          (a.name || a.instanceId).localeCompare(b.name || b.instanceId)
        );
      });
  }, [instances, filter, shares, groupBy, boundNode]);

  useEffect(() => {
    const available = new Set(instances.map(instanceKey));
    setSelectedInstanceKeys((current) =>
      current.filter((key) => available.has(key)),
    );
  }, [instances]);

  const allVisibleSelected =
    visibleInstances.length > 0 &&
    visibleInstances.every((instance) =>
      selectedInstanceKeys.includes(instanceKey(instance)),
    );

  const toggleInstanceSelection = (instance: aws.AwsInstance) => {
    const key = instanceKey(instance);
    setSelectedInstanceKeys((current) =>
      current.includes(key)
        ? current.filter((value) => value !== key)
        : [...current, key],
    );
  };

  const toggleAllVisibleInstances = () => {
    if (allVisibleSelected) {
      const visibleKeys = new Set(visibleInstances.map(instanceKey));
      setSelectedInstanceKeys((current) =>
        current.filter((key) => !visibleKeys.has(key)),
      );
      return;
    }
    setSelectedInstanceKeys((current) => [
      ...new Set([...current, ...visibleInstances.map(instanceKey)]),
    ]);
  };

  const submitBatchOperations = async (
    targets: aws.AwsInstance[],
    submit: (instance: aws.AwsInstance) => Promise<aws.AwsOperation>,
  ) => {
    const settled = await Promise.allSettled(targets.map(submit));
    const operations = settled.flatMap((result) =>
      result.status === "fulfilled" ? [result.value] : [],
    );
    if (operations.length) {
      setOperations((current) => {
        const ids = new Set(
          operations.map((item) => item.operationId || item.id),
        );
        return [
          ...operations,
          ...current.filter((item) => !ids.has(item.operationId || item.id)),
        ].slice(0, 100);
      });
    }
    const failed = settled.filter(
      (result) =>
        result.status === "rejected" ||
        result.value.status === "FAILED" ||
        result.value.status === "UNKNOWN",
    ).length;
    return { submitted: operations.length - failed, failed };
  };

  const batchStatusCheck = () =>
    perform("batch-status", async () => {
      const targets = selectedInstances;
      if (!targets.length) throw new Error("请先选择实例");
      const settled = await Promise.allSettled(
        targets.map((instance) =>
          aws.checkAwsInstanceGfw({
            accountId: instance.accountId,
            region: instance.region,
            instanceId: instance.instanceId,
          }),
        ),
      );
      const failed = settled.filter((result) => result.status === "rejected").length;
      setSelectedInstanceKeys([]);
      toast.success(
        failed
          ? `已完成 ${targets.length - failed} 个，${failed} 个失败`
          : `已完成 ${targets.length} 个实例状态查询`,
      );
      await refreshInstances();
    });

  const batchTerminate = () =>
    perform("batch-terminate", async () => {
      const targets = selectedInstances;
      if (!targets.length) throw new Error("请先选择实例");
      const preview = targets
        .slice(0, 5)
        .map((item) => `${item.name || "未命名"} (${item.instanceId})`)
        .join("\n");
      const suffix = targets.length > 5 ? `\n以及其他 ${targets.length - 5} 个实例` : "";
      if (
        !window.confirm(
          `删除选中的 ${targets.length} 个 EC2 实例？此操作不可撤销。\n${preview}${suffix}`,
        )
      )
        return;
      const result = await submitBatchOperations(targets, async (instance) =>
        unpack(
          await aws.operateAwsInstance({
            accountId: instance.accountId,
            region: instance.region,
            instanceId: instance.instanceId,
            action: "terminate",
            requestId: uuid(),
          }),
          "提交删除任务失败",
        ),
      );
      setSelectedInstanceKeys([]);
      toast.success(`已提交 ${result.submitted} 个删除任务，失败 ${result.failed} 个`);
    });

  const batchChangeIp = () => {
    if (!selectedInstances.length) {
      toast.error("请先选择实例");
      return;
    }
    setBatchIpForm({
      strategy: "AUTO",
      cidr: "",
      maxAttempts: 5,
      releaseOld: false,
    });
  };

  const executeBatchChangeIp = () =>
    perform("batch-ip", async () => {
      const targets = selectedInstances;
      const form = batchIpForm;
      if (!targets.length || !form) throw new Error("请先选择实例");
      const result = await submitBatchOperations(targets, async (instance) =>
        unpack(
          await aws.changeAwsInstanceIp({
            accountId: instance.accountId,
            region: instance.region,
            instanceId: instance.instanceId,
            strategy: form.strategy,
            cidr: form.cidr || undefined,
            maxAttempts: form.maxAttempts,
            releaseOld: form.releaseOld,
            requestId: uuid(),
          }),
          "提交换 IP 任务失败",
        ),
      );
      setBatchIpForm(null);
      setSelectedInstanceKeys([]);
      toast.success(`已提交 ${result.submitted} 个换 IP 任务，失败 ${result.failed} 个`);
    });

  const pageCount = Math.max(1, Math.ceil(visibleInstances.length / 20));
  const effectivePage = Math.min(page, pageCount);
  const pageInstances = visibleInstances.slice(
    (effectivePage - 1) * 20,
    effectivePage * 20,
  );
  const groupLabel = (instance: aws.AwsInstance) =>
    groupBy === "region"
      ? instance.region || "未知区域"
      : groupBy === "share"
        ? shares
            .filter((share) =>
              share.nodeIds?.includes(boundNode(instance)?.id || 0),
            )
            .map((share) => share.name)
            .join(" / ") || "未分享"
        : groupBy === "account"
          ? accountName(instance.accountId)
          : "全部实例";

  const exportInstances = () => {
    const csvCell = (value: unknown) =>
      '"' +
      String(value ?? "")
        .replace(/^[=+@-]/, "'$&")
        .replace(/"/g, '""') +
      '"';
    const rows = [
      [
        "账号",
        "区域",
        "实例 ID",
        "名称",
        "状态",
        "公网 IPv4",
        "IPv6",
        "型号",
        "vCPU",
        "内存 GiB",
        "带宽",
        "配置时间",
      ],
      ...visibleInstances.map((instance) => [
        accountName(instance.accountId),
        instance.region,
        instance.instanceId,
        instance.name,
        instance.state || instance.status,
        instance.publicIpv4 || instance.publicIp,
        instance.ipv6,
        instance.instanceType,
        instance.vcpu,
        instance.memoryGiB,
        instance.bandwidth,
        dateLabel(
          instance.configuredAt || instance.createdAt || instance.launchTime,
        ),
      ]),
    ];
    const url = URL.createObjectURL(
      new Blob(
        ["\uFEFF" + rows.map((row) => row.map(csvCell).join(",")).join("\r\n")],
        { type: "text/csv;charset=utf-8" },
      ),
    );
    const link = document.createElement("a");
    link.href = url;
    link.download = "aws-ec2-instances.csv";
    link.click();
    URL.revokeObjectURL(url);
  };
  const openAccount = (account?: aws.AwsAccountSummary) => {
    setAccountForm(
      account
        ? {
            ...emptyAccount,
            id: account.id,
            name: account.name,
            // The list value is masked; never put the decrypted key in the form.
            accessKeyId: account.accessKeyId || "",
            region: account.region,
            regions: account.regions || account.region,
            groupId: account.groupId,
            groupName: account.groupName,
            proxyId: account.proxyId,
            remark: account.remark,
            mfaSerial: account.mfaSerialNumber,
            roleArn: account.roleArn,
            roleSessionName: account.roleSessionName,
            expiresAt: account.expiresAt ?? null,
          }
        : { ...emptyAccount },
    );
  };
  const saveAccount = () =>
    perform("account", async () => {
      if (
        !accountForm ||
        !accountForm.name.trim() ||
        !accountForm.accessKeyId.trim() ||
        (!accountForm.id && !accountForm.secretAccessKey.trim())
      )
        throw new Error("请填写账号名称、Access Key 和 Secret Key");
      const region = (accountForm.regions || "").split(",")[0].trim();
      if (!region) throw new Error("请填写至少一个 AWS 区域");
      unpack(
        await aws.saveAwsAccount({
          ...accountForm,
          region,
          name: accountForm.name.trim(),
        }),
        "保存账号失败",
      );
      setAccountForm(null);
      toast.success("AWS 账号已保存");
      await refresh();
    });
  const openAction = (instance: aws.AwsInstance, action: InstanceAction) =>
    setActionForm({
      instance,
      action,
      requestId: uuid(),
      field: "name",
      value: action === "modify" ? instance.name || "" : "",
      strategy: "AUTO",
      cidr: "",
      maxAttempts: 5,
      releaseOld: false,
    });
  const executeAction = () =>
    perform("action", async () => {
      if (!actionForm) return;
      const { instance, action, requestId } = actionForm;
      const base = {
        accountId: instance.accountId,
        region: instance.region,
        instanceId: instance.instanceId,
        requestId,
      };
      if (action === "check") {
        const nodeResponse = unpack(await getNodeList(), "读取 APK 报告失败");
        const latest = asList<aws.AwsBoundNode>(nodeResponse).find(
          (node) =>
            node.awsAccountId === instance.accountId &&
            node.awsRegion === instance.region &&
            node.awsInstanceId === instance.instanceId,
        );
        setInspection({
          title: "APK 检测结果",
          text: latest
            ? gfwLabel(latest.wallMonitorExternalStatus) +
              "\n最近回报：" +
              dateLabel(latest.wallMonitorExternalLastCheckAt) +
              "\n此处只读取 APK 最近回报，不主动探测，也不会触发自动换 IP。"
            : "该实例尚未绑定面板节点，无法获取 APK 检测结果。请先在节点管理中绑定该实例。",
        });
      } else if (action === "failover") {
        const node = boundNode(instance);
        if (!node) throw new Error("该实例尚未绑定面板节点，无法执行替补");
        if (
          !window.confirm(
            "即将为当前节点创建替补 EC2 实例，旧实例会保留且可能产生费用。\n账号：" +
              accountName(instance.accountId) +
              "\n区域：" +
              instance.region +
              "\n实例：" +
              instance.instanceId +
              "\n确认继续吗？",
          )
        )
          return;
        acceptOperation(
          unpack(
            await aws.submitAwsManualFailover({
              nodeId: node.id,
              requestId,
            }),
            "提交替补任务失败",
          ),
        );
      } else if (action === "rotate-ip") {
        acceptOperation(
          unpack(
            await aws.changeAwsInstanceIp({
              ...base,
              strategy: actionForm.strategy,
              cidr: actionForm.cidr || undefined,
              maxAttempts: actionForm.maxAttempts,
              releaseOld: actionForm.releaseOld,
            }),
            "换 IP 请求失败",
          ),
        );
      } else {
        const payload: aws.AwsActionPayload = {
          ...base,
          action,
          clientToken: requestId,
        };
        if (action === "modify") {
          if (
            [
              "rootVolumeSize",
              "rootVolumeIops",
              "rootVolumeThroughput",
            ].includes(actionForm.field)
          ) {
            if (
              !Number.isInteger(Number(actionForm.value)) ||
              Number(actionForm.value) < 1
            )
              throw new Error("请输入正整数");
            Object.assign(payload, {
              [actionForm.field]: Number(actionForm.value),
            });
          } else
            Object.assign(payload, { [actionForm.field]: actionForm.value });
        }
        if (action === "reinstall") {
          if (!actionForm.value.startsWith("ami-"))
            throw new Error("请填写有效的 AMI 镜像 ID");
          payload.imageId = actionForm.value.trim();
        }
        if (action === "ipv6-unassign")
          payload.ipv6Addresses = actionForm.value
            .split(/[\s,]+/)
            .filter(Boolean);
        acceptOperation(
          unpack(await aws.operateAwsInstance(payload), "提交操作失败"),
        );
      }
      setActionForm(null);
    });
  const saveLaunch = (template: boolean) =>
    perform(template ? "template" : "create", async () => {
      const form = template ? templateForm : createForm;
      if (
        !form ||
        !form.name.trim() ||
        !form.region?.trim() ||
        !form.imageId?.trim() ||
        !form.instanceType?.trim()
      )
        throw new Error("请填写名称、区域、镜像 ID 和实例型号");
      const disk = Number(form.rootVolumeSize || form.diskGiB || 20);
      if (!Number.isInteger(disk) || disk < 1)
        throw new Error("根磁盘大小必须是正整数");
      if (template) {
        unpack(
          await aws.saveAwsTemplate({ ...form, diskGiB: disk }),
          "保存模板失败",
        );
        setTemplateForm(null);
        toast.success("模板已保存");
        setTemplates(
          asList(unpack(await aws.listAwsTemplates(), "读取模板失败")),
        );
      } else {
        if (!form.accountId || !createForm) throw new Error("请选择 AWS 账号");
        if (
          !window.confirm(
            "即将创建收费资源。\n账号：" +
              accountName(form.accountId) +
              "\n区域：" +
              form.region +
              "\n实例名称：" +
              form.name +
              "\n型号：" +
              form.instanceType +
              "\n确认创建吗？",
          )
        )
          return;
        acceptOperation(
          unpack(
            await aws.createAwsInstance({
              ...form,
              rootVolumeSize: disk,
              diskGiB: disk,
              securityGroupIds: (form.securityGroupIds || "")
                .split(/[\s,]+/)
                .filter(Boolean),
              clientToken: createForm.clientToken,
              templateId: createForm.templateId,
            }),
            "创建实例失败",
          ),
        );
        setCreateForm(null);
      }
    });
  const saveShare = () =>
    perform("share", async () => {
      if (!shareForm?.name?.trim() || !shareForm.nodeIds?.length)
        throw new Error("请填写分享组名称并选择节点");
      if (shareForm.expiresAt && shareForm.expiresAt <= Date.now())
        throw new Error("到期时间必须晚于当前时间");
      unpack(await aws.saveAwsShareGroup(shareForm), "保存分享组失败");
      setShareForm(null);
      setShares(
        asList(unpack(await aws.listAwsShareGroups(), "读取分享组失败")),
      );
      toast.success("分享组已保存");
    });
  const saveFailover = () =>
    perform("failover", async () => {
      if (
        failover.enabled &&
        (!failover.templateId ||
          !failover.backupGroupId ||
          !failover.nodeIds.length ||
          !failover.conditions.length ||
          !failover.maxInstances ||
          failover.maxInstances < 1)
      )
        throw new Error(
          "启用前请指定节点、模板、备用账号组、触发条件和最大实例数",
        );
      if (
        failover.enabled &&
        failover.conditions.includes("traffic_limit") &&
        (!failover.trafficLimitBytes || failover.trafficLimitBytes <= 0)
      )
        throw new Error("请选择大于零的流量上限");
      if (
        failover.enabled &&
        !window.confirm(
          "启用后面板会按照选定条件自动创建收费 EC2 实例，并更新选定节点的绑定。\n仅影响当前勾选的 " +
            failover.nodeIds.length +
            " 个节点。\n确认保存吗？",
        )
      )
        return;
      unpack(await aws.saveAwsFailoverConfig(failover), "保存替补配置失败");
      setFailover((form) => ({ ...form, telegramBotToken: "" }));
      toast.success("自动替补配置已保存");
    });
  const copyShare = async (share: aws.AwsShareGroup) => {
    try {
      if (!share.accessToken)
        throw new Error("分享链接未生成，请重新保存分享组");
      await navigator.clipboard.writeText(
        window.location.origin +
          "/aws-share/" +
          encodeURIComponent(share.accessToken),
      );
      toast.success("分享链接已复制，请只发给可信任的人");
    } catch (error) {
      toast.error(errorMessage(error));
    }
  };

  if (!admin)
    return <div className="p-6 text-default-500">AWS 管理仅对管理员开放。</div>;
  return (
    <div className="container mx-auto max-w-7xl px-3 py-6 lg:px-6">
      <header className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">AWS EC2 管理</h1>
          <p className="mt-1 text-sm text-default-500">
            账号、实例、分享和自动替补。自动换 IP 只接受 APK 不可达报告。
          </p>
        </div>
        <div className="flex gap-2">
          <Button
            size="sm"
            variant="flat"
            isLoading={loading}
            onPress={() => void refresh()}
          >
            刷新
          </Button>
          <Button size="sm" color="primary" onPress={() => void openAccount()}>
            新增账号
          </Button>
        </div>
      </header>
      <nav
        className="mb-5 flex flex-wrap gap-2 border-b border-default-200 pb-3"
        aria-label="AWS 管理导航"
      >
        {(
          [
            { id: "instances", label: "EC2 实例" },
            { id: "accounts", label: "账号与代理" },
            { id: "templates", label: "创建模板" },
            { id: "sharing", label: "分享与自动替补" },
            { id: "logs", label: "操作记录" },
          ] as const
        ).map((item) => (
          <Button
            key={item.id}
            size="sm"
            variant={tab === item.id ? "solid" : "light"}
            color={tab === item.id ? "primary" : "default"}
            onPress={() => setTab(item.id)}
          >
            {item.label}
          </Button>
        ))}
      </nav>
      {loading && !accounts.length ? (
        <div className="flex min-h-32 items-center justify-center">
          <Spinner label="加载 AWS 数据" />
        </div>
      ) : null}

      {tab === "instances" && (
        <section>
          <div className="mb-4 grid gap-3 rounded-medium border border-default-200 p-4 sm:grid-cols-2 lg:grid-cols-4">
            <Input
              label="搜索实例"
              placeholder="名称、实例 ID、IP"
              value={filter.keyword}
              onValueChange={(keyword) =>
                setFilter((value) => ({ ...value, keyword }))
              }
            />
            <SelectField
              label="账号"
              value={filter.accountId}
              onChange={(accountId) =>
                setFilter((value) => ({ ...value, accountId }))
              }
            >
              <option value="">全部账号</option>
              {accounts.map((account) => (
                <option key={account.id} value={account.id}>
                  {account.name}
                </option>
              ))}
            </SelectField>
            <SelectField
              label="区域"
              value={filter.region}
              onChange={(region) =>
                setFilter((value) => ({ ...value, region }))
              }
            >
              <option value="">全部区域</option>
              {regions.map((region) => (
                <option key={region}>{region}</option>
              ))}
            </SelectField>
            <SelectField
              label="状态"
              value={filter.state}
              onChange={(state) => setFilter((value) => ({ ...value, state }))}
            >
              <option value="">全部状态</option>
              {["running", "stopped", "pending", "stopping", "terminated"].map(
                (state) => (
                  <option key={state}>{state}</option>
                ),
              )}
            </SelectField>
            <SelectField
              label="分享组"
              value={filter.shareId}
              onChange={(shareId) =>
                setFilter((value) => ({ ...value, shareId }))
              }
            >
              <option value="">全部分享组</option>
              {shares.map((share) => (
                <option key={share.id} value={share.id}>
                  {share.name}
                </option>
              ))}
            </SelectField>
            <SelectField label="分组显示" value={groupBy} onChange={setGroupBy}>
              <option value="account">按账号</option>
              <option value="region">按区域</option>
              <option value="share">按分享组</option>
              <option value="none">不分组</option>
            </SelectField>
            <div className="flex items-end gap-2">
              <Button
                variant="flat"
                isLoading={busy === "sync"}
                isDisabled={!!busy}
                onPress={() =>
                  void perform("sync", async () => {
                    const selected = accounts.filter(
                      (item) =>
                        !filter.accountId ||
                        String(item.id) === filter.accountId,
                    );
                    const responses = await Promise.all(
                      selected.map((item) =>
                        aws.syncAwsInstances(
                          item.id,
                          filter.region || undefined,
                        ),
                      ),
                    );
                    responses.forEach((response) =>
                      unpack(response, "同步实例失败"),
                    );
                    await refreshInstances();
                    toast.success("实例已同步");
                  })
                }
              >
                同步实例
              </Button>
              <Button variant="flat" onPress={exportInstances}>
                导出
              </Button>
            </div>
            <div className="flex items-end">
              <Button
                color="primary"
                onPress={() =>
                  setCreateForm({ ...emptyTemplate, clientToken: uuid() })
                }
              >
                创建实例
              </Button>
            </div>
          </div>
          <div className="mb-4 flex flex-wrap items-center gap-2 rounded-medium border border-default-200 px-3 py-2">
            <span className="mr-2 text-sm text-default-500">
              已选 {selectedInstanceKeys.length} 个实例
            </span>
            <Button
              size="sm"
              variant="flat"
              isDisabled={!selectedInstanceKeys.length || !!busy}
              onPress={batchChangeIp}
            >
              批量换 IP
            </Button>
            <Button
              size="sm"
              variant="flat"
              isDisabled={!selectedInstanceKeys.length || !!busy}
              onPress={() => void batchStatusCheck()}
            >
              批量查状态
            </Button>
            <Button
              size="sm"
              color="danger"
              variant="flat"
              isDisabled={!selectedInstanceKeys.length || !!busy}
              onPress={() => void batchTerminate()}
            >
              批量删除
            </Button>
            <Button
              size="sm"
              variant="light"
              isDisabled={!selectedInstanceKeys.length || !!busy}
              onPress={() => setSelectedInstanceKeys([])}
            >
              清空选择
            </Button>
          </div>
          <p className="mb-3 text-sm text-default-500">
            共 {visibleInstances.length} 个实例 · APK
            状态来自绑定节点最近回报；AWS 运行状态不代表大陆可达。
          </p>
          {!visibleInstances.length ? (
            <div className="border-y border-default-200 py-10 text-center text-sm text-default-500">
              没有符合条件的实例。请添加账号、确认区域并同步。
            </div>
          ) : (
            <div className="overflow-x-auto rounded-medium border border-default-200">
              <table className="w-full min-w-[1040px] text-left text-sm">
                <thead className="border-b border-default-200 text-default-500">
                  <tr>
                    <th className="w-12 px-3 py-3">
                      <input
                        type="checkbox"
                        aria-label="选择当前筛选的全部实例"
                        checked={allVisibleSelected}
                        onChange={toggleAllVisibleInstances}
                      />
                    </th>
                    {[
                      "实例",
                      "状态 / APK",
                      "账号 / 区域",
                      "IP 地址",
                      "配置",
                      "操作",
                    ].map((label) => (
                      <th className="px-3 py-3" key={label}>
                        {label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-default-200">
                  {pageInstances.map((instance, index) => (
                    <InstanceRows
                      key={instanceKey(instance)}
                      instance={instance}
                      accountName={accountName(instance.accountId)}
                      node={boundNode(instance)}
                      heading={
                        groupBy !== "none" &&
                        (index === 0 ||
                          groupLabel(instance) !==
                            groupLabel(pageInstances[index - 1]))
                          ? groupLabel(instance)
                          : undefined
                      }
                      disabled={!!busy}
                      selected={selectedInstanceKeys.includes(instanceKey(instance))}
                      onSelectedChange={() => toggleInstanceSelection(instance)}
                      onAction={(action) => openAction(instance, action)}
                    />
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <div className="mt-4 flex items-center justify-between text-sm">
            <span>
              第 {effectivePage} / {pageCount} 页，每页 20 条
            </span>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="flat"
                isDisabled={effectivePage <= 1}
                onPress={() => setPage(effectivePage - 1)}
              >
                上一页
              </Button>
              <Button
                size="sm"
                variant="flat"
                isDisabled={effectivePage >= pageCount}
                onPress={() => setPage(effectivePage + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        </section>
      )}

      {tab === "accounts" && (
        <section className="space-y-6">
          <div className="flex flex-wrap justify-end gap-2">
            <Button
              variant="flat"
              onPress={() => {
                setImportForm({ text: "", region: "us-east-1" });
                setImportResult(null);
              }}
            >
              批量导入
            </Button>
            <Button
              variant="flat"
              onPress={() =>
                setResourceForm({
                  kind: "group",
                  name: "",
                  url: "",
                  remark: "",
                })
              }
            >
              新增账号组
            </Button>
            <Button
              variant="flat"
              onPress={() =>
                setResourceForm({
                  kind: "proxy",
                  name: "",
                  url: "",
                  remark: "",
                })
              }
            >
              新增代理
            </Button>
          </div>
          <div className="divide-y divide-default-200 border-y border-default-200">
            {accounts.length ? (
              accounts.map((account) => (
                <div
                  key={account.id}
                  className="flex flex-wrap items-center justify-between gap-3 py-4"
                >
                  <div>
                    <div className="flex items-center gap-2">
                      <h2 className="font-semibold">{account.name}</h2>
                      <Chip
                        size="sm"
                        color={account.enabled === false ? "danger" : "success"}
                        variant="flat"
                      >
                        {account.enabled === false ? "已禁用" : "正常"}
                      </Chip>
                      <Chip size="sm" variant="flat">
                        {groups.find((group) => group.id === account.groupId)
                          ?.name ||
                          account.groupName ||
                          "未分组"}
                      </Chip>
                      <Chip
                        size="sm"
                        color={
                          account.expiresAt && account.expiresAt <= Date.now()
                            ? "danger"
                            : "default"
                        }
                        variant="flat"
                      >
                        {accountExpiryLabel(account.expiresAt)}
                      </Chip>
                    </div>
                    <p className="mt-2 text-xs text-default-500">
                      {account.accessKeyId || "密钥已安全保存"} ·{" "}
                      {account.regions || account.region} · 代理：
                      {account.proxyConfigured || account.proxyId
                        ? "已配置"
                        : "无"}{" "}
                      · MFA：{account.mfaConfigured ? "已配置" : "无"}
                    </p>
                    {account.remark && (
                      <p className="mt-1 text-xs text-default-500">
                        {account.remark}
                      </p>
                    )}
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <Button
                      size="sm"
                      variant="flat"
                      isLoading={busy === "test:" + account.id}
                      isDisabled={!!busy || account.enabled === false}
                      onPress={() =>
                        void perform("test:" + account.id, async () => {
                          const data = unpack(
                            await aws.testAwsAccount(account.id),
                            "测试失败",
                          );
                          if (!data.ok)
                            throw new Error(data.message || "AWS 账号测试失败");
                          toast.success(
                            "连接成功，STS 身份验证通过" +
                              (data.region ? "（" + data.region + "）" : ""),
                          );
                        })
                      }
                    >
                      测试
                    </Button>
                    <Button
                      size="sm"
                      variant="flat"
                      isDisabled={!!busy || account.enabled === false}
                      onPress={() =>
                        void perform("quota", async () =>
                          setQuota({
                            name: account.name,
                            data: unpack(
                              await aws.getAwsAccountQuota(account.id),
                              "查询配额失败",
                            ),
                          }),
                        )
                      }
                    >
                      vCPU 配额
                    </Button>
                    <Button
                      size="sm"
                      variant="flat"
                      color={account.enabled === false ? "success" : "warning"}
                      isDisabled={!!busy}
                      onPress={() =>
                        void perform("status:" + account.id, async () => {
                          const nextEnabled = account.enabled === false;
                          if (
                            !window.confirm(
                              (nextEnabled ? "启用" : "禁用") +
                                " AWS 账号「" +
                                account.name +
                                "」？" +
                                (nextEnabled
                                  ? ""
                                  : "禁用后不会删除 AWS 资源，但不会再用于同步、创建、换 IP或替补。"),
                            )
                          )
                            return;
                          unpack(
                            await aws.setAwsAccountEnabled(account.id, nextEnabled),
                            "更新账号状态失败",
                          );
                          await refresh();
                          toast.success(nextEnabled ? "账号已启用" : "账号已禁用");
                        })
                      }
                    >
                      {account.enabled === false ? "启用" : "禁用"}
                    </Button>
                    <Button
                      size="sm"
                      variant="flat"
                      onPress={() => void openAccount(account)}
                    >
                      编辑
                    </Button>
                    <Button
                      size="sm"
                      variant="flat"
                      color="danger"
                      isDisabled={!!busy}
                      onPress={() => {
                        if (
                          window.confirm(
                            "删除账号「" +
                              account.name +
                              "」？此操作不删除 AWS 资源。",
                          )
                        )
                          void perform("delete-account", async () => {
                            unpack(
                              await aws.deleteAwsAccount(account.id),
                              "删除账号失败",
                            );
                            await refresh();
                          });
                      }}
                    >
                      删除
                    </Button>
                  </div>
                </div>
              ))
            ) : (
              <p className="py-10 text-center text-default-500">
                暂无 AWS 账号
              </p>
            )}
          </div>
          <div className="grid gap-6 lg:grid-cols-2">
            <div>
              <h2 className="mb-3 font-semibold">账号组</h2>
              {groups.map((group) => (
                <div
                  className="flex items-center justify-between border-b border-default-200 py-3"
                  key={group.id}
                >
                  <div>
                    <p>{group.name}</p>
                    <p className="text-xs text-default-500">
                      {group.remark || "—"}
                    </p>
                  </div>
                  <div className="flex gap-1">
                    <Button
                      size="sm"
                      variant="light"
                      onPress={() =>
                        setResourceForm({
                          kind: "group",
                          ...group,
                          url: "",
                          remark: group.remark || "",
                        })
                      }
                    >
                      编辑
                    </Button>
                    <Button
                      size="sm"
                      variant="light"
                      color="danger"
                      onPress={() => {
                        if (
                          window.confirm("删除账号组「" + group.name + "」？")
                        )
                          void perform("delete-group", async () => {
                            unpack(
                              await aws.deleteAwsAccountGroup(group.id),
                              "删除账号组失败",
                            );
                            await refresh();
                          });
                      }}
                    >
                      删除
                    </Button>
                  </div>
                </div>
              ))}
            </div>
            <div>
              <h2 className="mb-3 font-semibold">代理池</h2>
              {proxies.map((proxy) => (
                <div
                  className="flex items-center justify-between border-b border-default-200 py-3"
                  key={proxy.id}
                >
                  <div>
                    <p>{proxy.name}</p>
                    <p className="break-all text-xs text-default-500">
                      {proxy.endpoint || "已加密保存"}
                    </p>
                  </div>
                  <div className="flex gap-1">
                    <Button
                      size="sm"
                      variant="light"
                      onPress={() =>
                        setResourceForm({
                          kind: "proxy",
                          id: proxy.id,
                          name: proxy.name,
                          remark: proxy.remark || "",
                          url: "",
                        })
                      }
                    >
                      编辑
                    </Button>
                    <Button
                      size="sm"
                      variant="light"
                      color="danger"
                      onPress={() => {
                        if (window.confirm("删除代理「" + proxy.name + "」？"))
                          void perform("delete-proxy", async () => {
                            unpack(
                              await aws.deleteAwsProxy(proxy.id),
                              "删除代理失败",
                            );
                            await refresh();
                          });
                      }}
                    >
                      删除
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </section>
      )}

      {tab === "templates" && (
        <section>
          <div className="mb-4 flex justify-end">
            <Button
              color="primary"
              onPress={() => setTemplateForm({ ...emptyTemplate })}
            >
              新增模板
            </Button>
          </div>
          <div className="divide-y divide-default-200 border-y border-default-200">
            {templates.length ? (
              templates.map((template) => (
                <div
                  className="flex flex-wrap items-center justify-between gap-3 py-4"
                  key={template.id}
                >
                  <div>
                    <h2 className="font-semibold">{template.name}</h2>
                    <p className="mt-1 text-sm text-default-500">
                      {template.region} · {template.instanceType} ·{" "}
                      {template.architecture} ·{" "}
                      {template.rootVolumeSize || template.diskGiB} GiB
                    </p>
                    <p className="text-xs text-default-500">
                      {template.imageId} {template.spot ? " · Spot" : ""}{" "}
                      {template.staticIp ? " · Elastic IP" : ""}
                    </p>
                  </div>
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="flat"
                      color="primary"
                      onPress={() => {
                        setCreateForm({
                          ...template,
                          id: undefined,
                          templateId: template.id,
                          clientToken: uuid(),
                        });
                      }}
                    >
                      使用模板
                    </Button>
                    <Button
                      size="sm"
                      variant="flat"
                      onPress={() => setTemplateForm({ ...template })}
                    >
                      编辑
                    </Button>
                    <Button
                      size="sm"
                      variant="flat"
                      color="danger"
                      onPress={() => {
                        if (
                          window.confirm("删除模板「" + template.name + "」？")
                        )
                          void perform("delete-template", async () => {
                            unpack(
                              await aws.deleteAwsTemplate(template.id),
                              "删除模板失败",
                            );
                            setTemplates(
                              asList(
                                unpack(
                                  await aws.listAwsTemplates(),
                                  "读取模板失败",
                                ),
                              ),
                            );
                          });
                      }}
                    >
                      删除
                    </Button>
                  </div>
                </div>
              ))
            ) : (
              <p className="py-10 text-center text-default-500">暂无创建模板</p>
            )}
          </div>
        </section>
      )}

      {tab === "sharing" && (
        <section className="space-y-8">
          <div>
            <div className="mb-3 flex items-center justify-between">
              <h2 className="font-semibold">分享组</h2>
              <Button
                size="sm"
                color="primary"
                onPress={() =>
                  setShareForm({
                    name: "",
                    permissions: "read",
                    nodeIds: [],
                    expiresAt: null,
                  })
                }
              >
                新增分享组
              </Button>
            </div>
            <p className="mb-3 text-xs text-default-500">
              链接持有者可查看组内节点；「允许操作」仅允许启动、停止、重启，不允许创建、删除、改配置或查看密钥。
            </p>
            <div className="divide-y divide-default-200 border-y border-default-200">
              {shares.map((share) => (
                <div
                  className="flex flex-wrap items-center justify-between gap-3 py-4"
                  key={share.id}
                >
                  <div>
                    <p className="font-medium">{share.name}</p>
                    <p className="text-xs text-default-500">
                      {share.nodeIds?.length ?? share.nodeCount ?? 0} 个节点 ·{" "}
                      {share.permissions === "operate"
                        ? "允许启停重启"
                        : "只读"}{" "}
                      · 到期：
                      {share.expiresAt
                        ? dateLabel(share.expiresAt)
                        : "永不过期"}
                    </p>
                  </div>
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="flat"
                      onPress={() => void copyShare(share)}
                    >
                      复制链接
                    </Button>
                    <Button
                      size="sm"
                      variant="flat"
                      onPress={() =>
                        setShareForm({ ...share, nodeIds: share.nodeIds || [] })
                      }
                    >
                      编辑
                    </Button>
                    <Button
                      size="sm"
                      variant="flat"
                      color="danger"
                      onPress={() => {
                        if (
                          window.confirm(
                            "删除分享组后原链接立即失效，确认删除「" +
                              share.name +
                              "」？",
                          )
                        )
                          void perform("delete-share", async () => {
                            unpack(
                              await aws.deleteAwsShareGroup(share.id),
                              "删除分享组失败",
                            );
                            setShares(
                              asList(
                                unpack(
                                  await aws.listAwsShareGroups(),
                                  "读取分享组失败",
                                ),
                              ),
                            );
                          });
                      }}
                    >
                      删除
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          </div>
          <div className="space-y-4 rounded-medium border border-default-200 p-4">
            <div className="flex items-center justify-between">
              <div>
                <h2 className="font-semibold">自动替补</h2>
                <p className="mt-1 text-xs text-default-500">
                  默认关闭。触发后创建新实例并更新节点绑定；失败原因保留在操作记录中。
                </p>
              </div>
              <Button
                size="sm"
                color="primary"
                isLoading={busy === "failover"}
                isDisabled={!!busy}
                onPress={() => void saveFailover()}
              >
                保存
              </Button>
            </div>
            <Check
              label="启用自动替补（可能自动产生 AWS 费用）"
              checked={failover.enabled}
              onChange={(enabled) =>
                setFailover((value) => ({ ...value, enabled }))
              }
            />
            <div className="grid gap-4 sm:grid-cols-2">
              <SelectField
                label="备用账号组"
                value={failover.backupGroupId}
                onChange={(value) =>
                  setFailover((form) => ({
                    ...form,
                    backupGroupId: value ? Number(value) : undefined,
                  }))
                }
              >
                <option value="">选择账号组</option>
                {groups.map((group) => (
                  <option key={group.id} value={group.id}>
                    {group.name}
                  </option>
                ))}
              </SelectField>
              <SelectField
                label="替补创建模板"
                value={failover.templateId}
                onChange={(value) =>
                  setFailover((form) => ({
                    ...form,
                    templateId: value ? Number(value) : undefined,
                  }))
                }
              >
                <option value="">选择模板</option>
                {templates.map((template) => (
                  <option key={template.id} value={template.id}>
                    {template.name}
                  </option>
                ))}
              </SelectField>
              <SelectField
                label="账号选择顺序"
                value={failover.accountSelection}
                onChange={(value) =>
                  setFailover((form) => ({
                    ...form,
                    accountSelection: value as "ordered" | "random",
                  }))
                }
              >
                <option value="ordered">按账号顺序</option>
                <option value="random">随机选择</option>
              </SelectField>
              <Input
                label="最大替补实例数"
                type="number"
                min={1}
                value={String(failover.maxInstances ?? 1)}
                onValueChange={(value) =>
                  setFailover((form) => ({
                    ...form,
                    maxInstances: Number(value),
                  }))
                }
              />
              <Input
                label="所需 vCPU"
                type="number"
                min={1}
                value={String(failover.requiredVcpu ?? 2)}
                onValueChange={(value) =>
                  setFailover((form) => ({
                    ...form,
                    requiredVcpu: Number(value),
                  }))
                }
              />
              <Input
                label="CRON 检查周期（秒 分 时 日 月 周）"
                value={failover.cron || ""}
                onValueChange={(cron) =>
                  setFailover((form) => ({ ...form, cron }))
                }
                description="例如 0 */10 * * * * 表示每 10 分钟检查。"
              />
            </div>
            <div>
              <p className="mb-2 text-sm">触发条件（选中任一条件满足时替补）</p>
              <div className="grid gap-3 sm:grid-cols-2">
                {failoverConditions.map((condition) => (
                  <Check
                    key={condition.value}
                    label={condition.label}
                    checked={failover.conditions.includes(condition.value)}
                    onChange={(checked) =>
                      setFailover((form) => ({
                        ...form,
                        conditions: checked
                          ? [...form.conditions, condition.value]
                          : form.conditions.filter(
                              (value) => value !== condition.value,
                            ),
                      }))
                    }
                  />
                ))}
              </div>
            </div>
            {failover.conditions.includes("traffic_limit") && (
              <Input
                label="流量上限（GiB，上传 + 下载）"
                type="number"
                min={1}
                value={
                  failover.trafficLimitBytes
                    ? String(failover.trafficLimitBytes / 1073741824)
                    : ""
                }
                onValueChange={(value) =>
                  setFailover((form) => ({
                    ...form,
                    trafficLimitBytes: Math.round(Number(value) * 1073741824),
                  }))
                }
              />
            )}
            <div>
              <p className="mb-2 text-sm">生效节点（必须明确选择）</p>
              <NodeChoices
                nodes={nodes}
                selected={failover.nodeIds}
                onChange={(nodeIds) =>
                  setFailover((form) => ({ ...form, nodeIds }))
                }
              />
            </div>
            <Check
              label="发送 Telegram 通知（可选）"
              checked={failover.telegramEnabled}
              onChange={(telegramEnabled) =>
                setFailover((form) => ({ ...form, telegramEnabled }))
              }
            />
            {failover.telegramEnabled && (
              <div className="grid gap-4 sm:grid-cols-2">
                <Input
                  label="Bot Token"
                  type="password"
                  autoComplete="new-password"
                  placeholder="留空保持已保存的 Token"
                  value={failover.telegramBotToken || ""}
                  onValueChange={(telegramBotToken) =>
                    setFailover((form) => ({ ...form, telegramBotToken }))
                  }
                />
                <Input
                  label="Chat ID"
                  value={failover.telegramChatId || ""}
                  onValueChange={(telegramChatId) =>
                    setFailover((form) => ({ ...form, telegramChatId }))
                  }
                />
              </div>
            )}
          </div>
        </section>
      )}

      {tab === "logs" && (
        <section className="space-y-6">
          <div>
            <h2 className="mb-3 font-semibold">AWS 操作记录</h2>
            <div className="overflow-x-auto rounded-medium border border-default-200">
              <table className="w-full min-w-[720px] text-left text-sm">
                <thead>
                  <tr>
                    {["时间", "目标", "操作", "状态", "结果"].map((item) => (
                      <th key={item} className="p-3">
                        {item}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-default-200">
                  {operations.map((operation) => (
                    <tr key={operation.operationId || operation.id}>
                      <td className="p-3">
                        {dateLabel(
                          operation.createdAt ||
                            operation.createdTime ||
                            operation.updatedTime,
                        )}
                      </td>
                      <td className="p-3">
                        <p>
                          {accountName(operation.accountId)} ·{" "}
                          {operation.region}
                        </p>
                        <p className="font-mono text-xs">
                          {operation.instanceId || "创建实例"}
                        </p>
                      </td>
                      <td className="p-3">{operation.action || "EC2 操作"}</td>
                      <td className="p-3">
                        <Chip
                          size="sm"
                          variant="flat"
                          color={stateColor(operation.status)}
                        >
                          {operationLabel(operation)}
                        </Chip>
                      </td>
                      <td className="max-w-sm break-words p-3">
                        {operation.message ||
                          (operation.status === "SUCCEEDED"
                            ? operationAccepted(operation)
                              ? "请求已接受，等待 AWS 完成"
                              : "已完成"
                            : "等待执行结果")}
                        {operation.status === "UNKNOWN" && (
                          <Button
                            className="mt-2"
                            size="sm"
                            variant="flat"
                            color="warning"
                            isDisabled={!!busy}
                            onPress={() => {
                              if (
                                !window.confirm(
                                  "请先在 AWS 控制台核实该操作和实例状态。\n账号：" +
                                    accountName(operation.accountId) +
                                    "\n区域：" +
                                    operation.region +
                                    "\n实例：" +
                                    (operation.instanceId || "创建实例") +
                                    "\n确认已经人工核实，并解除此实例的操作锁？",
                                )
                              )
                                return;
                              void perform("acknowledge", async () => {
                                unpack(
                                  await aws.acknowledgeAwsOperation(
                                    operation.operationId || operation.id || "",
                                  ),
                                  "确认失败",
                                );
                                setOperations(
                                  asList(
                                    unpack(
                                      await aws.listAwsOperations(),
                                      "刷新记录失败",
                                    ),
                                  ),
                                );
                                toast.success("已标记人工核实");
                              });
                            }}
                          >
                            我已核实 AWS 状态
                          </Button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
          <div>
            <h2 className="mb-3 font-semibold">自动替补记录</h2>
            <div className="divide-y divide-default-200 border-y border-default-200">
              {failoverLogs.length ? (
                failoverLogs.map((log) => (
                  <div className="py-3" key={log.id}>
                    <div className="flex flex-wrap gap-3">
                      <span>{log.nodeName || "节点 " + log.nodeId}</span>
                      <Chip
                        size="sm"
                        variant="flat"
                        color={stateColor(log.status)}
                      >
                        {log.status || "未知"}
                      </Chip>
                      <span className="text-xs text-default-500">
                        {dateLabel(log.createdTime)}
                      </span>
                    </div>
                    <p className="mt-2 text-sm">
                      {log.message ||
                        log.result ||
                        log.reason ||
                        log.condition ||
                        "—"}
                    </p>
                    <p className="mt-1 font-mono text-xs text-default-500">
                      {log.oldInstanceId || "—"} →{" "}
                      {log.newInstanceId || "未创建"}
                    </p>
                  </div>
                ))
              ) : (
                <p className="py-8 text-center text-default-500">
                  暂无替补记录
                </p>
              )}
            </div>
          </div>
        </section>
      )}

      <Modal
        isOpen={!!accountForm}
        onClose={() => !busy && setAccountForm(null)}
        size="3xl"
        scrollBehavior="inside"
        isDismissable={!busy}
      >
        <ModalContent>
          <ModalHeader>
            {accountForm?.id ? "编辑 AWS 账号" : "新增 AWS 账号"}
          </ModalHeader>
          <ModalBody>
            {accountForm && (
              <div className="space-y-4">
                <Input
                  label="账号名称"
                  value={accountForm.name}
                  onValueChange={(name) =>
                    setAccountForm({ ...accountForm, name })
                  }
                />
                <Input
                  label="Access Key ID"
                  value={accountForm.accessKeyId}
                  onValueChange={(accessKeyId) =>
                    setAccountForm({ ...accountForm, accessKeyId })
                  }
                />
                <Input
                  label="Secret Access Key"
                  type="password"
                  autoComplete="new-password"
                  placeholder={
                    accountForm.id ? "留空保持原值" : "请输入 Secret Key"
                  }
                  value={accountForm.secretAccessKey}
                  onValueChange={(secretAccessKey) =>
                    setAccountForm({ ...accountForm, secretAccessKey })
                  }
                />
                <Input
                  label="Session Token（可选）"
                  type="password"
                  autoComplete="new-password"
                  placeholder={
                    accountForm.id ? "留空保持原值" : "临时凭据 Token"
                  }
                  value={accountForm.sessionToken || ""}
                  onValueChange={(sessionToken) =>
                    setAccountForm({ ...accountForm, sessionToken })
                  }
                />
                <div className="grid gap-4 sm:grid-cols-2">
                  <Input
                    label="MFA 设备 ARN（可选）"
                    value={accountForm.mfaSerial || ""}
                    onValueChange={(mfaSerial) =>
                      setAccountForm({ ...accountForm, mfaSerial })
                    }
                  />
                  <Input
                    label="MFA TOTP 密钥（可选）"
                    type="password"
                    placeholder={
                      accountForm.id ? "留空保持原值" : "Base32 密钥"
                    }
                    value={accountForm.mfaSecret || ""}
                    onValueChange={(mfaSecret) =>
                      setAccountForm({ ...accountForm, mfaSecret })
                    }
                  />
                  <SelectField
                    label="账号组"
                    value={accountForm.groupId}
                    onChange={(value) =>
                      setAccountForm({
                        ...accountForm,
                        groupId: value ? Number(value) : undefined,
                        groupName:
                          groups.find((group) => group.id === Number(value))
                            ?.name || "",
                      })
                    }
                  >
                    <option value="">不分组</option>
                    {groups.map((group) => (
                      <option key={group.id} value={group.id}>
                        {group.name}
                      </option>
                    ))}
                  </SelectField>
                  <SelectField
                    label="代理池"
                    value={accountForm.proxyId}
                    onChange={(value) =>
                      setAccountForm({
                        ...accountForm,
                        proxyId: value ? Number(value) : undefined,
                      })
                    }
                  >
                    <option value="">不使用代理池</option>
                    {proxies.map((proxy) => (
                      <option key={proxy.id} value={proxy.id}>
                        {proxy.name}
                      </option>
                    ))}
                  </SelectField>
                </div>
                <Input
                  label="账号独立代理（可选）"
                  type="password"
                  autoComplete="new-password"
                  isDisabled={!!accountForm.proxyId}
                  placeholder={
                    accountForm.id
                      ? "留空保持原值"
                      : "http://user:pass@host:port 或 socks5://host:port"
                  }
                  value={accountForm.proxy || ""}
                  onValueChange={(proxy) =>
                    setAccountForm({ ...accountForm, proxy })
                  }
                />
                <Input
                  label="启用区域（逗号分隔，首个为默认区域）"
                  value={accountForm.regions || ""}
                  onValueChange={(regions) =>
                    setAccountForm({ ...accountForm, regions })
                  }
                  placeholder="us-east-1,ap-southeast-1"
                />
                <Input
                  label="账号有效期（留空永不过期）"
                  type="datetime-local"
                  value={localDateTime(accountForm.expiresAt)}
                  onValueChange={(value) =>
                    setAccountForm({
                      ...accountForm,
                      expiresAt: value ? new Date(value).getTime() : null,
                    })
                  }
                  description="到期后账号会自动按禁用处理，不会删除 AWS 资源。"
                />
                <details className="rounded-medium border border-default-200 p-3">
                  <summary className="cursor-pointer text-sm">
                    角色与清除选项
                  </summary>
                  <div className="mt-3 space-y-3">
                    <Input
                      label="Role ARN（可选）"
                      value={accountForm.roleArn || ""}
                      onValueChange={(roleArn) =>
                        setAccountForm({ ...accountForm, roleArn })
                      }
                    />
                    <Input
                      label="Role External ID（可选）"
                      type="password"
                      placeholder="留空保持原值"
                      value={accountForm.roleExternalId || ""}
                      onValueChange={(roleExternalId) =>
                        setAccountForm({ ...accountForm, roleExternalId })
                      }
                    />
                    <Input
                      label="Role Session Name（可选）"
                      value={accountForm.roleSessionName || ""}
                      onValueChange={(roleSessionName) =>
                        setAccountForm({ ...accountForm, roleSessionName })
                      }
                    />
                    {accountForm.id && (
                      <>
                        <Check
                          label="清除已保存的 Session Token"
                          checked={accountForm.clearSessionToken}
                          onChange={(clearSessionToken) =>
                            setAccountForm({
                              ...accountForm,
                              clearSessionToken,
                            })
                          }
                        />
                        <Check
                          label="清除 MFA 设置"
                          checked={accountForm.clearMfa}
                          onChange={(clearMfa) =>
                            setAccountForm({ ...accountForm, clearMfa })
                          }
                        />
                        <Check
                          label="清除独立代理"
                          checked={accountForm.clearProxy}
                          onChange={(clearProxy) =>
                            setAccountForm({ ...accountForm, clearProxy })
                          }
                        />
                      </>
                    )}
                  </div>
                </details>
                <Textarea
                  label="备注"
                  value={accountForm.remark || ""}
                  onValueChange={(remark) =>
                    setAccountForm({ ...accountForm, remark })
                  }
                />
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button
              variant="light"
              isDisabled={!!busy}
              onPress={() => setAccountForm(null)}
            >
              取消
            </Button>
            <Button
              color="primary"
              isLoading={busy === "account"}
              isDisabled={!!busy}
              onPress={() => void saveAccount()}
            >
              保存
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={!!resourceForm}
        onClose={() => !busy && setResourceForm(null)}
      >
        <ModalContent>
          <ModalHeader>
            {resourceForm?.id ? "编辑" : "新增"}
            {resourceForm?.kind === "group" ? "账号组" : "代理"}
          </ModalHeader>
          <ModalBody>
            {resourceForm && (
              <div className="space-y-4">
                <Input
                  label="名称"
                  value={resourceForm.name}
                  onValueChange={(name) =>
                    setResourceForm({ ...resourceForm, name })
                  }
                />
                {resourceForm.kind === "proxy" && (
                  <Input
                    label="代理地址（含认证信息）"
                    type="password"
                    autoComplete="new-password"
                    placeholder={
                      resourceForm.id
                        ? "留空保持原值"
                        : "http://user:pass@host:port 或 socks5://host:port"
                    }
                    value={resourceForm.url}
                    onValueChange={(url) =>
                      setResourceForm({ ...resourceForm, url })
                    }
                  />
                )}
                <Textarea
                  label="备注"
                  value={resourceForm.remark}
                  onValueChange={(remark) =>
                    setResourceForm({ ...resourceForm, remark })
                  }
                />
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setResourceForm(null)}>
              取消
            </Button>
            <Button
              color="primary"
              isLoading={busy === "resource"}
              isDisabled={!!busy}
              onPress={() =>
                void perform("resource", async () => {
                  if (!resourceForm?.name.trim()) throw new Error("请输入名称");
                  if (
                    resourceForm.kind === "proxy" &&
                    !resourceForm.id &&
                    !resourceForm.url.trim()
                  )
                    throw new Error("请输入代理地址");
                  unpack(
                    await (resourceForm.kind === "group"
                      ? aws.saveAwsAccountGroup(resourceForm)
                      : aws.saveAwsProxy(resourceForm)),
                    "保存失败",
                  );
                  setResourceForm(null);
                  await refresh();
                })
              }
            >
              保存
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={!!importForm}
        onClose={() => !busy && setImportForm(null)}
        size="2xl"
      >
        <ModalContent>
          <ModalHeader>批量导入 AWS 账号</ModalHeader>
          <ModalBody>
            {importForm && (
              <div className="space-y-4">
                <p className="text-sm text-default-500">
                  每行一个账号，字段用空格分隔：AccessKey SecretKey [区域]
                  [名称]。导入内容只提交到面板后端，不保存到浏览器。
                </p>
                <Textarea
                  label="账号列表"
                  minRows={8}
                  autoComplete="off"
                  value={importForm.text}
                  onValueChange={(text) =>
                    setImportForm({ ...importForm, text })
                  }
                />
                <div className="grid gap-4 sm:grid-cols-2">
                  <Input
                    label="默认区域"
                    value={importForm.region}
                    onValueChange={(region) =>
                      setImportForm({ ...importForm, region })
                    }
                  />
                  <SelectField
                    label="导入到账号组"
                    value={importForm.groupId}
                    onChange={(value) =>
                      setImportForm({
                        ...importForm,
                        groupId: value ? Number(value) : undefined,
                      })
                    }
                  >
                    <option value="">不分组</option>
                    {groups.map((group) => (
                      <option key={group.id} value={group.id}>
                        {group.name}
                      </option>
                    ))}
                  </SelectField>
                </div>
                {importResult && (
                  <div className="space-y-1 text-sm">
                    <p>
                      导入成功：
                      {importResult.imported ?? importResult.successCount ?? 0}
                      ，失败：
                      {importResult.failed ?? importResult.errors?.length ?? 0}
                    </p>
                    {importResult.errors?.map((error, index) => (
                      <p className="text-danger" key={index}>
                        {typeof error === "string"
                          ? error
                          : "第 " + error.line + " 行：" + error.message}
                      </p>
                    ))}
                  </div>
                )}
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setImportForm(null)}>
              关闭
            </Button>
            <Button
              color="primary"
              isLoading={busy === "import"}
              isDisabled={!!busy}
              onPress={() =>
                void perform("import", async () => {
                  if (!importForm?.text.trim())
                    throw new Error("请粘贴账号列表");
                  const result = unpack(
                    await aws.importAwsAccounts(
                      importForm.text,
                      importForm.groupId,
                      importForm.region,
                    ),
                    "导入失败",
                  );
                  setImportResult(result);
                  setImportForm((form) =>
                    form ? { ...form, text: "" } : null,
                  );
                  await refresh();
                })
              }
            >
              导入
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={!!templateForm}
        onClose={() => !busy && setTemplateForm(null)}
        size="3xl"
        scrollBehavior="inside"
      >
        <ModalContent>
          <ModalHeader>
            {templateForm?.id ? "编辑创建模板" : "新增创建模板"}
          </ModalHeader>
          <ModalBody>
            {templateForm && (
              <LaunchFields
                form={templateForm}
                setForm={setTemplateForm}
                accounts={accounts}
                template
              />
            )}
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setTemplateForm(null)}>
              取消
            </Button>
            <Button
              color="primary"
              isLoading={busy === "template"}
              isDisabled={!!busy}
              onPress={() => void saveLaunch(true)}
            >
              保存模板
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
      <Modal
        isOpen={!!createForm}
        onClose={() => !busy && setCreateForm(null)}
        size="3xl"
        scrollBehavior="inside"
      >
        <ModalContent>
          <ModalHeader>创建 EC2 实例</ModalHeader>
          <ModalBody>
            {createForm && (
              <div className="space-y-4">
                <SelectField
                  label="复用创建模板"
                  value={createForm.templateId}
                  onChange={(value) => {
                    const template = templates.find(
                      (item) => item.id === Number(value),
                    );
                    setCreateForm(
                      template
                        ? {
                            ...template,
                            id: undefined,
                            templateId: template.id,
                            clientToken: createForm.clientToken,
                          }
                        : {
                            ...emptyTemplate,
                            clientToken: createForm.clientToken,
                          },
                    );
                  }}
                >
                  <option value="">手动填写</option>
                  {templates.map((template) => (
                    <option key={template.id} value={template.id}>
                      {template.name}
                    </option>
                  ))}
                </SelectField>
                <LaunchFields
                  form={createForm}
                  setForm={(form) =>
                    setCreateForm({
                      ...form,
                      clientToken: createForm.clientToken,
                      templateId: createForm.templateId,
                    })
                  }
                  accounts={accounts}
                />
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setCreateForm(null)}>
              取消
            </Button>
            <Button
              color="primary"
              isLoading={busy === "create"}
              isDisabled={!!busy}
              onPress={() => void saveLaunch(false)}
            >
              确认配置并创建
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={!!batchIpForm}
        onClose={() => !busy && setBatchIpForm(null)}
        size="lg"
      >
        <ModalContent>
          <ModalHeader>批量更换 IP（{selectedInstances.length} 个实例）</ModalHeader>
          <ModalBody>
            {batchIpForm && (
              <div className="space-y-4">
                <p className="text-sm text-warning">
                  每个实例会单独提交任务；动态 IP 可能导致实例停止并重新启动。
                  不是面板创建的 Elastic IP 不会被自动释放。
                </p>
                <SelectField
                  label="换 IP 方式"
                  value={batchIpForm.strategy}
                  onChange={(strategy) =>
                    setBatchIpForm({
                      ...batchIpForm,
                      strategy: strategy as aws.AwsChangeIpPayload["strategy"],
                    })
                  }
                >
                  <option value="AUTO">自动判断</option>
                  <option value="DYNAMIC">动态公网 IP</option>
                  <option value="EIP">Elastic IP</option>
                </SelectField>
                <Input
                  label="目标 IPv4 CIDR（可选）"
                  placeholder="例如 1.2.0.0/16"
                  value={batchIpForm.cidr}
                  onValueChange={(cidr) => setBatchIpForm({ ...batchIpForm, cidr })}
                />
                <Input
                  label="最大尝试次数（1–10）"
                  type="number"
                  min={1}
                  max={10}
                  value={String(batchIpForm.maxAttempts)}
                  onValueChange={(value) =>
                    setBatchIpForm({
                      ...batchIpForm,
                      maxAttempts: Math.min(10, Math.max(1, Number(value) || 1)),
                    })
                  }
                />
                <Check
                  label="释放旧的面板托管 Elastic IP"
                  checked={batchIpForm.releaseOld}
                  onChange={(releaseOld) =>
                    setBatchIpForm({ ...batchIpForm, releaseOld })
                  }
                />
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button
              variant="light"
              isDisabled={!!busy}
              onPress={() => setBatchIpForm(null)}
            >
              取消
            </Button>
            <Button
              color="primary"
              isLoading={busy === "batch-ip"}
              isDisabled={!!busy}
              onPress={() => void executeBatchChangeIp()}
            >
              提交批量任务
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={!!actionForm}
        onClose={() => !busy && setActionForm(null)}
        size="2xl"
        scrollBehavior="inside"
      >
        <ModalContent>
          <ModalHeader>
            {actionForm ? actionNames[actionForm.action] : "实例操作"}
          </ModalHeader>
          <ModalBody>
            {actionForm && (
              <div className="space-y-4">
                <div className="rounded-medium bg-default-100 p-3 text-sm">
                  <p>账号：{accountName(actionForm.instance.accountId)}</p>
                  <p>区域：{actionForm.instance.region}</p>
                  <p>实例：{actionForm.instance.name || "未命名"}</p>
                  <p className="break-all font-mono">
                    {actionForm.instance.instanceId}
                  </p>
                </div>
                {[
                  "stop",
                  "reboot",
                  "terminate",
                  "reinstall",
                  "rotate-ip",
                  "failover",
                ].includes(actionForm.action) && (
                  <p className="text-sm text-warning">
                    {actionForm.action === "terminate"
                      ? "删除不可撤销，实例和随实例删除的磁盘数据将丢失，请确认备份。"
                      : actionForm.action === "reinstall"
                        ? "重装会替换系统盘并导致中断，旧根卷保留（可能继续计费）。只能对运行中的实例执行。"
                        : actionForm.action === "failover"
                          ? "将按自动替补配置创建新实例；旧实例会保留，创建或绑定失败时需要人工核对。"
                        : "此操作可能导致服务中断或公网地址变化，请确认目标。"}
                  </p>
                )}
                {actionForm.action === "modify" && (
                  <>
                    <SelectField
                      label="修改项目"
                      value={actionForm.field}
                      onChange={(field) =>
                        setActionForm({
                          ...actionForm,
                          field,
                          value:
                            field === "name"
                              ? actionForm.instance.name || ""
                              : "",
                        })
                      }
                    >
                      <option value="name">实例名称</option>
                      <option value="userData">开机脚本（先停止实例）</option>
                      <option value="instanceType">
                        实例型号（先停止实例）
                      </option>
                      <option value="rootVolumeSize">
                        根磁盘大小（GiB，只可扩容）
                      </option>
                      <option value="rootVolumeType">根磁盘类型</option>
                      <option value="rootVolumeIops">根磁盘 IOPS</option>
                      <option value="rootVolumeThroughput">
                        根磁盘吞吐量（MiB/s）
                      </option>
                    </SelectField>
                    {actionForm.field === "userData" ? (
                      <Textarea
                        label="新开机脚本"
                        minRows={6}
                        value={actionForm.value}
                        onValueChange={(value) =>
                          setActionForm({ ...actionForm, value })
                        }
                      />
                    ) : (
                      <Input
                        label="新值"
                        value={actionForm.value}
                        onValueChange={(value) =>
                          setActionForm({ ...actionForm, value })
                        }
                      />
                    )}
                  </>
                )}
                {actionForm.action === "reinstall" && (
                  <Input
                    label="新镜像 AMI ID"
                    placeholder="ami-…"
                    value={actionForm.value}
                    onValueChange={(value) =>
                      setActionForm({ ...actionForm, value })
                    }
                  />
                )}
                {actionForm.action === "rotate-ip" && (
                  <>
                    <SelectField
                      label="换 IP 方式"
                      value={actionForm.strategy}
                      onChange={(strategy) =>
                        setActionForm({
                          ...actionForm,
                          strategy:
                            strategy as aws.AwsChangeIpPayload["strategy"],
                        })
                      }
                    >
                      <option value="AUTO">自动判断</option>
                      <option value="DYNAMIC">动态公网 IP（停止后启动）</option>
                      <option value="EIP">静态 Elastic IP</option>
                    </SelectField>
                    <Input
                      label="目标 IPv4 CIDR（可选）"
                      placeholder="例如 1.2.0.0/16"
                      value={actionForm.cidr}
                      onValueChange={(cidr) =>
                        setActionForm({ ...actionForm, cidr })
                      }
                    />
                    <Input
                      label="最大尝试次数（1–10）"
                      type="number"
                      min={1}
                      max={10}
                      value={String(actionForm.maxAttempts)}
                      onValueChange={(value) =>
                        setActionForm({
                          ...actionForm,
                          maxAttempts: Math.min(
                            10,
                            Math.max(1, Number(value) || 1),
                          ),
                        })
                      }
                    />
                    <Check
                      label="释放旧的面板托管 Elastic IP"
                      checked={actionForm.releaseOld}
                      onChange={(releaseOld) =>
                        setActionForm({ ...actionForm, releaseOld })
                      }
                    />
                    <p className="text-xs text-default-500">
                      默认保留旧 Elastic
                      IP（未绑定地址可能继续计费）；不是面板创建和管理的 Elastic
                      IP 不会被改动。指定 CIDR 不能保证成功。
                    </p>
                  </>
                )}
                {actionForm.action.startsWith("ipv6") && (
                  <p className="text-sm text-default-500">
                    只使用实例已有的 IPv6 就绪子网，不会修改共享
                    VPC、路由或安全组。
                  </p>
                )}
                {actionForm.action === "ipv6-unassign" && (
                  <Textarea
                    label="要释放的 IPv6（逗号或换行分隔）"
                    placeholder="留空释放主网卡上全部 IPv6"
                    value={actionForm.value}
                    onValueChange={(value) =>
                      setActionForm({ ...actionForm, value })
                    }
                  />
                )}
                {actionForm.action === "check" && (
                  <p className="text-sm text-default-500">
                    读取大陆 APK
                    最近回报，不会从面板服务器模拟大陆检测，不触发自动换 IP。
                  </p>
                )}
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button
              variant="light"
              isDisabled={!!busy}
              onPress={() => setActionForm(null)}
            >
              取消
            </Button>
            <Button
              color={actionForm?.action === "terminate" ? "danger" : "primary"}
              isLoading={busy === "action"}
              isDisabled={!!busy}
              onPress={() => void executeAction()}
            >
              {actionForm?.action === "check" ? "读取结果" : "确认执行"}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={!!shareForm}
        onClose={() => !busy && setShareForm(null)}
        size="2xl"
        scrollBehavior="inside"
      >
        <ModalContent>
          <ModalHeader>
            {shareForm?.id ? "编辑分享组" : "新增分享组"}
          </ModalHeader>
          <ModalBody>
            {shareForm && (
              <div className="space-y-4">
                <Input
                  label="分享组名称"
                  value={shareForm.name || ""}
                  onValueChange={(name) => setShareForm({ ...shareForm, name })}
                />
                <Input
                  label="到期时间（留空永不过期）"
                  type="datetime-local"
                  value={localDateTime(shareForm.expiresAt)}
                  onValueChange={(value) =>
                    setShareForm({
                      ...shareForm,
                      expiresAt: value ? new Date(value).getTime() : null,
                    })
                  }
                />
                <SelectField
                  label="分享权限"
                  value={shareForm.permissions || "read"}
                  onChange={(permissions) =>
                    setShareForm({
                      ...shareForm,
                      permissions: permissions as "read" | "operate",
                    })
                  }
                >
                  <option value="read">只读</option>
                  <option value="operate">允许启动、停止、重启</option>
                </SelectField>
                <NodeChoices
                  nodes={nodes}
                  selected={shareForm.nodeIds || []}
                  onChange={(nodeIds) =>
                    setShareForm({ ...shareForm, nodeIds })
                  }
                />
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setShareForm(null)}>
              取消
            </Button>
            <Button
              color="primary"
              isLoading={busy === "share"}
              isDisabled={!!busy}
              onPress={() => void saveShare()}
            >
              保存
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
      <Modal isOpen={!!quota} onClose={() => setQuota(null)}>
        <ModalContent>
          <ModalHeader>vCPU 配额 · {quota?.name}</ModalHeader>
          <ModalBody>
            {quota && (
              <div className="space-y-2 text-sm">
                <p>区域：{quota.data.region || "默认区域"}</p>
                <p>标准实例已使用：{quota.data.vcpuUsed ?? "未知"} vCPU</p>
                <p>标准实例配额：{quota.data.vcpuQuota ?? "未知"} vCPU</p>
                <p>Spot 配额：{quota.data.spotVcpuQuota ?? "未知"} vCPU</p>
                {quota.data.message && (
                  <p className="text-default-500">{quota.data.message}</p>
                )}
                <p className="text-xs text-default-500">
                  未知不等于零；配额结果受账号权限、区域和实例配额类别影响。
                </p>
              </div>
            )}
          </ModalBody>
          <ModalFooter>
            <Button onPress={() => setQuota(null)}>关闭</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
      <Modal isOpen={!!inspection} onClose={() => setInspection(null)}>
        <ModalContent>
          <ModalHeader>{inspection?.title}</ModalHeader>
          <ModalBody>
            <p className="whitespace-pre-line text-sm">{inspection?.text}</p>
          </ModalBody>
          <ModalFooter>
            <Button onPress={() => setInspection(null)}>关闭</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}

function InstanceRows({
  instance,
  accountName,
  node,
  heading,
  disabled,
  selected,
  onSelectedChange,
  onAction,
}: {
  instance: aws.AwsInstance;
  accountName: string;
  node?: aws.AwsBoundNode;
  heading?: string;
  disabled: boolean;
  selected: boolean;
  onSelectedChange: () => void;
  onAction: (action: InstanceAction) => void;
}) {
  const actions: InstanceAction[] = [
    "start",
    "stop",
    "reboot",
    "modify",
    "rotate-ip",
    "check",
    "ipv6-enable",
    "ipv6-assign",
    "ipv6-unassign",
    "reinstall",
    "terminate",
  ];
  return (
    <>
      {heading && (
        <tr className="bg-default-50">
          <td
            colSpan={7}
            className="px-3 py-2 text-xs font-semibold text-default-500"
          >
            {heading}
          </td>
        </tr>
      )}
      <tr>
        <td className="w-12 px-3 py-3 align-top">
          <input
            type="checkbox"
            aria-label={`选择实例 ${instance.name || instance.instanceId}`}
            checked={selected}
            onChange={onSelectedChange}
          />
        </td>
        <td className="max-w-56 px-3 py-3">
          <p className="break-words font-medium">
            {instance.name || "未命名实例"}
          </p>
          <p className="font-mono text-xs text-default-500">
            {instance.instanceId}
          </p>
          <p className="mt-1 text-xs text-default-500">
            {node?.name || "未绑定面板节点"}
          </p>
        </td>
        <td className="px-3 py-3">
          <Chip
            size="sm"
            variant="flat"
            color={stateColor(instance.state || instance.status)}
          >
            {instance.state || instance.status || "未知"}
          </Chip>
          <p className="mt-2 text-xs text-default-500">
            {gfwLabel(node?.wallMonitorExternalStatus)}
          </p>
        </td>
        <td className="px-3 py-3">
          <p>{accountName}</p>
          <p className="text-xs text-default-500">{instance.region}</p>
          <p className="text-xs text-default-500">
            {instance.availabilityZone}
          </p>
        </td>
        <td className="max-w-52 break-all px-3 py-3 font-mono text-xs">
          <p>{instance.publicIpv4 || instance.publicIp || "无公网 IPv4"}</p>
          <p className="mt-1 text-default-500">
            {instance.ipv6 || instance.ipv6Addresses?.join(", ") || "无 IPv6"}
          </p>
          {instance.elasticIp && (
            <p className="mt-1 text-primary">Elastic IP</p>
          )}
        </td>
        <td className="px-3 py-3">
          <p>
            {instance.instanceType || "—"}
            {instance.spot ? " · Spot" : ""}
          </p>
          <p className="text-xs text-default-500">
            {instance.vcpu ?? "?"} vCPU / {instance.memoryGiB ?? "?"} GiB
          </p>
          <p className="text-xs text-default-500">
            {instance.bandwidth || "带宽未知"}
          </p>
          <p className="mt-1 text-xs text-default-500">
            {dateLabel(
              instance.configuredAt ||
                instance.createdAt ||
                instance.launchTime,
            )}
          </p>
        </td>
        <td className="px-3 py-3">
          {node && (
            <Button
              size="sm"
              variant="flat"
              color="warning"
              isDisabled={disabled}
              onPress={() => onAction("failover")}
            >
              替补
            </Button>
          )}
          <SelectField
            label="选择操作"
            value=""
            disabled={disabled}
            onChange={(action) => {
              if (action) onAction(action as InstanceAction);
            }}
          >
            <option value="">请选择</option>
            {actions.map((action) => (
              <option key={action} value={action}>
                {actionNames[action]}
              </option>
            ))}
          </SelectField>
        </td>
      </tr>
    </>
  );
}

interface SharedInstance {
  nodeId: number;
  name: string;
  instanceId: string;
  region: string;
  state: string;
  publicIpv4?: string;
  ipv6?: string;
  gfwStatus?: string;
  lastProbeAt?: number;
}
interface SharedData {
  name: string;
  permissions: "read" | "operate";
  expiresAt?: number;
  instances: SharedInstance[];
}

export function AwsSharedPage() {
  const { token = "" } = useParams();
  const [data, setData] = useState<SharedData | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const request = useCallback(
    async <T,>(path: string, body?: unknown): Promise<T> => {
      const response = await fetch(
        "/api/aws-share/" + encodeURIComponent(token) + "/" + path,
        {
          method: body ? "POST" : "GET",
          headers: body ? { "Content-Type": "application/json" } : undefined,
          body: body ? JSON.stringify(body) : undefined,
          credentials: "omit",
          cache: "no-store",
          referrerPolicy: "no-referrer",
        },
      );
      if (!response.ok) throw new Error("分享链接不可用、已过期或请求失败");
      return unpack(await response.json(), "分享链接不可用");
    },
    [token],
  );
  const refresh = useCallback(async () => {
    setBusy(true);
    try {
      setData(await request<SharedData>("instances"));
      setError("");
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  }, [request]);
  useEffect(() => {
    void refresh();
  }, [refresh]);
  return (
    <main className="mx-auto max-w-5xl space-y-5 p-6">
      <header className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold">
            {data?.name || "EC2 实例分享"}
          </h1>
          {data?.expiresAt && (
            <p className="mt-1 text-sm text-default-500">
              到期时间：{dateLabel(data.expiresAt)}
            </p>
          )}
        </div>
        <Button variant="flat" isLoading={busy} onPress={() => void refresh()}>
          刷新
        </Button>
      </header>
      {error ? (
        <p className="rounded-medium bg-danger-50 p-4 text-danger">{error}</p>
      ) : data ? (
        <div className="divide-y divide-default-200 border-y border-default-200">
          {data.instances.map((instance) => (
            <section className="space-y-2 py-4" key={instance.nodeId}>
              <div className="flex items-center gap-2">
                <h2 className="font-semibold">{instance.name}</h2>
                <Chip size="sm" variant="flat">
                  {instance.state}
                </Chip>
              </div>
              <p className="text-xs text-default-500">
                {instance.region} · {instance.instanceId}
              </p>
              <p className="break-all font-mono text-sm">
                {instance.publicIpv4 || "无公网 IPv4"} {instance.ipv6}
              </p>
              <p className="text-xs text-default-500">
                {gfwLabel(instance.gfwStatus)} ·{" "}
                {dateLabel(instance.lastProbeAt)}
              </p>
              {data.permissions === "operate" && (
                <div className="flex gap-2">
                  {(["start", "stop", "reboot"] as const).map((action) => (
                    <Button
                      key={action}
                      size="sm"
                      variant="flat"
                      isDisabled={busy}
                      onPress={() => {
                        if (
                          !window.confirm(
                            actionNames[action] +
                              "实例？\n名称：" +
                              instance.name +
                              "\n区域：" +
                              instance.region +
                              "\n实例：" +
                              instance.instanceId,
                          )
                        )
                          return;
                        setBusy(true);
                        void request("action", {
                          nodeId: instance.nodeId,
                          action,
                          requestId: uuid(),
                        })
                          .then(() =>
                            toast.success("请求已接受，请稍后刷新确认结果"),
                          )
                          .catch((cause) => toast.error(errorMessage(cause)))
                          .finally(() => setBusy(false));
                      }}
                    >
                      {actionNames[action]}
                    </Button>
                  ))}
                </div>
              )}
            </section>
          ))}
        </div>
      ) : (
        <Spinner label="读取分享内容" />
      )}
    </main>
  );
}

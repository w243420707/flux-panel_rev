import { useEffect, useState } from "react";
import { Button } from "@heroui/button";
import { Chip } from "@heroui/chip";
import { Input, Textarea } from "@heroui/input";
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from "@heroui/modal";
import { Spinner } from "@heroui/spinner";
import toast from "react-hot-toast";

import {
  deleteOciAccount,
  getOciAccountPrivateKey,
  listOciAccounts,
  listOciInstances,
  saveOciAccount,
  testOciAccount,
  type OciAccountPayload,
  type OciAccountSummary,
  type OciInstance,
} from "@/api/oci";

const emptyForm: OciAccountPayload = {
  name: "",
  configText: "",
  privateKey: "",
};

const formatOcidTail = (ocid: string) => ocid.slice(-8);

const buildConfigText = (account: OciAccountSummary) =>
  [
    "[DEFAULT]",
    `user=${account.userOcid}`,
    `fingerprint=${account.fingerprint}`,
    `tenancy=${account.tenancyOcid}`,
    `region=${account.region}`,
  ].join("\n");

export default function OciPage() {
  const [accounts, setAccounts] = useState<OciAccountSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState<OciAccountPayload>(emptyForm);
  const [saving, setSaving] = useState(false);
  const [testingId, setTestingId] = useState<number | null>(null);
  const [instancesAccount, setInstancesAccount] = useState<OciAccountSummary | null>(null);
  const [instances, setInstances] = useState<OciInstance[]>([]);
  const [instancesLoading, setInstancesLoading] = useState(false);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [privateKeyLoadingId, setPrivateKeyLoadingId] = useState<number | null>(null);

  useEffect(() => {
    void loadAccounts();
  }, []);

  const loadAccounts = async () => {
    setLoading(true);
    try {
      const response = await listOciAccounts();
      if (response.code === 0) {
        setAccounts(response.data || []);
      } else {
        toast.error(response.msg || "加载 OCI 账号失败");
      }
    } catch {
      toast.error("加载 OCI 账号失败");
    } finally {
      setLoading(false);
    }
  };

  const openCreate = () => {
    setForm({ ...emptyForm });
    setFormOpen(true);
  };

  const openEdit = async (account: OciAccountSummary) => {
    setForm({
      id: account.id,
      name: account.name,
      configText: buildConfigText(account),
      privateKey: "",
    });
    setFormOpen(true);
    setPrivateKeyLoadingId(account.id);
    try {
      const response = await getOciAccountPrivateKey(account.id);
      if (response.code === 0 && response.data?.privateKey) {
        setForm(current => current.id === account.id
          ? { ...current, privateKey: response.data.privateKey }
          : current);
      } else {
        toast.error(response.msg || "读取 OCI 私钥失败");
      }
    } catch {
      toast.error("读取 OCI 私钥失败");
    } finally {
      setPrivateKeyLoadingId(null);
    }
  };

  const updateField = (field: keyof OciAccountPayload, value: string) => {
    setForm((current) => ({ ...current, [field]: value }));
  };

  const handleSave = async () => {
    if (!form.name.trim() || !form.configText.trim()) {
      toast.error("请填写账号名称和 OCI config 内容");
      return;
    }
    if (!form.id && !form.privateKey.trim()) {
      toast.error("新增账号时需要填写私钥");
      return;
    }

    setSaving(true);
    try {
      const response = await saveOciAccount({
        ...form,
        name: form.name.trim(),
        configText: form.configText.trim(),
        privateKey: form.privateKey.trim(),
      });
      if (response.code === 0) {
        toast.success(form.id ? "OCI 账号已更新" : "OCI 账号已添加");
        setFormOpen(false);
        await loadAccounts();
      } else {
        toast.error(response.msg || "保存 OCI 账号失败");
      }
    } catch {
      toast.error("保存 OCI 账号失败");
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async (account: OciAccountSummary) => {
    setTestingId(account.id);
    try {
      const response = await testOciAccount(account.id);
      if (response.code === 0 && response.data?.ok) {
        toast.success(`连接成功，发现 ${response.data.instanceCount} 个实例`);
      } else {
        toast.error(response.msg || "OCI 连接测试失败");
      }
    } catch {
      toast.error("OCI 连接测试失败");
    } finally {
      setTestingId(null);
    }
  };

  const handleShowInstances = async (account: OciAccountSummary) => {
    setInstancesAccount(account);
    setInstances([]);
    setInstancesLoading(true);
    try {
      const response = await listOciInstances(account.id);
      if (response.code === 0) {
        setInstances(response.data || []);
      } else {
        toast.error(response.msg || "加载实例列表失败");
      }
    } catch {
      toast.error("加载实例列表失败");
    } finally {
      setInstancesLoading(false);
    }
  };

  const handleDelete = async (account: OciAccountSummary) => {
    if (!window.confirm(`确定删除 OCI 账号“${account.name}”吗？`)) {
      return;
    }
    setDeletingId(account.id);
    try {
      const response = await deleteOciAccount(account.id);
      if (response.code === 0) {
        toast.success("OCI 账号已删除");
        await loadAccounts();
      } else {
        toast.error(response.msg || "删除 OCI 账号失败");
      }
    } catch {
      toast.error("删除 OCI 账号失败");
    } finally {
      setDeletingId(null);
    }
  };

  const closeForm = () => {
    setFormOpen(false);
    setForm({ ...emptyForm });
  };

  return (
    <div className="container mx-auto max-w-7xl px-3 py-6 lg:px-6">
      <header className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-foreground">Oracle Cloud 账号</h1>
          <p className="mt-1 text-sm text-default-500">管理 OCI 凭据，并查看账号下可绑定的实例。</p>
        </div>
        <Button color="primary" onPress={openCreate}>
          新增账号
        </Button>
      </header>

      {loading ? (
        <div className="flex min-h-48 items-center justify-center">
          <Spinner label="正在加载账号" />
        </div>
      ) : accounts.length === 0 ? (
        <div className="border-y border-default-200 py-12 text-center">
          <p className="text-sm text-default-500">还没有 OCI 账号</p>
          <Button className="mt-4" color="primary" variant="flat" onPress={openCreate}>
            添加第一个账号
          </Button>
        </div>
      ) : (
        <div className="divide-y divide-default-200 border-y border-default-200">
          {accounts.map((account) => (
            <section key={account.id} className="py-5">
              <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <h2 className="text-base font-semibold text-foreground">{account.name}</h2>
                    <Chip size="sm" variant="flat" color={account.privateKeyConfigured ? "success" : "warning"}>
                      {account.privateKeyConfigured ? "私钥已配置" : "缺少私钥"}
                    </Chip>
                    <Chip size="sm" variant="flat">{account.region}</Chip>
                  </div>
                  <dl className="mt-3 grid gap-x-6 gap-y-2 text-xs sm:grid-cols-2">
                    <div className="min-w-0">
                      <dt className="text-default-500">User OCID</dt>
                      <dd className="break-all text-default-700 dark:text-default-300">{account.userOcid}</dd>
                    </div>
                    <div className="min-w-0">
                      <dt className="text-default-500">Tenancy OCID</dt>
                      <dd className="break-all text-default-700 dark:text-default-300">{account.tenancyOcid}</dd>
                    </div>
                    <div>
                      <dt className="text-default-500">API Key 指纹</dt>
                      <dd className="break-all text-default-700 dark:text-default-300">{account.fingerprint}</dd>
                    </div>
                  </dl>
                </div>
                <div className="flex flex-wrap gap-2 lg:justify-end">
                  <Button size="sm" variant="flat" onPress={() => void handleShowInstances(account)}>
                    实例列表
                  </Button>
                  <Button
                    size="sm"
                    variant="flat"
                    color="primary"
                    onPress={() => void handleTest(account)}
                    isLoading={testingId === account.id}
                  >
                    测试连接
                  </Button>
                  <Button size="sm" variant="flat" onPress={() => void openEdit(account)} isLoading={privateKeyLoadingId === account.id}>
                    编辑
                  </Button>
                  <Button
                    size="sm"
                    variant="flat"
                    color="danger"
                    onPress={() => void handleDelete(account)}
                    isLoading={deletingId === account.id}
                  >
                    删除
                  </Button>
                </div>
              </div>
            </section>
          ))}
        </div>
      )}

      <Modal isOpen={formOpen} onOpenChange={(open) => open ? setFormOpen(true) : closeForm()} size="2xl" scrollBehavior="outside">
        <ModalContent>
          {(onClose) => (
            <>
              <ModalHeader>{form.id ? "编辑 OCI 账号" : "新增 OCI 账号"}</ModalHeader>
              <ModalBody>
                <div className="grid gap-4">
                  <Input
                    label="账号名称"
                    value={form.name}
                    onValueChange={(value) => updateField("name", value)}
                    variant="bordered"
                  />
                  <Textarea
                    label="OCI config 内容"
                    placeholder={'[DEFAULT]\nuser=ocid1.user...\nfingerprint=...\ntenancy=ocid1.tenancy...\nregion=eu-madrid-1\nkey_file=...'}
                    value={form.configText}
                    onValueChange={(value) => updateField("configText", value)}
                    minRows={6}
                    variant="bordered"
                  />
                  <Textarea
                    label="Private Key"
                    placeholder={privateKeyLoadingId === form.id ? "正在读取私钥..." : "粘贴 OCI API Signing Key 私钥"}
                    value={form.privateKey}
                    onValueChange={(value) => updateField("privateKey", value)}
                    minRows={8}
                    variant="bordered"
                    isRequired={!form.id}
                  />
                </div>
              </ModalBody>
              <ModalFooter>
                <Button variant="light" onPress={onClose}>取消</Button>
                <Button color="primary" onPress={() => void handleSave()} isLoading={saving}>保存</Button>
              </ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>

      <Modal
        isOpen={instancesAccount !== null}
        onOpenChange={(open) => {
          if (!open) {
            setInstancesAccount(null);
            setInstances([]);
          }
        }}
        size="3xl"
        scrollBehavior="inside"
      >
        <ModalContent>
          {(onClose) => (
            <>
              <ModalHeader className="flex flex-col gap-1">
                <span>{instancesAccount?.name} 的实例</span>
                <span className="text-xs font-normal text-default-500">通过实例 OCID 绑定，不受公网 IP 变化影响。</span>
              </ModalHeader>
              <ModalBody>
                {instancesLoading ? (
                  <div className="flex min-h-40 items-center justify-center"><Spinner label="正在读取实例" /></div>
                ) : instances.length === 0 ? (
                  <div className="py-10 text-center text-sm text-default-500">该账号下没有可显示的实例</div>
                ) : (
                  <div className="divide-y divide-default-200">
                    {instances.map((instance) => (
                      <div key={instance.instanceOcid} className="grid gap-2 py-4 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-center">
                        <div className="min-w-0">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="font-medium text-foreground">{instance.displayName}</span>
                            <Chip size="sm" variant="flat" color={instance.lifecycleState === "RUNNING" ? "success" : "default"}>
                              {instance.lifecycleState}
                            </Chip>
                          </div>
                          <div className="mt-1 break-all font-mono text-xs text-default-500" title={instance.instanceOcid}>
                            OCID …{formatOcidTail(instance.instanceOcid)}
                          </div>
                        </div>
                        <div className="text-sm text-default-600">公网 IP：{instance.publicIp || "暂无"}</div>
                      </div>
                    ))}
                  </div>
                )}
              </ModalBody>
              <ModalFooter><Button variant="light" onPress={onClose}>关闭</Button></ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>
    </div>
  );
}

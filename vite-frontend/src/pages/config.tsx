import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from "@heroui/button";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Input } from "@heroui/input";
import { Spinner } from "@heroui/spinner";
import { Divider } from "@heroui/divider";
import { Switch } from "@heroui/switch";
import { Select, SelectItem } from "@heroui/select";
import toast from 'react-hot-toast';
import { updateConfigs } from '@/api';
import {
  checkForUpdate,
  getUpdateSettings,
  getUpdateStatus,
  saveUpdateSettings,
  UpdateSettings,
  UpdateStatus,
} from '@/api/update';
import { SettingsIcon } from '@/components/icons';

import { isAdmin } from '@/utils/auth';
import { getCachedConfigs, clearConfigCache, updateSiteConfig } from '@/config/site';

// 简单的保存图标组件
const SaveIcon = ({ className }: { className?: string }) => (
  <svg
    className={className}
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z" />
    <polyline points="17,21 17,13 7,13 7,21" />
    <polyline points="7,3 7,8 15,8" />
  </svg>
);

interface ConfigItem {
  key: string;
  label: string;
  placeholder?: string;
  description?: string;
  type: 'input' | 'switch' | 'select';
  options?: { label: string; value: string; description?: string }[];
  dependsOn?: string; // 依赖的配置项key
  dependsValue?: string; // 依赖的配置项值
}

// 网站配置项定义
const CONFIG_ITEMS: ConfigItem[] = [
  {
    key: 'ip',
    label: '面板后端地址',
    placeholder: '请输入面板后端IP:PORT',
    description: '格式“ip:port”,用于对接节点时使用,ip是你安装面板服务器的公网ip,端口是安装脚本内输入的后端端口。不要套CDN,不支持https,通讯数据有加密',
    type: 'input'
  },
  {
    key: 'app_name',
    label: '应用名称',
    placeholder: '请输入应用名称',
    description: '在浏览器标签页和导航栏显示的应用名称',
    type: 'input'
  },
  {
    key: 'captcha_enabled',
    label: '启用验证码',
    description: '开启后，用户登录时需要完成验证码验证',
    type: 'switch'
  },
  {
    key: 'captcha_type',
    label: '验证码类型',
    description: '选择验证码的显示类型，不同类型有不同的安全级别',
    type: 'select',
    dependsOn: 'captcha_enabled',
    dependsValue: 'true',
    options: [
      { 
        label: '随机类型', 
        value: 'RANDOM', 
        description: '系统随机选择验证码类型' 
      },
      { 
        label: '滑块验证码', 
        value: 'SLIDER', 
        description: '拖动滑块完成拼图验证' 
      },
      { 
        label: '文字点选验证码', 
        value: 'WORD_IMAGE_CLICK', 
        description: '按顺序点击指定文字' 
      },
      { 
        label: '旋转验证码', 
        value: 'ROTATE', 
        description: '旋转图片到正确角度' 
      },
      { 
        label: '拼图验证码', 
        value: 'CONCAT', 
        description: '拖动滑块完成图片拼接' 
      }
    ]
  }
];

const DEFAULT_UPDATE_SETTINGS: UpdateSettings = {
  enabled: false,
  checkIntervalSeconds: 24 * 60 * 60,
  githubTokenConfigured: false,
  githubTokenMasked: null,
  repository: '',
};

const formatUpdateTime = (value: string | number | null | undefined): string => {
  if (value === null || value === undefined || value === '') {
    return '暂无';
  }

  const date = typeof value === 'number'
    ? new Date(value < 1_000_000_000_000 ? value * 1000 : value)
    : new Date(value);

  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
};

// 初始化时从缓存读取配置，避免闪烁
const getInitialConfigs = (): Record<string, string> => {
  if (typeof window === 'undefined') return {};
  
  const configKeys = ['app_name', 'captcha_enabled', 'captcha_type', 'ip'];
  const initialConfigs: Record<string, string> = {};
  
  try {
    configKeys.forEach(key => {
      const cachedValue = localStorage.getItem('vite_config_' + key);
      if (cachedValue) {
        initialConfigs[key] = cachedValue;
      }
    });
  } catch (error) {
  }
  
  return initialConfigs;
};

export default function ConfigPage() {
  const navigate = useNavigate();
  const initialConfigs = getInitialConfigs();
  const [configs, setConfigs] = useState<Record<string, string>>(initialConfigs);
  const [loading, setLoading] = useState(Object.keys(initialConfigs).length === 0); // 如果有缓存数据，不显示loading
  const [saving, setSaving] = useState(false);
  const [hasChanges, setHasChanges] = useState(false);
  const [originalConfigs, setOriginalConfigs] = useState<Record<string, string>>(initialConfigs);
  const [updateSettings, setUpdateSettings] = useState<UpdateSettings>(DEFAULT_UPDATE_SETTINGS);
  const [updateStatus, setUpdateStatus] = useState<UpdateStatus | null>(null);
  const [updateIntervalMinutes, setUpdateIntervalMinutes] = useState('1440');
  const [githubToken, setGithubToken] = useState('');
  const [clearGithubToken, setClearGithubToken] = useState(false);
  const [updateLoading, setUpdateLoading] = useState(true);
  const [updateSaving, setUpdateSaving] = useState(false);
  const [updateChecking, setUpdateChecking] = useState(false);

  // 权限检查
  useEffect(() => {
    if (!isAdmin()) {
      toast.error('权限不足，只有管理员可以访问此页面');
      navigate('/dashboard', { replace: true });
      return;
    }
  }, [navigate]);

  // 加载配置数据（优先从缓存）
  const loadConfigs = async (currentConfigs?: Record<string, string>) => {
    const configsToCompare = currentConfigs || configs;
    const hasInitialData = Object.keys(configsToCompare).length > 0;
    
    // 如果已有缓存数据，不显示loading，静默更新
    if (!hasInitialData) {
      setLoading(true);
    }
    
    try {
      const configData = await getCachedConfigs();
      
      // 只有在数据有变化时才更新
      const hasDataChanged = JSON.stringify(configData) !== JSON.stringify(configsToCompare);
      if (hasDataChanged) {
        setConfigs(configData);
        setOriginalConfigs({ ...configData });
        setHasChanges(false);
      } else {
      }
    } catch (error) {
      // 只有在没有缓存数据时才显示错误
      if (!hasInitialData) {
        toast.error('加载配置出错，请重试');
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    // 延迟加载，避免阻塞初始渲染
    const timer = setTimeout(() => {
      loadConfigs(initialConfigs);
    }, 100);

    return () => clearTimeout(timer);
  }, []); // 只在组件挂载时执行一次

  // 加载自动更新设置和状态。Token 只保留在当前输入框内，不写入缓存。
  useEffect(() => {
    let active = true;

    const loadUpdateInfo = async () => {
      setUpdateLoading(true);
      try {
        const [settingsResponse, statusResponse] = await Promise.all([
          getUpdateSettings(),
          getUpdateStatus(),
        ]);

        if (!active) return;

        if (settingsResponse.code === 0 && settingsResponse.data) {
          const settings = {
            ...DEFAULT_UPDATE_SETTINGS,
            ...settingsResponse.data,
          };
          setUpdateSettings(settings);
          setUpdateIntervalMinutes(String(Math.max(3, Math.round(settings.checkIntervalSeconds / 60))));
        } else if (settingsResponse.msg) {
          toast.error('加载自动更新设置失败: ' + settingsResponse.msg);
        }

        if (statusResponse.code === 0 && statusResponse.data) {
          setUpdateStatus(statusResponse.data);
        }
      } catch (error) {
        if (active) {
          toast.error('加载自动更新设置失败，请重试');
        }
      } finally {
        if (active) {
          setUpdateLoading(false);
        }
      }
    };

    void loadUpdateInfo();
    return () => {
      active = false;
    };
  }, []);

  // 处理配置项变更
  const handleConfigChange = (key: string, value: string) => {
    let newConfigs = { ...configs, [key]: value };
    
    // 特殊处理：启用验证码时，如果验证码类型未设置，默认为随机
    if (key === 'captcha_enabled' && value === 'true') {
      if (!newConfigs.captcha_type) {
        newConfigs.captcha_type = 'RANDOM';
      }
    }
    
    setConfigs(newConfigs);
    
    // 检查是否有变更
    const hasChangesNow = Object.keys(newConfigs).some(
      k => newConfigs[k] !== originalConfigs[k]
    ) || Object.keys(originalConfigs).some(
      k => originalConfigs[k] !== newConfigs[k]
    );
    setHasChanges(hasChangesNow);
  };

  // 保存配置
  const handleSave = async () => {
    setSaving(true);
    try {
      const response = await updateConfigs(configs);
      if (response.code === 0) {
        toast.success('配置保存成功');
        
        // 清除所有配置缓存，强制下次重新获取
        clearConfigCache();
        
        // 获取变更的配置项
        const changedKeys = Object.keys(configs).filter(
          key => configs[key] !== originalConfigs[key]
        );
        
        setOriginalConfigs({ ...configs });
        setHasChanges(false);
        
        // 如果应用名称发生变化，立即更新网站配置
        if (changedKeys.includes('app_name')) {
          await updateSiteConfig();
        }
        
        // 触发配置更新事件，通知其他组件
        window.dispatchEvent(new CustomEvent('configUpdated', { 
          detail: { changedKeys } 
        }));
      } else {
        toast.error('保存配置失败: ' + response.msg);
      }
    } catch (error) {
      toast.error('保存配置出错，请重试');
    } finally {
      setSaving(false);
    }
  };

  // 保存自动更新设置
  const handleUpdateSettingsSave = async () => {
    const intervalMinutes = Number(updateIntervalMinutes);
    if (!Number.isInteger(intervalMinutes) || intervalMinutes < 3) {
      toast.error('检查间隔至少为 3 分钟');
      return;
    }

    setUpdateSaving(true);
    try {
      const payload: {
        enabled: boolean;
        checkIntervalSeconds: number;
        githubToken?: string;
        clearGithubToken?: boolean;
      } = {
        enabled: updateSettings.enabled,
        checkIntervalSeconds: intervalMinutes * 60,
      };

      if (clearGithubToken) {
        payload.clearGithubToken = true;
      } else if (githubToken.trim()) {
        payload.githubToken = githubToken.trim();
      }

      const response = await saveUpdateSettings(payload);
      if (response.code !== 0) {
        toast.error('自动更新设置保存失败: ' + response.msg);
        return;
      }

      toast.success('自动更新设置已保存');
      setGithubToken('');
      setClearGithubToken(false);

      if (response.data) {
        setUpdateSettings({ ...DEFAULT_UPDATE_SETTINGS, ...response.data });
      } else {
        const settingsResponse = await getUpdateSettings();
        if (settingsResponse.code === 0 && settingsResponse.data) {
          setUpdateSettings({ ...DEFAULT_UPDATE_SETTINGS, ...settingsResponse.data });
        }
      }
    } catch (error) {
      toast.error('自动更新设置保存失败，请重试');
    } finally {
      setUpdateSaving(false);
    }
  };

  // 手动检查 GitHub Release
  const handleUpdateCheck = async () => {
    setUpdateChecking(true);
    try {
      const response = await checkForUpdate();
      if (response.code === 0 && response.data) {
        setUpdateStatus(response.data);
        toast.success(response.data.updateAvailable ? '发现新版本' : '当前已是最新版本');
      } else {
        toast.error('检查更新失败: ' + response.msg);
      }
    } catch (error) {
      toast.error('检查更新失败，请重试');
    } finally {
      setUpdateChecking(false);
    }
  };



  // 检查配置项是否应该显示（依赖检查）
  const shouldShowItem = (item: ConfigItem): boolean => {
    if (!item.dependsOn || !item.dependsValue) {
      return true;
    }
    return configs[item.dependsOn] === item.dependsValue;
  };

  // 渲染不同类型的配置项
  const renderConfigItem = (item: ConfigItem) => {
    const isChanged = hasChanges && configs[item.key] !== originalConfigs[item.key];
    
    switch (item.type) {
      case 'input':
        return (
          <Input
            value={configs[item.key] || ''}
            onChange={(e) => handleConfigChange(item.key, e.target.value)}
            placeholder={item.placeholder}
            variant="bordered"
            size="md"
            classNames={{
              input: "text-sm",
              inputWrapper: isChanged 
                ? "border-warning-300 data-[hover=true]:border-warning-400" 
                : ""
            }}
          />
        );

      case 'switch':
        return (
          <Switch
            isSelected={configs[item.key] === 'true'}
            onValueChange={(checked) => handleConfigChange(item.key, checked ? 'true' : 'false')}
            color="primary"
            size="md"
            classNames={{
              wrapper: isChanged ? "border-warning-300" : ""
            }}
          >
            <span className="text-sm text-gray-700 dark:text-gray-300">
              {configs[item.key] === 'true' ? '已启用' : '已禁用'}
            </span>
          </Switch>
        );

      case 'select':
        return (
          <Select
            selectedKeys={configs[item.key] ? [configs[item.key]] : []}
            onSelectionChange={(keys) => {
              const selectedKey = Array.from(keys)[0] as string;
              if (selectedKey) {
                handleConfigChange(item.key, selectedKey);
              }
            }}
            placeholder="请选择验证码类型"
            variant="bordered"
            size="md"
            classNames={{
              trigger: isChanged 
                ? "border-warning-300 data-[hover=true]:border-warning-400" 
                : ""
            }}
          >
            {item.options?.map((option) => (
              <SelectItem 
                key={option.value}
                description={option.description}
              >
                {option.label}
              </SelectItem>
            )) || []}
          </Select>
        );

      default:
        return null;
    }
  };

  if (loading) {
    return (
      
        <div className="flex items-center justify-center min-h-[400px]">
          <Spinner size="lg" label="加载配置中..." />
        </div>
      
    );
  }

  return (
    
      <div className="p-6 max-w-4xl mx-auto">
        {/* 页面标题 */}
        <div className="flex items-center gap-3 mb-6">
          <SettingsIcon className="w-8 h-8 text-primary" />
          <div>
            <h1 className="text-2xl font-bold">网站配置</h1>
            <p className="text-gray-600 dark:text-gray-400">
              管理网站的基本信息和显示设置
            </p>
          </div>
        </div>

        <Card className="shadow-md">
          <CardHeader className="pb-4">
            <div className="flex justify-between items-center w-full">
              <div>
                <h2 className="text-xl font-semibold">基本设置</h2>
                <p className="text-sm text-gray-600 dark:text-gray-400">
                  配置网站的基本信息，这些设置会影响网站的显示效果
                </p>
              </div>
              <div className="flex gap-2">

                <Button
                  color="primary"
                  startContent={<SaveIcon className="w-4 h-4" />}
                  onClick={handleSave}
                  isLoading={saving}
                  disabled={!hasChanges}
                >
                  {saving ? '保存中...' : '保存配置'}
                </Button>
              </div>
            </div>
          </CardHeader>

          <Divider />

          <CardBody className="space-y-6 pt-6">
            {CONFIG_ITEMS.map((item, index) => {
              // 检查配置项是否应该显示
              if (!shouldShowItem(item)) {
                return null;
              }

              // 计算是否是最后一个显示的项目（用于决定是否显示分隔线）
              const remainingItems = CONFIG_ITEMS.slice(index + 1).filter(shouldShowItem);
              const isLastItem = remainingItems.length === 0;

              return (
                <div key={item.key} className="space-y-3">
                  <div className="flex flex-col gap-1">
                    <label className="text-sm font-medium text-gray-700 dark:text-gray-300">
                      {item.label}
                    </label>
                    {item.description && (
                      <p className="text-xs text-gray-500 dark:text-gray-400">
                        {item.description}
                      </p>
                    )}
                  </div>
                  
                  {/* 渲染配置项 */}
                  {renderConfigItem(item)}
                  
                  {/* 分隔线 */}
                  {!isLastItem && (
                    <Divider className="mt-6" />
                  )}
                </div>
              );
            })}
          </CardBody>
        </Card>

        <Card className="mt-6 shadow-md">
          <CardHeader className="pb-4">
            <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 w-full">
              <div>
                <h2 className="text-xl font-semibold">面板自动更新</h2>
                <p className="text-sm text-gray-600 dark:text-gray-400">
                  从 GitHub 私有仓库检查面板新版本，Token 只由后端保存。
                </p>
              </div>
              <Button
                color="primary"
                startContent={<SaveIcon className="w-4 h-4" />}
                onClick={handleUpdateSettingsSave}
                isLoading={updateSaving}
              >
                {updateSaving ? '保存中...' : '保存更新设置'}
              </Button>
            </div>
          </CardHeader>

          <Divider />

          <CardBody className="space-y-6 pt-6">
            {updateLoading ? (
              <div className="flex items-center justify-center py-6">
                <Spinner size="sm" label="加载更新设置中..." />
              </div>
            ) : (
              <>
                <div className="flex flex-col gap-3">
                  <div>
                    <label className="text-sm font-medium text-gray-700 dark:text-gray-300">
                      自动检查更新
                    </label>
                    <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">
                      默认关闭，打开后按下面的间隔自动检查。
                    </p>
                  </div>
                  <Switch
                    isSelected={updateSettings.enabled}
                    onValueChange={(enabled) => setUpdateSettings((current) => ({ ...current, enabled }))}
                    color="primary"
                  >
                    <span className="text-sm text-gray-700 dark:text-gray-300">
                      {updateSettings.enabled ? '已启用' : '已禁用'}
                    </span>
                  </Switch>
                </div>

                <Divider />

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <Input
                    type="number"
                    label="检查间隔（分钟）"
                    min={3}
                    step={1}
                    value={updateIntervalMinutes}
                    onChange={(event) => setUpdateIntervalMinutes(event.target.value)}
                    description="最少 3 分钟，默认 1440 分钟（24 小时）"
                    variant="bordered"
                  />
                  <Input
                    label="GitHub 仓库"
                    value={updateSettings.repository || '未配置'}
                    isReadOnly
                    variant="bordered"
                  />
                </div>

                <div className="space-y-3">
                  <Input
                    type="password"
                    label="GitHub Token"
                    placeholder={updateSettings.githubTokenConfigured
                      ? `已配置${updateSettings.githubTokenMasked ? `（${updateSettings.githubTokenMasked}）` : ''}，留空保留原 Token`
                      : '请输入用于访问私有仓库的 Token'}
                    value={githubToken}
                    onChange={(event) => {
                      setGithubToken(event.target.value);
                      if (event.target.value) {
                        setClearGithubToken(false);
                      }
                    }}
                    autoComplete="new-password"
                    variant="bordered"
                    description="Token 不会写入浏览器缓存；留空表示保留当前 Token。"
                  />
                  {updateSettings.githubTokenConfigured && (
                    <label className="inline-flex items-center gap-2 text-sm text-danger-600 dark:text-danger-400 cursor-pointer">
                      <input
                        type="checkbox"
                        checked={clearGithubToken}
                        onChange={(event) => {
                          setClearGithubToken(event.target.checked);
                          if (event.target.checked) {
                            setGithubToken('');
                          }
                        }}
                        className="h-4 w-4 rounded border-gray-300 text-danger-600 focus:ring-danger-500"
                      />
                      清除已保存的 GitHub Token
                    </label>
                  )}
                </div>

                <Divider />

                <div className="flex flex-col gap-4">
                  <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
                    <div>
                      <h3 className="text-base font-medium">版本状态</h3>
                      <p className="text-xs text-gray-500 dark:text-gray-400">
                        {updateStatus?.updateMessage || updateStatus?.lastCheckMessage || '尚未执行检查'}
                      </p>
                    </div>
                    <Button
                      variant="flat"
                      color="primary"
                      onClick={handleUpdateCheck}
                      isLoading={updateChecking}
                    >
                      {updateChecking ? '检查中...' : '立即检查'}
                    </Button>
                  </div>

                  {updateStatus ? (
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-3 text-sm">
                      <div className="flex justify-between gap-3">
                        <span className="text-gray-500 dark:text-gray-400">当前版本</span>
                        <span className="font-medium">{updateStatus.currentVersion || '未知'}</span>
                      </div>
                      <div className="flex justify-between gap-3">
                        <span className="text-gray-500 dark:text-gray-400">最新版本</span>
                        <span className="font-medium">{updateStatus.latestVersion || '暂无'}</span>
                      </div>
                      <div className="flex justify-between gap-3">
                        <span className="text-gray-500 dark:text-gray-400">更新状态</span>
                        <span className={updateStatus.updateAvailable ? 'font-medium text-primary' : 'font-medium text-success'}>
                          {updateStatus.updateAvailable ? '有新版本' : '已是最新'}
                        </span>
                      </div>
                      <div className="flex justify-between gap-3">
                        <span className="text-gray-500 dark:text-gray-400">更新任务</span>
                        <span className="font-medium">{updateStatus.updateStatus || '暂无任务'}</span>
                      </div>
                      <div className="flex justify-between gap-3">
                        <span className="text-gray-500 dark:text-gray-400">上次检查</span>
                        <span className="font-medium text-right">{formatUpdateTime(updateStatus.lastCheckAt)}</span>
                      </div>
                      <div className="flex justify-between gap-3">
                        <span className="text-gray-500 dark:text-gray-400">下次检查</span>
                        <span className="font-medium text-right">{formatUpdateTime(updateStatus.nextCheckAt)}</span>
                      </div>
                    </div>
                  ) : (
                    <p className="text-sm text-gray-500 dark:text-gray-400">暂无版本状态</p>
                  )}
                </div>
              </>
            )}
          </CardBody>
        </Card>

        {/* 操作提示 */}
        {hasChanges && (
          <Card className="mt-4 bg-warning-50 dark:bg-warning-900/20 border-warning-200 dark:border-warning-800">
            <CardBody className="py-3">
              <div className="flex items-center gap-2 text-warning-700 dark:text-warning-300">
                <div className="w-2 h-2 bg-warning-500 rounded-full animate-pulse" />
                <span className="text-sm">
                  检测到配置变更，请记得保存您的修改
                </span>
              </div>
            </CardBody>
          </Card>
        )}
      </div>
    
  );
}

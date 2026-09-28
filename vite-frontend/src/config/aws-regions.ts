/**
 * AWS 区域常量表（前端硬编码）
 *
 * 后端没有提供区域列表接口，所以这里维护唯一一份数据源；
 * 扁平数组、code 列表、名字映射、Set 全部从 AWS_REGION_GROUPS 派生，避免多处不同步。
 *
 * 注意：所有 code 必须能通过后端校验正则
 *   ^[a-z]{2,3}(-gov)?-[a-z0-9-]+-[0-9]+$
 * （见 AwsAccountServiceImpl），且单个账号最多 50 个区域。
 */

export type AwsRegion = {
  /** AWS 区域代码，如 us-east-1 */
  code: string;
  /** 中文友好名 */
  name: string;
  /** 新账号默认未开通，需先在 AWS 控制台启用该区域，否则调用会失败 */
  optIn?: boolean;
};

export type AwsRegionGroup = {
  /** 稳定 id，用作 React key */
  id: string;
  /** 分组显示名 */
  label: string;
  regions: AwsRegion[];
};

/** 与后端 AwsAccountServiceImpl 使用的同款区域校验正则 */
export const AWS_REGION_PATTERN = /^[a-z]{2,3}(-gov)?-[a-z0-9-]+-[0-9]+$/;

export const AWS_REGION_GROUPS: AwsRegionGroup[] = [
  {
    id: "north-america",
    label: "北美",
    regions: [
      { code: "us-east-1", name: "美国东部（弗吉尼亚北部）" },
      { code: "us-east-2", name: "美国东部（俄亥俄）" },
      { code: "us-west-1", name: "美国西部（加利福尼亚北部）" },
      { code: "us-west-2", name: "美国西部（俄勒冈）" },
      { code: "ca-central-1", name: "加拿大（中部）" },
      { code: "ca-west-1", name: "加拿大西部（卡尔加里）", optIn: true },
      { code: "mx-central-1", name: "墨西哥（中部）", optIn: true },
    ],
  },
  {
    id: "south-america",
    label: "南美",
    regions: [{ code: "sa-east-1", name: "南美洲（圣保罗）" }],
  },
  {
    id: "europe",
    label: "欧洲",
    regions: [
      { code: "eu-west-1", name: "欧洲（爱尔兰）" },
      { code: "eu-west-2", name: "欧洲（伦敦）" },
      { code: "eu-west-3", name: "欧洲（巴黎）" },
      { code: "eu-central-1", name: "欧洲（法兰克福）" },
      { code: "eu-central-2", name: "欧洲（苏黎世）", optIn: true },
      { code: "eu-north-1", name: "欧洲（斯德哥尔摩）" },
      { code: "eu-south-1", name: "欧洲（米兰）", optIn: true },
      { code: "eu-south-2", name: "欧洲（西班牙）", optIn: true },
    ],
  },
  {
    id: "asia-pacific",
    label: "亚太",
    regions: [
      { code: "ap-east-1", name: "亚太（中国香港）", optIn: true },
      { code: "ap-east-2", name: "亚太（中国台湾）", optIn: true },
      { code: "ap-northeast-1", name: "亚太（东京）" },
      { code: "ap-northeast-2", name: "亚太（首尔）" },
      { code: "ap-northeast-3", name: "亚太（大阪）" },
      { code: "ap-south-1", name: "亚太（孟买）" },
      { code: "ap-south-2", name: "亚太（海得拉巴）", optIn: true },
      { code: "ap-southeast-1", name: "亚太（新加坡）" },
      { code: "ap-southeast-2", name: "亚太（悉尼）" },
      { code: "ap-southeast-3", name: "亚太（雅加达）", optIn: true },
      { code: "ap-southeast-4", name: "亚太（墨尔本）", optIn: true },
      { code: "ap-southeast-5", name: "亚太（马来西亚）", optIn: true },
      { code: "ap-southeast-6", name: "亚太（新西兰）", optIn: true },
      { code: "ap-southeast-7", name: "亚太（泰国）", optIn: true },
    ],
  },
  {
    id: "middle-east",
    label: "中东",
    regions: [
      { code: "me-south-1", name: "中东（巴林）", optIn: true },
      { code: "me-central-1", name: "中东（阿联酋）", optIn: true },
      { code: "il-central-1", name: "以色列（特拉维夫）", optIn: true },
    ],
  },
  {
    id: "africa",
    label: "非洲",
    regions: [{ code: "af-south-1", name: "非洲（开普敦）", optIn: true }],
  },
  {
    id: "china",
    label: "中国区",
    regions: [
      // 中国区是独立分区，需要单独的 AWS 中国账号与凭据
      { code: "cn-north-1", name: "中国（北京）" },
      { code: "cn-northwest-1", name: "中国（宁夏）" },
    ],
  },
  {
    id: "govcloud",
    label: "GovCloud",
    regions: [
      { code: "us-gov-east-1", name: "GovCloud（美国东部）" },
      { code: "us-gov-west-1", name: "GovCloud（美国西部）" },
    ],
  },
];

/** 扁平列表，顺序 = 分组顺序 */
export const AWS_REGIONS: AwsRegion[] = AWS_REGION_GROUPS.flatMap(
  (group) => group.regions,
);

/** 全部区域 code */
export const AWS_REGION_CODES: string[] = AWS_REGIONS.map((item) => item.code);

/** code -> 中文名，用于反查展示 */
export const AWS_REGION_NAME: Record<string, string> = Object.fromEntries(
  AWS_REGIONS.map((item) => [item.code, item.name]),
);

/** code 集合，用于判定「不在标准列表里的区域」 */
export const AWS_REGION_SET: ReadonlySet<string> = new Set(AWS_REGION_CODES);

/** code -> optIn 标记 */
const AWS_REGION_OPT_IN: Record<string, boolean> = Object.fromEntries(
  AWS_REGIONS.map((item) => [item.code, !!item.optIn]),
);

/** 反查中文名；非标准区域原样返回 code */
export function regionName(code: string): string {
  return AWS_REGION_NAME[code] ?? code;
}

/** 该区域是否需要先在 AWS 控制台开通 */
export function isOptInRegion(code: string): boolean {
  return !!AWS_REGION_OPT_IN[code];
}

/** 是否符合后端区域正则（用于给不合规的历史数据打警告标记） */
export function isAwsRegionCode(code: string): boolean {
  return AWS_REGION_PATTERN.test(code);
}

/**
 * 逗号串 -> code 数组。
 *
 * 刻意不排序：顺序就是默认区域的语义（首项为默认区域），
 * 必须保证「数据库里的顺序 = UI 里的顺序 = 提交时的顺序」。
 */
export function splitRegionList(value?: string | null): string[] {
  if (!value) return [];

  const seen = new Set<string>();
  const result: string[] = [];

  for (const raw of value.split(",")) {
    const code = raw.trim();

    if (!code || seen.has(code)) continue;
    seen.add(code);
    result.push(code);
  }

  return result;
}

/** code 序列 -> 逗号串（保序去重） */
export function joinRegionList(codes: Iterable<string>): string {
  return splitRegionList(Array.from(codes).join(",")).join(",");
}

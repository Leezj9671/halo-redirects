export type MatchType = "EXACT" | "DIRECTORY";

export interface RuleInput {
  fromPath: string;
  toPath?: string | null;
  matchType: MatchType;
  statusCode: number;
  note?: string | null;
  enabled: boolean;
}

export interface Rule extends RuleInput {
  name: string;
  version: number;
  creationTimestamp: string;
  skippedForLoop: boolean;
}

export interface RuleList {
  pluginEnabled: boolean;
  preserveQueryString: boolean;
  items: Rule[];
}

export interface ImportResult {
  importedCount: number;
  createdCount: number;
  updatedCount: number;
  skippedCount: number;
  totalRuleCount: number;
  mode: string;
}

export interface TestHop {
  path: string;
  ruleName: string | null;
  fromPath: string;
  directory: boolean;
  statusCode: number;
  location: string | null;
}

export interface TestResult {
  input: string;
  path: string;
  pluginEnabled: boolean;
  matched: boolean;
  hops: TestHop[];
}

export const STATUS_OPTIONS = [
  { value: 301, label: "301 永久" },
  { value: 302, label: "302 临时" },
  { value: 307, label: "307 临时（保留方法）" },
  { value: 308, label: "308 永久（保留方法）" },
  { value: 410, label: "410 已删除" },
];

export const MATCH_OPTIONS = [
  { value: "EXACT", label: "精确匹配" },
  { value: "DIRECTORY", label: "目录匹配" },
];

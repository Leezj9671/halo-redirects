<script setup lang="ts">
import {
  Dialog,
  IconAddCircle,
  IconDeleteBin,
  IconRefreshLine,
  Toast,
  VButton,
  VEmpty,
  VLoading,
  VPagination,
  VSpace,
  VSwitch,
  VTag,
} from "@halo-dev/components";
import { computed, onMounted, ref, watch } from "vue";
import { redirectsApi } from "@/api";
import BulkAddModal from "@/components/BulkAddModal.vue";
import ImportModal from "@/components/ImportModal.vue";
import RuleEditModal from "@/components/RuleEditModal.vue";
import UrlTester from "@/components/UrlTester.vue";
import { STATUS_OPTIONS, type Rule, type RuleInput } from "@/types";

const rules = ref<Rule[]>([]);
const pluginEnabled = ref(true);
const loading = ref(false);
const keyword = ref("");
const matchFilter = ref("");
const statusFilter = ref("");
const page = ref(1);
const size = ref(20);
const selected = ref<Set<string>>(new Set());
const highlighted = ref<string | null>(null);
const togglingName = ref<string | null>(null);

const editing = ref<Rule | null>(null);
const editPrefill = ref<Partial<RuleInput> | undefined>(undefined);
const editVisible = ref(false);
const bulkVisible = ref(false);
const importVisible = ref(false);

async function load() {
  loading.value = true;
  try {
    const data = await redirectsApi.list();
    rules.value = data.items;
    pluginEnabled.value = data.pluginEnabled;
    const names = new Set(data.items.map((rule) => rule.name));
    selected.value = new Set([...selected.value].filter((name) => names.has(name)));
  } finally {
    loading.value = false;
  }
}

onMounted(load);

const filtered = computed(() => {
  const text = keyword.value.trim().toLowerCase();
  return rules.value.filter((rule) => {
    if (matchFilter.value && rule.matchType !== matchFilter.value) return false;
    if (statusFilter.value === "disabled" && rule.enabled) return false;
    if (statusFilter.value === "loop" && !rule.skippedForLoop) return false;
    if (statusFilter.value && !["disabled", "loop"].includes(statusFilter.value)
      && String(rule.statusCode) !== statusFilter.value) return false;
    if (!text) return true;
    return [rule.fromPath, rule.toPath, rule.note]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(text));
  });
});

watch([keyword, matchFilter, statusFilter, size], () => (page.value = 1));

const pageItems = computed(() =>
  filtered.value.slice((page.value - 1) * size.value, page.value * size.value)
);

const stats = computed(() => ({
  total: rules.value.length,
  disabled: rules.value.filter((rule) => !rule.enabled).length,
  loops: rules.value.filter((rule) => rule.skippedForLoop).length,
}));

const allOnPageSelected = computed(
  () => pageItems.value.length > 0 && pageItems.value.every((rule) => selected.value.has(rule.name))
);

function toggleAllOnPage(checked: boolean) {
  const next = new Set(selected.value);
  pageItems.value.forEach((rule) => (checked ? next.add(rule.name) : next.delete(rule.name)));
  selected.value = next;
}

function toggleOne(name: string, checked: boolean) {
  const next = new Set(selected.value);
  if (checked) next.add(name);
  else next.delete(name);
  selected.value = next;
}

function openCreate(prefill?: Partial<RuleInput>) {
  editing.value = null;
  editPrefill.value = prefill;
  editVisible.value = true;
}

function openEdit(rule: Rule) {
  editing.value = rule;
  editPrefill.value = undefined;
  editVisible.value = true;
}

async function onSaved(rule: Rule) {
  editVisible.value = false;
  Toast.success(editing.value ? "规则已更新" : "规则已创建");
  await load();
  locate(rule.name);
}

async function toggleEnabled(rule: Rule, enabled: boolean) {
  togglingName.value = rule.name;
  try {
    await redirectsApi.update(rule.name, { ...rule, enabled });
    await load();
  } finally {
    togglingName.value = null;
  }
}

function confirmDelete(rule: Rule) {
  Dialog.warning({
    title: "删除规则",
    description: `确定删除 ${decode(rule.fromPath)} 的重定向规则吗？删除后立即生效。`,
    confirmType: "danger",
    confirmText: "删除",
    cancelText: "取消",
    async onConfirm() {
      await redirectsApi.remove(rule.name);
      Toast.success("已删除");
      await load();
    },
  });
}

function confirmDeleteSelected() {
  const names = [...selected.value];
  Dialog.warning({
    title: "批量删除",
    description: `确定删除选中的 ${names.length} 条规则吗？删除后立即生效。`,
    confirmType: "danger",
    confirmText: "删除",
    cancelText: "取消",
    async onConfirm() {
      const count = await redirectsApi.removeMany(names);
      Toast.success(`已删除 ${count} 条规则`);
      selected.value = new Set();
      await load();
    },
  });
}

async function exportCsv() {
  const blob = await redirectsApi.exportCsv();
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = "redirect-rules.csv";
  link.click();
  URL.revokeObjectURL(link.href);
}

function locate(name: string) {
  const index = filtered.value.findIndex((rule) => rule.name === name);
  if (index < 0) {
    keyword.value = "";
    matchFilter.value = "";
    statusFilter.value = "";
  }
  const position = filtered.value.findIndex((rule) => rule.name === name);
  if (position >= 0) page.value = Math.floor(position / size.value) + 1;
  highlighted.value = name;
  setTimeout(() => {
    if (highlighted.value === name) highlighted.value = null;
  }, 2500);
}

const SHORT_STATUS: Record<number, string> = {
  301: "301 永久",
  302: "302 临时",
  307: "307 临时",
  308: "308 永久",
  410: "410 已删除",
};

function statusLabel(code: number) {
  return SHORT_STATUS[code] ?? String(code);
}

function statusTheme(code: number) {
  return code === 410 ? "danger" : "default";
}

function decode(value?: string | null) {
  if (!value) return "";
  try {
    return decodeURI(value);
  } catch {
    return value;
  }
}

async function onBulkSaved() {
  bulkVisible.value = false;
  await load();
}

async function onImported() {
  importVisible.value = false;
  await load();
}
</script>

<template>
  <div class="redirects">
    <div v-if="!pluginEnabled" class="redirects-banner">
      重定向目前在「基础设置」里是关闭状态，下面的规则不会生效。
    </div>

    <section class="redirects-section">
      <h3 class="redirects-title">测试地址</h3>
      <UrlTester @locate="locate" @create="(path: string) => openCreate({ fromPath: path })" />
    </section>

    <section class="redirects-section">
      <div class="redirects-header">
        <h3 class="redirects-title">
          重定向规则
          <span class="redirects-count">
            共 {{ stats.total }} 条<template v-if="stats.disabled">，停用 {{ stats.disabled }} 条</template>
          </span>
          <button v-if="stats.loops" type="button" class="redirects-loop-hint" @click="statusFilter = 'loop'">
            {{ stats.loops }} 条规则构成循环，已自动跳过
          </button>
        </h3>
        <VSpace class="redirects-actions">
          <VButton size="sm" @click="importVisible = true">导入 CSV</VButton>
          <VButton size="sm" :disabled="!stats.total" @click="exportCsv">导出 CSV</VButton>
          <VButton size="sm" @click="bulkVisible = true">批量添加</VButton>
          <VButton size="sm" type="secondary" @click="openCreate()">
            <template #icon><IconAddCircle /></template>
            新建规则
          </VButton>
        </VSpace>
      </div>

      <div class="redirects-toolbar">
        <input v-model="keyword" class="redirects-input" placeholder="搜索来源、目标或备注" />
        <select v-model="matchFilter" class="redirects-select">
          <option value="">全部匹配方式</option>
          <option value="EXACT">精确匹配</option>
          <option value="DIRECTORY">目录匹配</option>
        </select>
        <select v-model="statusFilter" class="redirects-select">
          <option value="">全部状态</option>
          <option v-for="option in STATUS_OPTIONS" :key="option.value" :value="String(option.value)">
            {{ option.label }}
          </option>
          <option value="disabled">已停用</option>
          <option value="loop">构成循环</option>
        </select>
        <VButton size="sm" :loading="loading" @click="load">
          <template #icon><IconRefreshLine /></template>
        </VButton>
        <VButton v-if="selected.size" size="sm" type="danger" @click="confirmDeleteSelected">
          <template #icon><IconDeleteBin /></template>
          删除选中（{{ selected.size }}）
        </VButton>
      </div>

      <VLoading v-if="loading && !rules.length" />
      <VEmpty
        v-else-if="!rules.length"
        title="还没有重定向规则"
        message="可以逐条新建，也可以批量粘贴或导入 CSV"
      >
        <template #actions>
          <VSpace>
            <VButton @click="bulkVisible = true">批量添加</VButton>
            <VButton type="secondary" @click="openCreate()">新建规则</VButton>
          </VSpace>
        </template>
      </VEmpty>
      <VEmpty v-else-if="!filtered.length" title="没有符合条件的规则" message="换个关键词或筛选条件试试" />

      <div v-else class="redirects-table-wrap">
        <table class="redirects-table">
          <thead>
            <tr>
              <th class="col-check">
                <input type="checkbox" :checked="allOnPageSelected"
                  @change="toggleAllOnPage(($event.target as HTMLInputElement).checked)" />
              </th>
              <th>来源路径</th>
              <th>目标地址</th>
              <th class="col-match">匹配</th>
              <th class="col-status">状态码</th>
              <th>备注</th>
              <th class="col-enabled">启用</th>
              <th class="col-ops">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="rule in pageItems"
              :key="rule.name"
              :class="{ 'is-disabled': !rule.enabled, 'is-highlighted': highlighted === rule.name }"
            >
              <td class="col-check">
                <input type="checkbox" :checked="selected.has(rule.name)"
                  @change="toggleOne(rule.name, ($event.target as HTMLInputElement).checked)" />
              </td>
              <td class="col-path" :title="decode(rule.fromPath)">
                <span class="path">{{ decode(rule.fromPath) }}</span>
                <VTag v-if="rule.skippedForLoop" theme="danger">循环，已跳过</VTag>
              </td>
              <td class="col-path" :title="decode(rule.toPath)">
                <span v-if="rule.statusCode === 410" class="muted">—</span>
                <span v-else class="path">{{ decode(rule.toPath) }}</span>
              </td>
              <td class="col-match">{{ rule.matchType === "DIRECTORY" ? "目录" : "精确" }}</td>
              <td class="col-status"><VTag :theme="statusTheme(rule.statusCode)">{{ statusLabel(rule.statusCode) }}</VTag></td>
              <td class="col-note" :title="rule.note ?? ''">{{ rule.note }}</td>
              <td class="col-enabled">
                <VSwitch :model-value="rule.enabled" :loading="togglingName === rule.name"
                  @change="(value: boolean) => toggleEnabled(rule, value)" />
              </td>
              <td class="col-ops">
                <button type="button" class="redirects-op" @click="openEdit(rule)">编辑</button>
                <button type="button" class="redirects-op redirects-op--danger" @click="confirmDelete(rule)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <VPagination
        v-if="filtered.length > size"
        v-model:page="page"
        v-model:size="size"
        :total="filtered.length"
        :size-options="[20, 50, 100, 200]"
        page-label="页"
        size-label="条 / 页"
        :total-label="`共 ${filtered.length} 项数据`"
      />
    </section>

    <RuleEditModal :visible="editVisible" :rule="editing" :prefill="editPrefill"
      @close="editVisible = false" @saved="onSaved" />
    <BulkAddModal :visible="bulkVisible" @close="bulkVisible = false" @saved="onBulkSaved" />
    <ImportModal :visible="importVisible" @close="importVisible = false" @saved="onImported" />
  </div>
</template>

<style scoped>
.redirects {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 16px;
  color: #111827;
}
.redirects-banner {
  padding: 10px 14px;
  border: 1px solid #fde68a;
  border-radius: 6px;
  background: #fffbeb;
  color: #92400e;
  font-size: 13px;
}
.redirects-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.redirects-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.redirects-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.redirects-count {
  font-size: 13px;
  font-weight: 400;
  color: #6b7280;
}
.redirects-loop-hint {
  padding: 2px 8px;
  border: 0;
  border-radius: 999px;
  background: #fee2e2;
  color: #b91c1c;
  font-size: 12px;
  cursor: pointer;
}
.redirects-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.redirects-input,
.redirects-select {
  box-sizing: border-box;
  height: 32px;
  padding: 0 10px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  background: #fff;
  font-size: 13px;
  outline: none;
}
.redirects-input {
  flex: 1 1 240px;
  min-width: 180px;
  max-width: 360px;
}
.redirects-input:focus,
.redirects-select:focus {
  border-color: #4ccba0;
  box-shadow: 0 0 0 1px #4ccba0;
}
.redirects-table-wrap {
  overflow-x: auto;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
}
.redirects-table {
  width: 100%;
  min-width: 860px;
  border-collapse: collapse;
  table-layout: fixed;
  font-size: 13px;
}
.redirects-table th {
  padding: 10px 12px;
  border-bottom: 1px solid #e5e7eb;
  background: #f9fafb;
  color: #4b5563;
  font-weight: 500;
  text-align: left;
  white-space: nowrap;
}
.redirects-table td {
  padding: 8px 12px;
  border-bottom: 1px solid #f3f4f6;
  vertical-align: middle;
}
.redirects-table tbody tr:last-child td {
  border-bottom: 0;
}
.redirects-table tbody tr:hover {
  background: #f9fafb;
}
.redirects-table tr.is-disabled td:not(.col-enabled):not(.col-ops):not(.col-check) {
  opacity: 0.5;
}
.redirects-table tr.is-highlighted {
  background: #ecfdf5;
  transition: background 0.3s;
}
.col-check {
  width: 36px;
}
.col-match {
  width: 64px;
}
.col-status {
  width: 110px;
  white-space: nowrap;
}
.col-enabled {
  width: 64px;
}
.col-ops {
  width: 96px;
  white-space: nowrap;
}
.col-path,
.col-note {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.col-path {
  display: table-cell;
}
.col-path .path {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12.5px;
  margin-right: 6px;
}
.col-note {
  color: #6b7280;
}
.muted {
  color: #9ca3af;
}
.redirects-op {
  padding: 0;
  margin-right: 12px;
  border: 0;
  background: none;
  color: #2563eb;
  font-size: 13px;
  cursor: pointer;
}
.redirects-op:last-child {
  margin-right: 0;
}
.redirects-op:hover {
  text-decoration: underline;
}
.redirects-op--danger {
  color: #dc2626;
}
</style>

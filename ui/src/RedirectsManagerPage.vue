<script setup>
import { computed, onMounted, ref } from "vue";

const API_BASE = "/apis/console.api.redirects.halo.run/v1alpha1/plugins/redirects";

const loading = ref(true);
const saving = ref(false);
const importing = ref(false);
const exporting = ref("");
const importMode = ref("replace");
const importFile = ref(null);
const notice = ref({ type: "info", text: "首次加载中..." });
const state = ref(createDefaultState());

const hasRules = computed(() => state.value.rules.length > 0);

onMounted(() => {
  loadSettings();
});

function createDefaultState() {
  return {
    enabled: true,
    preserveQueryString: true,
    rules: [createRule()]
  };
}

function createRule() {
  return {
    fromPath: "",
    toPath: "",
    statusCode: 301,
    note: "",
    matchType: "EXACT"
  };
}

async function loadSettings() {
  loading.value = true;

  try {
    const response = await fetch(`${API_BASE}/settings`, {
      credentials: "same-origin"
    });

    if (!response.ok) {
      throw new Error(await readError(response));
    }

    const payload = await response.json();
    state.value = {
      enabled: payload.enabled ?? true,
      preserveQueryString: payload.preserveQueryString ?? true,
      rules: (payload.rules ?? []).map(normalizeRule)
    };

    if (state.value.rules.length === 0) {
      state.value.rules.push(createRule());
    }

    notice.value = {
      type: "info",
      text: "已加载当前配置。目录匹配会保留后续子路径。"
    };
  } catch (error) {
    notice.value = {
      type: "danger",
      text: error.message || "读取当前配置失败。"
    };
  } finally {
    loading.value = false;
  }
}

async function saveSettings() {
  const validRules = sanitizedRules();
  if (validRules.length === 0) {
    notice.value = {
      type: "warning",
      text: "至少保留一条完整规则，或先新增后再保存。"
    };
    return;
  }

  saving.value = true;

  try {
    const response = await fetch(`${API_BASE}/settings`, {
      method: "PUT",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        enabled: !!state.value.enabled,
        preserveQueryString: !!state.value.preserveQueryString,
        rules: validRules
      })
    });

    if (!response.ok) {
      throw new Error(await readError(response));
    }

    const payload = await response.json();
    state.value = {
      enabled: payload.enabled ?? true,
      preserveQueryString: payload.preserveQueryString ?? true,
      rules: (payload.rules ?? []).map(normalizeRule)
    };

    if (state.value.rules.length === 0) {
      state.value.rules.push(createRule());
    }

    notice.value = {
      type: "success",
      text: `已保存 ${sanitizedRules().length} 条规则。`
    };
  } catch (error) {
    notice.value = {
      type: "danger",
      text: error.message || "保存失败。"
    };
  } finally {
    saving.value = false;
  }
}

async function importRules() {
  if (!importFile.value) {
    notice.value = {
      type: "warning",
      text: "请选择一个 CSV 或 XLSX 文件。"
    };
    return;
  }

  importing.value = true;

  try {
    const formData = new FormData();
    formData.append("file", importFile.value);

    const response = await fetch(
      `${API_BASE}/rules/import?mode=${encodeURIComponent(importMode.value)}`,
      {
        method: "POST",
        credentials: "same-origin",
        body: formData
      }
    );

    if (!response.ok) {
      throw new Error(await readError(response));
    }

    const payload = await response.json();
    await loadSettings();
    importFile.value = null;
    const input = document.getElementById("redirects-import-file");
    if (input) {
      input.value = "";
    }

    notice.value = {
      type: "success",
      text: `导入完成：新增 ${payload.importedCount} 条，当前共 ${payload.totalRuleCount} 条。`
    };
  } catch (error) {
    notice.value = {
      type: "danger",
      text: error.message || "导入失败。"
    };
  } finally {
    importing.value = false;
  }
}

async function exportRules(format) {
  exporting.value = format;

  try {
    const response = await fetch(
      `${API_BASE}/rules/export?format=${encodeURIComponent(format)}`,
      {
        credentials: "same-origin"
      }
    );

    if (!response.ok) {
      throw new Error(await readError(response));
    }

    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `redirect-rules.${format}`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);

    notice.value = {
      type: "success",
      text: `已导出 ${format.toUpperCase()} 文件。`
    };
  } catch (error) {
    notice.value = {
      type: "danger",
      text: error.message || "导出失败。"
    };
  } finally {
    exporting.value = "";
  }
}

function addRule() {
  state.value.rules.push(createRule());
}

function duplicateRule(index) {
  const source = state.value.rules[index];
  state.value.rules.splice(index + 1, 0, {
    ...normalizeRule(source),
    note: source.note ? `${source.note}（副本）` : ""
  });
}

function removeRule(index) {
  if (state.value.rules.length === 1) {
    state.value.rules.splice(0, 1, createRule());
    return;
  }

  state.value.rules.splice(index, 1);
}

function onFileChange(event) {
  const [file] = event.target.files || [];
  importFile.value = file || null;
}

function normalizeRule(rule) {
  return {
    fromPath: rule?.fromPath || "",
    toPath: rule?.toPath || "",
    statusCode: Number(rule?.statusCode) === 302 ? 302 : 301,
    note: rule?.note || "",
    matchType: rule?.matchType === "DIRECTORY" ? "DIRECTORY" : "EXACT"
  };
}

function sanitizedRules() {
  return state.value.rules
    .map(normalizeRule)
    .filter((rule) => rule.fromPath.trim() && rule.toPath.trim());
}

async function readError(response) {
  const text = await response.text();

  if (!text) {
    return "请求失败。";
  }

  try {
    const parsed = JSON.parse(text);
    if (parsed.message) {
      return parsed.message;
    }
    if (parsed.error) {
      return parsed.error;
    }
  } catch (error) {
    return text;
  }

  return text;
}
</script>

<template>
  <div class="redirects-page">
    <div class="redirects-shell">
      <section class="redirects-hero">
        <div>
          <p class="redirects-eyebrow">Halo Plugin Console</p>
          <h1 class="redirects-title">重定向规则面板</h1>
          <p class="redirects-subtitle">
            在这里直接维护规则、导入导出 CSV / XLSX，并配置目录级跳转。目录匹配示例：
            <code>/docs</code> 到 <code>/knowledge</code> 会把
            <code>/docs/guide/install</code> 自动变成 <code>/knowledge/guide/install</code>。
          </p>
        </div>
        <div class="redirects-actions">
          <button
            class="redirects-button redirects-button--secondary"
            type="button"
            :disabled="loading"
            @click="loadSettings"
          >
            重新加载
          </button>
          <button
            class="redirects-button redirects-button--primary"
            type="button"
            :disabled="loading || saving"
            @click="saveSettings"
          >
            {{ saving ? "保存中..." : "保存全部规则" }}
          </button>
        </div>
      </section>

      <div class="redirects-grid">
        <section class="redirects-card">
          <div class="redirects-card-head">
            <div>
              <h2 class="redirects-card-title">全局设置</h2>
              <p class="redirects-card-note">
                保存后会写回插件配置，批量文本规则会自动转换为可视化规则列表。
              </p>
            </div>
            <div class="redirects-stats">
              <span class="redirects-stats-value">{{ sanitizedRules().length }}</span>
              <span class="redirects-stats-label">有效规则</span>
            </div>
          </div>

          <div class="redirects-card-body">
            <div class="redirects-form">
              <div class="redirects-switch">
                <div class="redirects-switch-head">
                  <label class="redirects-label" for="redirects-enabled">启用重定向</label>
                  <input id="redirects-enabled" v-model="state.enabled" type="checkbox">
                </div>
                <p class="redirects-help">关闭后不会执行任何规则，但列表仍会保留。</p>
              </div>

              <div class="redirects-switch">
                <div class="redirects-switch-head">
                  <label class="redirects-label" for="redirects-preserve-query">保留原查询参数</label>
                  <input
                    id="redirects-preserve-query"
                    v-model="state.preserveQueryString"
                    type="checkbox"
                  >
                </div>
                <p class="redirects-help">例如把原始链接里的 UTM 参数继续拼到目标地址后面。</p>
              </div>

              <div class="redirects-field">
                <label class="redirects-label" for="redirects-import-file">导入文件</label>
                <input
                  id="redirects-import-file"
                  class="redirects-file"
                  type="file"
                  accept=".csv,.xlsx"
                  @change="onFileChange"
                >
                <p class="redirects-help">支持 `fromPath,toPath,statusCode,note,matchType` 表头。</p>
              </div>

              <div class="redirects-field">
                <label class="redirects-label" for="redirects-import-mode">导入模式</label>
                <select id="redirects-import-mode" v-model="importMode" class="redirects-select">
                  <option value="replace">覆盖现有规则</option>
                  <option value="append">追加到现有规则</option>
                </select>
              </div>

              <div class="redirects-toolbar">
                <button
                  class="redirects-button redirects-button--primary"
                  type="button"
                  :disabled="importing"
                  @click="importRules"
                >
                  {{ importing ? "导入中..." : "导入文件" }}
                </button>
                <button
                  class="redirects-button redirects-button--secondary"
                  type="button"
                  :disabled="exporting === 'csv'"
                  @click="exportRules('csv')"
                >
                  {{ exporting === "csv" ? "导出中..." : "导出 CSV" }}
                </button>
                <button
                  class="redirects-button redirects-button--secondary"
                  type="button"
                  :disabled="exporting === 'xlsx'"
                  @click="exportRules('xlsx')"
                >
                  {{ exporting === "xlsx" ? "导出中..." : "导出 XLSX" }}
                </button>
              </div>
            </div>

            <div class="redirects-banner" :class="`redirects-banner--${notice.type}`">
              {{ notice.text }}
            </div>
          </div>
        </section>

        <section class="redirects-card">
          <div class="redirects-card-head">
            <div>
              <h2 class="redirects-card-title">规则列表</h2>
              <p class="redirects-card-note">
                精确匹配只命中单一路径；目录匹配会按最长前缀优先，自动保留后续子路径。
              </p>
            </div>
            <div class="redirects-inline">
              <span class="redirects-badge">
                <span class="redirects-dot" />
                {{ state.enabled ? "已启用" : "已停用" }}
              </span>
              <button
                class="redirects-button redirects-button--secondary"
                type="button"
                @click="addRule"
              >
                新增规则
              </button>
            </div>
          </div>

          <div class="redirects-card-body">
            <div v-if="loading" class="redirects-banner redirects-banner--info">
              正在读取配置...
            </div>

            <div v-else-if="!hasRules" class="redirects-empty">
              当前没有规则，先添加一条开始。
            </div>

            <div v-else class="redirects-table-wrap">
              <table class="redirects-table">
                <thead>
                  <tr>
                    <th style="width: 22%">来源路径</th>
                    <th style="width: 24%">目标地址</th>
                    <th style="width: 14%">匹配方式</th>
                    <th style="width: 12%">状态码</th>
                    <th style="width: 18%">备注</th>
                    <th style="width: 10%">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(rule, index) in state.rules" :key="`${index}-${rule.fromPath}-${rule.toPath}`">
                    <td>
                      <input
                        v-model="rule.fromPath"
                        class="redirects-input redirects-mini"
                        type="text"
                        placeholder="/old-path 或 /docs"
                      >
                    </td>
                    <td>
                      <input
                        v-model="rule.toPath"
                        class="redirects-input redirects-mini"
                        type="text"
                        placeholder="/new-path 或 https://example.com"
                      >
                    </td>
                    <td>
                      <select v-model="rule.matchType" class="redirects-select redirects-mini">
                        <option value="EXACT">精确匹配</option>
                        <option value="DIRECTORY">目录匹配</option>
                      </select>
                    </td>
                    <td>
                      <select v-model.number="rule.statusCode" class="redirects-select redirects-mini">
                        <option :value="301">301</option>
                        <option :value="302">302</option>
                      </select>
                    </td>
                    <td>
                      <input
                        v-model="rule.note"
                        class="redirects-input redirects-mini"
                        type="text"
                        placeholder="可选备注"
                      >
                    </td>
                    <td>
                      <div class="redirects-toolbar">
                        <button
                          class="redirects-button redirects-button--secondary"
                          type="button"
                          @click="duplicateRule(index)"
                        >
                          复制
                        </button>
                        <button
                          class="redirects-button redirects-button--danger"
                          type="button"
                          @click="removeRule(index)"
                        >
                          删除
                        </button>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

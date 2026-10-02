<script setup lang="ts">
import { VButton } from "@halo-dev/components";
import { ref } from "vue";
import { redirectsApi } from "@/api";
import type { TestResult } from "@/types";

const emit = defineEmits<{ (e: "locate", ruleName: string): void; (e: "create", path: string): void }>();

const url = ref("");
const testing = ref(false);
const result = ref<TestResult | null>(null);

async function run() {
  if (!url.value.trim()) return;
  testing.value = true;
  try {
    result.value = await redirectsApi.test(url.value.trim());
  } catch {
    result.value = null;
  } finally {
    testing.value = false;
  }
}

function decode(value: string | null) {
  if (!value) return "";
  try {
    return decodeURI(value);
  } catch {
    return value;
  }
}
</script>

<template>
  <div class="redirects-tester">
    <form class="redirects-tester__bar" @submit.prevent="run">
      <input
        v-model="url"
        class="redirects-input"
        placeholder="测试地址：粘贴完整 URL 或路径，例如 /archives/旧文章?utm_source=x"
      />
      <VButton :loading="testing" :disabled="!url.trim()" @click="run">测试</VButton>
    </form>
    <div v-if="result" class="redirects-tester__result">
      <template v-if="!result.pluginEnabled">
        <span class="redirects-dot redirects-dot--warn" />重定向已在「基础设置」中关闭，访问不会跳转。
      </template>
      <template v-else-if="!result.matched">
        <span class="redirects-dot" />
        <span><code>{{ decode(result.path) }}</code> 没有命中任何规则，会正常访问原页面。</span>
        <button type="button" class="redirects-link" @click="emit('create', decode(result.path))">
          为它新建规则
        </button>
      </template>
      <ol v-else class="redirects-tester__hops">
        <li v-for="(hop, index) in result.hops" :key="index">
          <span class="redirects-dot redirects-dot--ok" />
          <code>{{ decode(hop.path) }}</code>
          <span class="redirects-arrow">→</span>
          <strong>{{ hop.statusCode }}</strong>
          <template v-if="hop.location">
            <span class="redirects-arrow">→</span><code>{{ decode(hop.location) }}</code>
          </template>
          <span v-else class="redirects-muted">（已删除，不跳转）</span>
          <span class="redirects-muted">
            命中 {{ hop.directory ? "目录" : "精确" }}规则
            <button v-if="hop.ruleName" type="button" class="redirects-link" @click="emit('locate', hop.ruleName)">
              {{ decode(hop.fromPath) }}
            </button>
            <template v-else>{{ decode(hop.fromPath) }}</template>
          </span>
        </li>
        <li v-if="result.hops.length > 1" class="redirects-muted">
          共 {{ result.hops.length }} 次跳转。链条越短越好，可以把第一条规则的目标直接改成最终地址。
        </li>
      </ol>
    </div>
  </div>
</template>

<style scoped>
.redirects-tester {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.redirects-tester__bar {
  display: flex;
  gap: 8px;
}
.redirects-tester__result {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 6px;
  background: #f9fafb;
  font-size: 13px;
  color: #374151;
}
.redirects-tester__hops {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.redirects-tester__hops li {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
code {
  padding: 1px 5px;
  border-radius: 4px;
  background: #eef2f7;
  font-size: 12px;
  word-break: break-all;
}
.redirects-arrow {
  color: #9ca3af;
}
.redirects-muted {
  color: #6b7280;
}
.redirects-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #9ca3af;
  flex: none;
}
.redirects-dot--ok {
  background: #22c55e;
}
.redirects-dot--warn {
  background: #f59e0b;
}
.redirects-link {
  padding: 0;
  border: 0;
  background: none;
  color: #2563eb;
  font: inherit;
  cursor: pointer;
}
.redirects-link:hover {
  text-decoration: underline;
}
.redirects-input {
  box-sizing: border-box;
  flex: 1;
  min-width: 0;
  height: 36px;
  padding: 0 12px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 14px;
  outline: none;
}
.redirects-input:focus {
  border-color: #4ccba0;
  box-shadow: 0 0 0 1px #4ccba0;
}
</style>

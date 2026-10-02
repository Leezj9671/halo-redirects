<script setup lang="ts">
import { Toast, VButton, VModal, VSpace } from "@halo-dev/components";
import { ref, watch } from "vue";
import { redirectsApi } from "@/api";

const props = defineProps<{ visible: boolean }>();
const emit = defineEmits<{ (e: "close"): void; (e: "saved"): void }>();

const file = ref<File | null>(null);
const mode = ref<"append" | "replace">("append");
const saving = ref(false);
const input = ref<HTMLInputElement | null>(null);

watch(
  () => props.visible,
  (visible) => {
    if (!visible) return;
    file.value = null;
    mode.value = "append";
    if (input.value) input.value.value = "";
  }
);

function onFileChange(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null;
}

async function submit() {
  if (!file.value) return;
  saving.value = true;
  try {
    const result = await redirectsApi.importCsv(file.value, mode.value);
    Toast.success(
      `导入完成：新增 ${result.createdCount} 条，更新 ${result.updatedCount} 条` +
        (result.skippedCount ? `，跳过 ${result.skippedCount} 条无效规则` : "") +
        `，当前共 ${result.totalRuleCount} 条`
    );
    emit("saved");
  } catch {
    // Shown by the console.
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <VModal :visible="visible" :width="560" title="导入 CSV" @close="emit('close')"
    @update:visible="(value: boolean) => !value && emit('close')">
    <div class="redirects-import">
      <p>
        表头：<code>fromPath,toPath,statusCode,note,matchType</code>（只有前两列必填）。Excel 中可
        「另存为 → CSV UTF-8」。
      </p>
      <input ref="input" type="file" accept=".csv,text/csv" @change="onFileChange" />
      <fieldset>
        <label><input v-model="mode" type="radio" value="append" /> 追加（来源路径相同的规则会被更新）</label>
        <label><input v-model="mode" type="radio" value="replace" /> 替换（先删除全部现有规则）</label>
      </fieldset>
    </div>
    <template #footer>
      <VSpace>
        <VButton :type="mode === 'replace' ? 'danger' : 'secondary'" :loading="saving" :disabled="!file" @click="submit">
          {{ mode === "replace" ? "替换导入" : "导入" }}
        </VButton>
        <VButton @click="emit('close')">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>

<style scoped>
.redirects-import {
  display: flex;
  flex-direction: column;
  gap: 14px;
  font-size: 13px;
  color: #374151;
}
.redirects-import p {
  margin: 0;
  line-height: 1.7;
}
.redirects-import code {
  padding: 1px 4px;
  border-radius: 4px;
  background: #f3f4f6;
  font-size: 12px;
}
.redirects-import fieldset {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  padding: 0;
  border: 0;
}
.redirects-import label {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}
</style>

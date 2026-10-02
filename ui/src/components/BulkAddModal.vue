<script setup lang="ts">
import { Toast, VButton, VModal, VSpace } from "@halo-dev/components";
import { ref, watch } from "vue";
import { redirectsApi } from "@/api";

const props = defineProps<{ visible: boolean }>();
const emit = defineEmits<{ (e: "close"): void; (e: "saved"): void }>();

const text = ref("");
const saving = ref(false);

watch(
  () => props.visible,
  (visible) => {
    if (visible) text.value = "";
  }
);

const placeholder = `/old-post -> /new-post
/docs/legacy -> https://example.com/docs -> 302
/docs -> /knowledge -> 301 -> DIRECTORY
/deleted-post -> 410
/campaign,/landing,302,活动页`;

async function submit() {
  if (!text.value.trim()) return;
  saving.value = true;
  try {
    const result = await redirectsApi.bulkAdd(text.value);
    const parts = [`新增 ${result.createdCount} 条`];
    if (result.updatedCount) parts.push(`更新 ${result.updatedCount} 条`);
    if (result.skippedCount) parts.push(`跳过 ${result.skippedCount} 条无效规则`);
    Toast.success(parts.join("，"));
    emit("saved");
  } catch {
    // Shown by the console.
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <VModal :visible="visible" :width="720" title="批量添加规则" @close="emit('close')"
    @update:visible="(value: boolean) => !value && emit('close')">
    <div class="redirects-bulk">
      <p class="redirects-bulk__help">
        每行一条：<code>来源 -&gt; 目标 [-&gt; 状态码] [-&gt; DIRECTORY]</code>，或逗号分隔
        <code>来源,目标,状态码,备注,匹配方式</code>。状态码默认 301，<code>#</code> 开头为注释。
        与已有规则来源路径相同时会更新原规则。
      </p>
      <textarea v-model="text" class="redirects-bulk__input" rows="12" :placeholder="placeholder" />
    </div>
    <template #footer>
      <VSpace>
        <VButton type="secondary" :loading="saving" :disabled="!text.trim()" @click="submit">添加</VButton>
        <VButton @click="emit('close')">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>

<style scoped>
.redirects-bulk__help {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.7;
  color: #4b5563;
}
.redirects-bulk__help code {
  padding: 1px 4px;
  border-radius: 4px;
  background: #f3f4f6;
  font-size: 12px;
}
.redirects-bulk__input {
  box-sizing: border-box;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font: 13px/1.6 ui-monospace, SFMono-Regular, Menlo, monospace;
  resize: vertical;
  outline: none;
}
.redirects-bulk__input:focus {
  border-color: #4ccba0;
  box-shadow: 0 0 0 1px #4ccba0;
}
</style>

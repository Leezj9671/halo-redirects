<script setup lang="ts">
import { VButton, VModal, VSpace } from "@halo-dev/components";
import { computed, ref, watch } from "vue";
import { redirectsApi } from "@/api";
import { MATCH_OPTIONS, STATUS_OPTIONS, type Rule, type RuleInput } from "@/types";

const props = defineProps<{ visible: boolean; rule?: Rule | null; prefill?: Partial<RuleInput> }>();
const emit = defineEmits<{ (e: "close"): void; (e: "saved", rule: Rule): void }>();

const emptyForm = (): RuleInput => ({
  fromPath: "",
  toPath: "",
  matchType: "EXACT",
  statusCode: 301,
  note: "",
  enabled: true,
});

const form = ref<RuleInput>(emptyForm());
const saving = ref(false);
const isGone = computed(() => Number(form.value.statusCode) === 410);
const isEditing = computed(() => !!props.rule);

watch(
  () => props.visible,
  (visible) => {
    if (!visible) return;
    form.value = props.rule
      ? {
          fromPath: props.rule.fromPath,
          toPath: props.rule.toPath ?? "",
          matchType: props.rule.matchType,
          statusCode: props.rule.statusCode,
          note: props.rule.note ?? "",
          enabled: props.rule.enabled,
        }
      : { ...emptyForm(), ...props.prefill };
  },
  { immediate: true }
);

async function submit() {
  saving.value = true;
  try {
    const input: RuleInput = {
      ...form.value,
      statusCode: Number(form.value.statusCode),
      toPath: isGone.value ? null : form.value.toPath,
    };
    const saved = props.rule
      ? await redirectsApi.update(props.rule.name, input)
      : await redirectsApi.create(input);
    emit("saved", saved);
  } catch {
    // The console already shows the server's error message.
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <VModal
    :visible="visible"
    :width="640"
    :title="isEditing ? '编辑规则' : '新建规则'"
    @update:visible="(value: boolean) => !value && emit('close')"
    @close="emit('close')"
  >
    <FormKit id="redirect-rule-form" type="form" :actions="false" :config="{ validationVisibility: 'submit' }" @submit="submit">
      <FormKit
        v-model="form.fromPath"
        type="text"
        name="fromPath"
        label="来源路径"
        placeholder="/old-post"
        help="站内路径，可直接写中文；目录匹配时填写目录前缀，例如 /docs"
        validation="required"
        validation-label="来源路径"
      />
      <FormKit
        v-model="form.matchType"
        type="select"
        name="matchType"
        label="匹配方式"
        :options="MATCH_OPTIONS"
        help="目录匹配会把子路径带到目标：/docs/a → /knowledge/a"
      />
      <FormKit
        v-model="form.statusCode"
        type="select"
        name="statusCode"
        label="状态码"
        :options="STATUS_OPTIONS"
        help="一般用 301；临时活动用 302；307/308 会保留 POST 等请求方法；410 表示内容已删除"
      />
      <FormKit
        v-if="!isGone"
        v-model="form.toPath"
        type="text"
        name="toPath"
        label="目标地址"
        placeholder="/new-post 或 https://example.com/page"
        validation="required"
        validation-label="目标地址"
      />
      <FormKit v-model="form.note" type="text" name="note" label="备注" placeholder="可选，仅自己可见" />
      <FormKit
        v-model="form.enabled"
        type="checkbox"
        name="enabled"
        label="启用这条规则"
      />
    </FormKit>
    <template #footer>
      <VSpace>
        <VButton type="secondary" :loading="saving" @click="$formkit.submit('redirect-rule-form')">
          保存
        </VButton>
        <VButton @click="emit('close')">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>

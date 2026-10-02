import { axiosInstance } from "@halo-dev/api-client";
import type { ImportResult, Rule, RuleInput, RuleList, TestResult } from "./types";

const BASE = "/apis/console.api.redirects.halo.run/v1alpha1/plugins/redirects";

// Errors are shown by the console's own axios interceptor (it toasts the server's message),
// so callers only need to stop their loading state.
export const redirectsApi = {
  async list(): Promise<RuleList> {
    const { data } = await axiosInstance.get<RuleList>(`${BASE}/rules`);
    return data;
  },
  async create(input: RuleInput): Promise<Rule> {
    const { data } = await axiosInstance.post<Rule>(`${BASE}/rules`, input);
    return data;
  },
  async update(name: string, input: RuleInput): Promise<Rule> {
    const { data } = await axiosInstance.put<Rule>(
      `${BASE}/rules/${encodeURIComponent(name)}`,
      input
    );
    return data;
  },
  async remove(name: string): Promise<void> {
    await axiosInstance.delete(`${BASE}/rules/${encodeURIComponent(name)}`);
  },
  async removeMany(names: string[]): Promise<number> {
    const { data } = await axiosInstance.post<{ deletedCount: number }>(
      `${BASE}/rules/-/delete`,
      { names }
    );
    return data.deletedCount;
  },
  async bulkAdd(text: string): Promise<ImportResult> {
    const { data } = await axiosInstance.post<ImportResult>(`${BASE}/rules/-/bulk`, { text });
    return data;
  },
  async importCsv(file: File, mode: "append" | "replace"): Promise<ImportResult> {
    const form = new FormData();
    form.append("file", file);
    const { data } = await axiosInstance.post<ImportResult>(`${BASE}/rules/import`, form, {
      params: { mode },
    });
    return data;
  },
  async exportCsv(): Promise<Blob> {
    const { data } = await axiosInstance.get<Blob>(`${BASE}/rules/export`, {
      params: { format: "csv" },
      responseType: "blob",
    });
    return data;
  },
  async test(url: string): Promise<TestResult> {
    const { data } = await axiosInstance.get<TestResult>(`${BASE}/rules/-/test`, {
      params: { url },
    });
    return data;
  },
};

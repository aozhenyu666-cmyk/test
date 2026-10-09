import { readJson } from "./fsx.js";

export const ROOT = "/sdcard/Download/Operit";
const CONFIG_PATH = `${ROOT}/audit/config.json`;

export interface WorkflowSpec {
  name: string; // 按名称在 get_all_workflows 里匹配
  cadenceMin: number; // 期望调度节奏
  critical: boolean; // 关键链：FAIL 拉低总分
  expectEnabled: boolean; // 期望是启用还是停用
}

export interface FileSpec {
  path: string;
  maxAgeMin: number;
  critical: boolean;
  label: string;
}

// 默认规格。用户或流程线可用 audit/config.json 覆盖（缺字段回落默认）。
export const DEFAULT_WORKFLOWS: WorkflowSpec[] = [
  { name: "P4_Event_Sampler", cadenceMin: 15, critical: true, expectEnabled: true },
  { name: "SCHED_Watchdog", cadenceMin: 15, critical: true, expectEnabled: true },
  { name: "G2_Judge_Flow", cadenceMin: 30, critical: true, expectEnabled: true },
  { name: "S4_Lock_Queue_Worker", cadenceMin: 15, critical: true, expectEnabled: true },
  { name: "BRAIN_Tick", cadenceMin: 15, critical: true, expectEnabled: true },
  { name: "OUTBOX_Carrier", cadenceMin: 15, critical: false, expectEnabled: true },
  { name: "S3_Brief_Loop", cadenceMin: 30, critical: false, expectEnabled: false }, // 已被 BRAIN_Tick 取代
];

export const DEFAULT_FILES: FileSpec[] = [
  { path: `${ROOT}/drift/task_state.txt`, maxAgeMin: 60, critical: true, label: "当前任务投影" },
  { path: `${ROOT}/companion/brain/state.json`, maxAgeMin: 60, critical: true, label: "大脑状态" },
  { path: `${ROOT}/judge/.pack_last.txt`, maxAgeMin: 90, critical: false, label: "判断证据包" },
];

export interface AuditConfig {
  workflows: WorkflowSpec[];
  files: FileSpec[];
  judgeCadenceMin: number;
}

export async function loadConfig(): Promise<AuditConfig> {
  const saved = await readJson<Partial<AuditConfig>>(CONFIG_PATH);
  return {
    workflows: saved?.workflows ?? DEFAULT_WORKFLOWS,
    files: saved?.files ?? DEFAULT_FILES,
    judgeCadenceMin: saved?.judgeCadenceMin ?? 30,
  };
}

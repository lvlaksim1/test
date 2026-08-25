import { NativeModules, Platform } from "react-native";

import type { CycleConfig } from "@/lib/cycle-utils";

export type CycleEvent = { at: number; message: string };
export type OpenAppInfo = { label: string; packageName: string; processNames: string[] };
export type UiElementInfo = {
  sequence: number;
  depth: number;
  name: string;
  attributes: Record<string, string>;
  [key: string]: string | number | Record<string, string>;
};
export type UnityRuntimeButton = {
  id: string;
  name?: string;
  text?: string;
  active?: boolean;
  interactable?: boolean;
};
export type UnityRuntimeSnapshot = {
  ok: boolean;
  connected: boolean;
  status: string;
  screen: string | null;
  buttons: UnityRuntimeButton[];
  message?: string | null;
  agentVersion?: string;
  packageName?: string;
};
export type TimeControlStatus = {
  isShizukuRunning: boolean;
  isShizukuPermissionGranted: boolean;
  isAutomaticTimeEnabled: boolean;
  isRunning: boolean;
  completedCycles: number;
  totalCycles: number;
  nextTargetMillis: number | null;
  lastAppliedMillis: number | null;
  events: CycleEvent[];
};

type NativeTimeControl = {
  getStatus: () => Promise<TimeControlStatus>;
  requestShizukuPermission: () => Promise<boolean>;
  setAutomaticTime: (enabled: boolean) => Promise<TimeControlStatus>;
  applyTime: (targetMillis: number) => Promise<TimeControlStatus>;
  getOpenApps: () => Promise<OpenAppInfo[]>;
  inspectApp: (packageName: string) => Promise<UiElementInfo[]>;
  invokeElement: (packageName: string, bounds: string) => Promise<boolean>;
  inspectUnityRuntime: (packageName: string) => Promise<UnityRuntimeSnapshot>;
  invokeUnityRuntimeButton: (packageName: string, buttonId: string) => Promise<boolean>;
  startCycle: (config: CycleConfig) => Promise<TimeControlStatus>;
  stopCycle: () => Promise<TimeControlStatus>;
  clearEvents: () => Promise<boolean>;
};

const nativeModule = NativeModules.TimeControl as NativeTimeControl | undefined;
export const isNativeTimeControlAvailable = Platform.OS === "android" && Boolean(nativeModule);
const webStatus: TimeControlStatus = { isShizukuRunning: false, isShizukuPermissionGranted: false, isAutomaticTimeEnabled: true, isRunning: false, completedCycles: 0, totalCycles: 0, nextTargetMillis: null, lastAppliedMillis: null, events: [] };

export async function getTimeControlStatus(): Promise<TimeControlStatus> { return nativeModule ? nativeModule.getStatus() : webStatus; }
export async function requestShizukuPermission(): Promise<boolean> {
  if (!nativeModule) throw new Error("Shizuku доступен только в собранном Android APK.");
  return nativeModule.requestShizukuPermission();
}
export async function setAutomaticTime(enabled: boolean): Promise<TimeControlStatus> {
  if (!nativeModule) throw new Error("Переключатель синхронизации доступен только в собранном Android APK.");
  return nativeModule.setAutomaticTime(enabled);
}
export async function applyTime(targetMillis: number): Promise<TimeControlStatus> {
  if (!nativeModule) throw new Error("Изменение времени доступно только в собранном Android APK.");
  return nativeModule.applyTime(targetMillis);
}
export async function getOpenApps(): Promise<OpenAppInfo[]> {
  if (!nativeModule) throw new Error("Список открытых приложений доступен только в собранном Android APK.");
  return nativeModule.getOpenApps();
}
export async function inspectAppScreen(packageName: string): Promise<UiElementInfo[]> {
  if (!nativeModule) throw new Error("Просмотр элементов экрана доступен только в собранном Android APK.");
  return nativeModule.inspectApp(packageName);
}
export async function invokeAppElement(packageName: string, bounds: string): Promise<boolean> {
  if (!nativeModule) throw new Error("Дублирование кнопок доступно только в собранном Android APK.");
  return nativeModule.invokeElement(packageName, bounds);
}
export async function inspectUnityRuntime(packageName: string): Promise<UnityRuntimeSnapshot> {
  if (!nativeModule) throw new Error("Unity Runtime Inspector доступен только в собранном Android APK.");
  return nativeModule.inspectUnityRuntime(packageName);
}
export async function invokeUnityRuntimeButton(packageName: string, buttonId: string): Promise<boolean> {
  if (!nativeModule) throw new Error("Вызов Unity-кнопки доступен только в собранном Android APK.");
  return nativeModule.invokeUnityRuntimeButton(packageName, buttonId);
}
export async function startTimeCycle(config: CycleConfig): Promise<TimeControlStatus> {
  if (!nativeModule) throw new Error("Изменение времени доступно только в собранном Android APK.");
  return nativeModule.startCycle(config);
}
export async function stopTimeCycle(): Promise<TimeControlStatus> {
  if (!nativeModule) throw new Error("Изменение времени доступно только в собранном Android APK.");
  return nativeModule.stopCycle();
}
export async function clearTimeEvents(): Promise<boolean> {
  if (!nativeModule) throw new Error("Журнал доступен только в собранном Android APK.");
  return nativeModule.clearEvents();
}

import { NativeModules, Platform } from "react-native";

import type { CycleConfig } from "@/lib/cycle-utils";

export type CycleEvent = { at: number; message: string };
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

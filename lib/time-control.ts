import { NativeModules, Platform } from "react-native";
import type { CycleConfig } from "@/lib/cycle-utils";

export type CycleEvent = { at: number; message: string };
export type TimeControlStatus = {
  isSystemAccessReady: boolean;
  systemAccessDetail: string;
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
  connectSystemAccess: () => Promise<TimeControlStatus>;
  pairSystemAccess: (pairingCode: string) => Promise<TimeControlStatus>;
  openDeveloperSettings: () => Promise<boolean>;
  openDateTimeSettings: () => Promise<boolean>;
  setAutomaticTime: (enabled: boolean) => Promise<TimeControlStatus>;
  startCycle: (config: CycleConfig) => Promise<TimeControlStatus>;
  stopCycle: () => Promise<TimeControlStatus>;
  clearEvents: () => Promise<boolean>;
};

const nativeModule = NativeModules.TimeControl as NativeTimeControl | undefined;
export const isNativeTimeControlAvailable = Platform.OS === "android" && Boolean(nativeModule);

const webStatus: TimeControlStatus = {
  isSystemAccessReady: false,
  systemAccessDetail: "Системный доступ доступен только в Android APK.",
  isAutomaticTimeEnabled: true,
  isRunning: false,
  completedCycles: 0,
  totalCycles: 0,
  nextTargetMillis: null,
  lastAppliedMillis: null,
  events: [],
};

function requireNative(): NativeTimeControl {
  if (!nativeModule) throw new Error("Функция доступна только в Android APK.");
  return nativeModule;
}

export async function getTimeControlStatus() { return nativeModule ? nativeModule.getStatus() : webStatus; }
export async function connectSystemAccess() { return requireNative().connectSystemAccess(); }
export async function pairSystemAccess(code: string) { return requireNative().pairSystemAccess(code); }
export async function openDeveloperSettings() { return requireNative().openDeveloperSettings(); }
export async function openDateTimeSettings() { return requireNative().openDateTimeSettings(); }
export async function setAutomaticTime(enabled: boolean) { return requireNative().setAutomaticTime(enabled); }
export async function startTimeCycle(config: CycleConfig) { return requireNative().startCycle(config); }
export async function stopTimeCycle() { return requireNative().stopCycle(); }
export async function clearTimeEvents() { return requireNative().clearEvents(); }

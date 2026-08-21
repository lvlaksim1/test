import { NativeModules, Platform } from "react-native";

import type { CycleConfig } from "@/lib/cycle-utils";

export type CycleEvent = {
  at: number;
  message: string;
};

export type AccessibilityStatus = {
  isAccessibilityEnabled: boolean;
  isRunning: boolean;
  completedCycles: number;
  totalCycles: number;
  nextTargetMillis: number | null;
  events: CycleEvent[];
};

type NativeTimeAccessibility = {
  getStatus: () => Promise<AccessibilityStatus>;
  openAccessibilitySettings: () => Promise<boolean>;
  startCycle: (config: CycleConfig) => Promise<AccessibilityStatus>;
  stopCycle: () => Promise<AccessibilityStatus>;
  clearEvents: () => Promise<boolean>;
};

const nativeModule = NativeModules.TimeAccessibility as NativeTimeAccessibility | undefined;

export const isNativeAccessibilityAvailable = Platform.OS === "android" && Boolean(nativeModule);

const webStatus: AccessibilityStatus = {
  isAccessibilityEnabled: false,
  isRunning: false,
  completedCycles: 0,
  totalCycles: 0,
  nextTargetMillis: null,
  events: [],
};

export async function getAccessibilityStatus(): Promise<AccessibilityStatus> {
  return nativeModule ? nativeModule.getStatus() : webStatus;
}

export async function openAccessibilitySettings(): Promise<boolean> {
  if (!nativeModule) throw new Error("Служба доступности доступна только в собранном Android APK.");
  return nativeModule.openAccessibilitySettings();
}

export async function startAccessibilityCycle(config: CycleConfig): Promise<AccessibilityStatus> {
  if (!nativeModule) throw new Error("Служба доступности доступна только в собранном Android APK.");
  return nativeModule.startCycle(config);
}

export async function stopAccessibilityCycle(): Promise<AccessibilityStatus> {
  if (!nativeModule) throw new Error("Служба доступности доступна только в собранном Android APK.");
  return nativeModule.stopCycle();
}

export async function clearAccessibilityEvents(): Promise<boolean> {
  if (!nativeModule) throw new Error("Служба доступности доступна только в собранном Android APK.");
  return nativeModule.clearEvents();
}

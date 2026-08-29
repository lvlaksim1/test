import AsyncStorage from "@react-native-async-storage/async-storage";

import type { CycleForm } from "@/lib/cycle-utils";
import type { SavedConfiguration } from "@/features/time-machine/form-utils";

const FORM_STORAGE_KEY = "time-machine-form-v2";
const CONFIGURATIONS_STORAGE_KEY = "time-machine-configurations-v1";
const ACTIVE_CONFIGURATION_STORAGE_KEY = "time-machine-active-configuration-v1";

export async function loadStoredForm(): Promise<Partial<CycleForm> | null> {
  const stored = await AsyncStorage.getItem(FORM_STORAGE_KEY);
  if (!stored) return null;
  const parsed = JSON.parse(stored) as unknown;
  return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed as Partial<CycleForm> : null;
}

export async function saveStoredForm(form: CycleForm): Promise<void> {
  await AsyncStorage.setItem(FORM_STORAGE_KEY, JSON.stringify(form));
}

export async function loadStoredConfigurations(): Promise<SavedConfiguration[]> {
  const stored = await AsyncStorage.getItem(CONFIGURATIONS_STORAGE_KEY);
  if (!stored) return [];
  const parsed = JSON.parse(stored) as unknown;
  return Array.isArray(parsed) ? parsed as SavedConfiguration[] : [];
}

export async function loadStoredActiveConfiguration(): Promise<string | null> {
  const stored = await AsyncStorage.getItem(ACTIVE_CONFIGURATION_STORAGE_KEY);
  return stored?.trim() ? stored : null;
}

export async function saveConfigurationState(configurations: SavedConfiguration[], activeName: string): Promise<void> {
  await AsyncStorage.multiSet([
    [CONFIGURATIONS_STORAGE_KEY, JSON.stringify(configurations)],
    [ACTIVE_CONFIGURATION_STORAGE_KEY, activeName],
  ]);
}

export async function saveStoredActiveConfiguration(name: string): Promise<void> {
  await AsyncStorage.setItem(ACTIVE_CONFIGURATION_STORAGE_KEY, name);
}

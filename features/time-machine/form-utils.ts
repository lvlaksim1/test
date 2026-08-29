import type { CycleForm } from "@/lib/cycle-utils";

export type NumericField = Exclude<keyof CycleForm, "date" | "time">;

export type SavedConfiguration = {
  name: string;
  step: Pick<CycleForm, "stepDays" | "stepHours" | "stepMinutes">;
  repetition: Pick<CycleForm, "pauseSeconds" | "repeatsPerSeries" | "seriesPauseSeconds" | "totalSeries">;
};

export function normalizeNumericInput(value: string, allowNegative: boolean): string {
  const stripped = value.replace(/[^\d-]/g, "");
  const hasMinus = allowNegative && stripped.startsWith("-");
  const digits = stripped.replace(/-/g, "");
  return hasMinus ? `-${digits}` : digits;
}

export function makeConfiguration(name: string, form: CycleForm): SavedConfiguration {
  return {
    name,
    step: {
      stepDays: form.stepDays,
      stepHours: form.stepHours,
      stepMinutes: form.stepMinutes,
    },
    repetition: {
      pauseSeconds: form.pauseSeconds,
      repeatsPerSeries: form.repeatsPerSeries,
      seriesPauseSeconds: form.seriesPauseSeconds,
      totalSeries: form.totalSeries,
    },
  };
}

export function applyConfiguration(form: CycleForm, configuration: SavedConfiguration): CycleForm {
  return {
    ...form,
    ...configuration.step,
    ...configuration.repetition,
  };
}

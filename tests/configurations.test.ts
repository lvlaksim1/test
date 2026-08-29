import { describe, expect, it } from "vitest";

import { applyConfiguration, makeConfiguration, normalizeNumericInput } from "../features/time-machine/form-utils";
import type { CycleForm } from "../lib/cycle-utils";

const form: CycleForm = {
  date: "30.08.2026",
  time: "12:34",
  stepDays: "1",
  stepHours: "2",
  stepMinutes: "3",
  pauseSeconds: "4",
  repeatsPerSeries: "5",
  seriesPauseSeconds: "6",
  totalSeries: "7",
};

describe("configuration helpers", () => {
  it("сохраняет только шаг и параметры повторения", () => {
    const configuration = makeConfiguration("Тест", form);
    expect(configuration).toEqual({
      name: "Тест",
      step: { stepDays: "1", stepHours: "2", stepMinutes: "3" },
      repetition: { pauseSeconds: "4", repeatsPerSeries: "5", seriesPauseSeconds: "6", totalSeries: "7" },
    });
    expect(configuration).not.toHaveProperty("date");
    expect(configuration).not.toHaveProperty("time");
  });

  it("при загрузке не меняет стартовые дату и время", () => {
    const configuration = makeConfiguration("Тест", form);
    const current = { ...form, date: "01.09.2026", time: "08:00", stepHours: "9" };
    const loaded = applyConfiguration(current, configuration);
    expect(loaded.date).toBe("01.09.2026");
    expect(loaded.time).toBe("08:00");
    expect(loaded.stepHours).toBe("2");
  });
});

describe("normalizeNumericInput", () => {
  it("сохраняет минус только для полей, где он разрешён", () => {
    expect(normalizeNumericInput("-12x", true)).toBe("-12");
    expect(normalizeNumericInput("-12x", false)).toBe("12");
  });
});

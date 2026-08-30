import { describe, expect, it } from "vitest";

import { getDefaultForm, parseCycleForm, targetAt } from "../lib/cycle-utils";

describe("parseCycleForm", () => {
  it("задает согласованные значения по умолчанию", () => {
    const form = getDefaultForm(new Date(2026, 7, 21, 12, 0));
    expect(form).toMatchObject({ stepDays: "0", stepHours: "2", stepMinutes: "0", pauseSeconds: "2", repeatsPerSeries: "2", seriesPauseSeconds: "60", totalSeries: "1" });
  });

  it("создает корректную конфигурацию вложенного и главного циклов", () => {
    const result = parseCycleForm({ date: "21.08.2026", time: "09:30", stepDays: "1", stepHours: "2", stepMinutes: "15", pauseSeconds: "10", repeatsPerSeries: "3", seriesPauseSeconds: "60", totalSeries: "4" });
    expect(result.error).toBeUndefined();
    expect(result.config).toMatchObject({ stepDays: 1, stepHours: 2, stepMinutes: 15, pauseSeconds: 10, repeatsPerSeries: 3, seriesPauseSeconds: 60, totalSeries: 4, totalCycles: 12 });
  });

  it("считает пустые поля шага нулём и принимает отрицательное значение", () => {
    const result = parseCycleForm({ date: "21.08.2026", time: "09:30", stepDays: "", stepHours: "-2", stepMinutes: "", pauseSeconds: "1", repeatsPerSeries: "1", seriesPauseSeconds: "0", totalSeries: "1" });
    expect(result.error).toBeUndefined();
    expect(result.config).toMatchObject({ stepDays: 0, stepHours: -2, stepMinutes: 0 });
  });

  it("не считает взаимно компенсирующие разные единицы нулевым шагом", () => {
    const result = parseCycleForm({ date: "21.08.2026", time: "09:30", stepDays: "1", stepHours: "-1", stepMinutes: "0", pauseSeconds: "1", repeatsPerSeries: "1", seriesPauseSeconds: "0", totalSeries: "1" });
    expect(result.error).toBeUndefined();
    expect(result.config).toMatchObject({ stepDays: 1, stepHours: -1, stepMinutes: 0 });
  });

  it("отклоняет нулевой шаг", () => {
    const result = parseCycleForm({ date: "21.08.2026", time: "09:30", stepDays: "0", stepHours: "0", stepMinutes: "0", pauseSeconds: "10", repeatsPerSeries: "3", seriesPauseSeconds: "60", totalSeries: "4" });
    expect(result.error).toContain("Шаг");
  });

  it("ограничивает общее количество изменений", () => {
    const result = parseCycleForm({ date: "21.08.2026", time: "09:30", stepDays: "0", stepHours: "1", stepMinutes: "0", pauseSeconds: "1", repeatsPerSeries: "500", seriesPauseSeconds: "10", totalSeries: "500" });
    expect(result.error).toContain("99999");
  });
});

describe("targetAt", () => {
  it("рассчитывает последовательную дату для каждого изменения независимо от границ серий", () => {
    const parsed = parseCycleForm({ date: "21.08.2026", time: "09:30", stepDays: "1", stepHours: "2", stepMinutes: "15", pauseSeconds: "10", repeatsPerSeries: "3", seriesPauseSeconds: "60", totalSeries: "4" });
    if (!parsed.config) throw new Error("Expected config");
    const target = targetAt(parsed.config, 2);
    expect(target.getDate()).toBe(23);
    expect(target.getHours()).toBe(14);
    expect(target.getMinutes()).toBe(0);
  });
});

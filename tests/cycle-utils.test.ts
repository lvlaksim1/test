import { describe, expect, it } from "vitest";

import { getDefaultForm, parseCycleForm, targetAt } from "../lib/cycle-utils";

describe("parseCycleForm", () => {
  it("задает согласованные значения по умолчанию", () => {
    const form = getDefaultForm(new Date(2026, 7, 21, 12, 0));
    expect(form).toMatchObject({
      stepDays: "0",
      stepHours: "2",
      stepMinutes: "0",
      pauseSeconds: "2",
      totalCycles: "2",
    });
  });

  it("создает корректную конфигурацию из валидной формы", () => {
    const result = parseCycleForm({
      date: "21.08.2026",
      time: "09:30",
      stepDays: "1",
      stepHours: "2",
      stepMinutes: "15",
      pauseSeconds: "10",
      totalCycles: "3",
    });

    expect(result.error).toBeUndefined();
    expect(result.config).toMatchObject({
      stepDays: 1,
      stepHours: 2,
      stepMinutes: 15,
      pauseSeconds: 10,
      totalCycles: 3,
    });
  });

  it("считает пустые поля шага нулём и принимает отрицательное значение", () => {
    const result = parseCycleForm({
      date: "21.08.2026",
      time: "09:30",
      stepDays: "",
      stepHours: "-2",
      stepMinutes: "",
      pauseSeconds: "1",
      totalCycles: "1",
    });

    expect(result.error).toBeUndefined();
    expect(result.config).toMatchObject({ stepDays: 0, stepHours: -2, stepMinutes: 0 });
  });

  it("отклоняет нулевой шаг", () => {
    const result = parseCycleForm({
      date: "21.08.2026",
      time: "09:30",
      stepDays: "0",
      stepHours: "0",
      stepMinutes: "0",
      pauseSeconds: "10",
      totalCycles: "3",
    });

    expect(result.error).toContain("Шаг");
  });
});

describe("targetAt", () => {
  it("рассчитывает последовательную дату для каждого цикла", () => {
    const parsed = parseCycleForm({
      date: "21.08.2026",
      time: "09:30",
      stepDays: "1",
      stepHours: "2",
      stepMinutes: "15",
      pauseSeconds: "10",
      totalCycles: "3",
    });
    if (!parsed.config) throw new Error("Expected config");

    const target = targetAt(parsed.config, 2);
    expect(target.getDate()).toBe(23);
    expect(target.getHours()).toBe(14);
    expect(target.getMinutes()).toBe(0);
  });
});

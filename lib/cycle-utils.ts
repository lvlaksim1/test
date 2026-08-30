export type CycleForm = {
  date: string;
  time: string;
  stepDays: string;
  stepHours: string;
  stepMinutes: string;
  pauseSeconds: string;
  repeatsPerSeries: string;
  seriesPauseSeconds: string;
  totalSeries: string;
};

export type CycleConfig = {
  startAtMillis: number;
  stepDays: number;
  stepHours: number;
  stepMinutes: number;
  pauseSeconds: number;
  repeatsPerSeries: number;
  seriesPauseSeconds: number;
  totalSeries: number;
  totalCycles: number;
};

const INTEGER_PATTERN = /^-?\d+$/;

export function getDefaultForm(now = new Date()): CycleForm {
  const date = [now.getDate(), now.getMonth() + 1, now.getFullYear()]
    .map((part, index) => index < 2 ? String(part).padStart(2, "0") : String(part))
    .join(".");
  const time = `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`;
  return {
    date,
    time,
    stepDays: "0",
    stepHours: "2",
    stepMinutes: "0",
    pauseSeconds: "2",
    repeatsPerSeries: "2",
    seriesPauseSeconds: "60",
    totalSeries: "1",
  };
}

export function parseCycleForm(form: CycleForm): { config?: CycleConfig; error?: string } {
  const dateMatch = form.date.trim().match(/^(\d{2})\.(\d{2})\.(\d{4})$/);
  const timeMatch = form.time.trim().match(/^(\d{2}):(\d{2})$/);
  if (!dateMatch || !timeMatch) return { error: "Введите дату в формате ДД.ММ.ГГГГ и время в формате ЧЧ:ММ." };

  const [day, month, year] = dateMatch.slice(1).map(Number);
  const [hours, minutes] = timeMatch.slice(1).map(Number);
  const start = new Date(year, month - 1, day, hours, minutes, 0, 0);
  if (start.getFullYear() !== year || start.getMonth() !== month - 1 || start.getDate() !== day || hours > 23 || minutes > 59) {
    return { error: "Стартовые дата или время некорректны." };
  }

  const numericFields = [
    ["дни", form.stepDays, -999, 999],
    ["часы", form.stepHours, -999, 999],
    ["минуты", form.stepMinutes, -999, 999],
    ["пауза между повторами", form.pauseSeconds, 1, 86400],
    ["повторы во вложенном цикле", form.repeatsPerSeries, 1, 99999],
    ["пауза между главными циклами", form.seriesPauseSeconds, 0, 86400],
    ["главные циклы", form.totalSeries, 1, 99999],
  ] as const;
  const values: number[] = [];
  for (const [name, raw, min, max] of numericFields) {
    const normalized = raw.trim() === "" ? "0" : raw.trim();
    if (!INTEGER_PATTERN.test(normalized)) return { error: `Поле «${name}» должно содержать целое число.` };
    const value = Number(normalized);
    if (value < min || value > max) return { error: `Поле «${name}» должно быть в диапазоне ${min}–${max}.` };
    values.push(value);
  }
  if (values[0] === 0 && values[1] === 0 && values[2] === 0) return { error: "Шаг изменения не может состоять только из нулей." };

  const repeatsPerSeries = values[4];
  const totalSeries = values[6];
  const totalCycles = repeatsPerSeries * totalSeries;
  if (totalCycles > 99999) return { error: "Общее количество изменений не должно превышать 99999." };

  return {
    config: {
      startAtMillis: start.getTime(),
      stepDays: values[0],
      stepHours: values[1],
      stepMinutes: values[2],
      pauseSeconds: values[3],
      repeatsPerSeries,
      seriesPauseSeconds: values[5],
      totalSeries,
      totalCycles,
    },
  };
}

export function toFormStart(value: number | Date): Pick<CycleForm, "date" | "time"> {
  const date = value instanceof Date ? value : new Date(value);
  return {
    date: [date.getDate(), date.getMonth() + 1, date.getFullYear()].map((part, index) => index < 2 ? String(part).padStart(2, "0") : String(part)).join("."),
    time: `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`,
  };
}

export function formatDateTime(value: number | Date | null | undefined): string {
  if (!value) return "—";
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return "—";
  return new Intl.DateTimeFormat("ru-RU", { dateStyle: "medium", timeStyle: "short" }).format(date);
}

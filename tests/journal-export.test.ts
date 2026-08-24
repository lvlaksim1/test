import { describe, expect, it } from "vitest";

import { formatJournalForCopy } from "../lib/journal-export";

describe("formatJournalForCopy", () => {
  it("включает все записи и выводит новые события первыми", () => {
    const exported = formatJournalForCopy([
      { at: 1_000, message: "Первая запись" },
      { at: 2_000, message: "Диагностика выбора даты: полный текст" },
    ]);

    expect(exported).toContain("Машина времени — журнал действий");
    expect(exported).toContain("Первая запись");
    expect(exported).toContain("Диагностика выбора даты: полный текст");
    expect(exported.indexOf("Диагностика выбора даты")).toBeLessThan(exported.indexOf("Первая запись"));
  });
});

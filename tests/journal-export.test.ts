import { describe, expect, it } from "vitest";

import { formatJournalForCopy } from "../lib/journal-export";

describe("formatJournalForCopy", () => {
  it("сохраняет фактический порядок событий даже если системное время шло назад", () => {
    const exported = formatJournalForCopy([
      { at: 3_000, message: "Первая фактическая запись" },
      { at: 1_000, message: "Вторая фактическая запись после перевода часов назад" },
    ]);

    expect(exported).toContain("Машина времени — журнал действий");
    expect(exported.indexOf("Вторая фактическая запись")).toBeLessThan(exported.indexOf("Первая фактическая запись"));
  });
});

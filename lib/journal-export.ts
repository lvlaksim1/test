import type { CycleEvent } from "@/lib/time-control";

export function formatJournalForCopy(events: CycleEvent[]): string {
  const header = [
    "Машина времени — журнал действий",
    `Экспортировано: ${new Date().toLocaleString("ru-RU")}`,
    "",
  ];

  const records = events
    .slice()
    .sort((left, right) => right.at - left.at)
    .map((event) => `${new Date(event.at).toLocaleString("ru-RU")}\n${event.message}`);

  return [...header, ...(records.length ? records : ["Записей нет."])].join("\n\n");
}

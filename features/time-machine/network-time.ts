export async function getRealCurrentTimeMillis(): Promise<number> {
  const response = await fetch("https://www.google.com/generate_204", {
    method: "HEAD",
    cache: "no-store",
  });
  const dateHeader = response.headers.get("date");
  if (!dateHeader) throw new Error("Сервер времени не вернул дату.");
  const millis = Date.parse(dateHeader);
  if (!Number.isFinite(millis)) throw new Error("Не удалось распознать время сервера.");
  return millis;
}

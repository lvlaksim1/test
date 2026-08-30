type TimeSource = { url: string; method: "HEAD" | "GET" };

const TIME_SOURCES: TimeSource[] = [
  { url: "https://www.google.com/generate_204", method: "HEAD" },
  { url: "https://www.cloudflare.com/cdn-cgi/trace", method: "GET" },
];

async function readServerTime(source: TimeSource, timeoutMillis = 4000): Promise<number> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMillis);
  try {
    const response = await fetch(source.url, {
      method: source.method,
      cache: "no-store",
      signal: controller.signal,
      headers: { "Cache-Control": "no-cache" },
    });
    const dateHeader = response.headers.get("date");
    if (!dateHeader) throw new Error("Сервер не вернул заголовок Date.");
    const millis = Date.parse(dateHeader);
    if (!Number.isFinite(millis)) throw new Error("Сервер вернул некорректную дату.");
    return millis;
  } finally {
    clearTimeout(timeout);
  }
}

export async function getRealCurrentTimeMillis(): Promise<number> {
  const errors: string[] = [];
  for (const source of TIME_SOURCES) {
    try {
      return await readServerTime(source);
    } catch (error) {
      errors.push(error instanceof Error ? error.message : "неизвестная ошибка");
    }
  }
  throw new Error(`Не удалось получить сетевое время ни от одного источника. ${errors.join(" ")}`);
}

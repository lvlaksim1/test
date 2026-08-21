import { describe, expect, it } from "vitest";

const token = process.env.EXPO_TOKEN;
const tokenTest = token ? it : it.skip;

describe("Expo access token", () => {
  tokenTest("authorizes a lightweight current-user request", async () => {

    const response = await fetch("https://exp.host/--/api/v2/auth/userInfo", {
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: "application/json",
      },
    });

    expect(response.status, `Expo API вернул ${response.status}`).toBe(200);
    const body = (await response.json()) as {
      data?: { id?: string; username?: string };
      id?: string;
      username?: string;
    };
    expect(body.data?.id ?? body.data?.username ?? body.id ?? body.username).toBeTruthy();
  }, 20_000);
});

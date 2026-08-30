#!/usr/bin/env node

const command = process.argv[2] ?? "status";
const baseUrl = (process.env.SLEEP_GUARD_URL ?? "").replace(/\/$/, "");
const token = process.env.SLEEP_GUARD_CODEX_TOKEN ?? "";
const routes = {
  start: ["POST", "/api/control/activate"],
  stop: ["POST", "/api/control/deactivate"],
  status: ["GET", "/api/control/status"],
};
if (!baseUrl || !token || !routes[command]) {
  console.error("Usage: SLEEP_GUARD_URL=https://... SLEEP_GUARD_CODEX_TOKEN=... sleep-guard-control.mjs start|stop|status");
  process.exit(2);
}
const [method, path] = routes[command];
const response = await fetch(`${baseUrl}${path}`, {
  method,
  headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
  body: method === "POST" ? "{}" : undefined,
});
console.log(JSON.stringify(await response.json(), null, 2));
process.exit(response.ok ? 0 : 1);


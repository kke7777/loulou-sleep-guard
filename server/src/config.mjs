import { resolve } from "node:path";

function integer(name, fallback, minimum, maximum) {
  const raw = process.env[name];
  const parsed = raw === undefined ? fallback : Number.parseInt(raw, 10);
  if (!Number.isInteger(parsed) || parsed < minimum || parsed > maximum) {
    throw new Error(`${name} must be an integer from ${minimum} to ${maximum}`);
  }
  return parsed;
}

function requiredSecret(name) {
  const value = (process.env[name] ?? "").trim();
  if (value.length < 24 || value.startsWith("replace-with")) {
    throw new Error(`${name} must be configured with at least 24 private characters`);
  }
  return value;
}

export function loadConfig(overrides = {}) {
  const config = {
    port: integer("PORT", 8787, 1, 65535),
    publicBaseUrl: (process.env.PUBLIC_BASE_URL ?? "").replace(/\/$/, ""),
    dataDir: resolve(process.cwd(), process.env.DATA_DIR ?? "./data"),
    androidDeviceToken: requiredSecret("ANDROID_DEVICE_TOKEN"),
    codexControlToken: requiredSecret("CODEX_CONTROL_TOKEN"),
    ownerApprovalCode: requiredSecret("OWNER_APPROVAL_CODE"),
    wakeHour: integer("WAKE_HOUR", 6, 0, 23),
    wakeMinute: integer("WAKE_MINUTE", 30, 0, 59),
    autoStartHour: integer("AUTO_START_HOUR", 1, 0, 23),
    utcOffsetMinutes: integer("UTC_OFFSET_MINUTES", 480, -720, 840),
    accessTokenTtlDays: integer("ACCESS_TOKEN_TTL_DAYS", 90, 1, 365),
  };
  Object.assign(config, overrides);
  if (!/^https:\/\//.test(config.publicBaseUrl) && !/^http:\/\/(?:127\.0\.0\.1|localhost)(?::\d+)?$/.test(config.publicBaseUrl)) {
    throw new Error("PUBLIC_BASE_URL must be HTTPS (or loopback HTTP for local tests)");
  }
  return config;
}

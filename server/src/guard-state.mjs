const EVENT_NAMES = new Set([
  "sleep_guard_started",
  "sleep_guard_ended",
  "blocked_app_opened",
  "temporary_unlock_requested",
]);

export function emptyState(now = new Date().toISOString()) {
  return {
    active: false,
    attempts: 0,
    unlock_request_count: 0,
    unlocks_revoked: false,
    session_id: null,
    started_at: null,
    ends_at: null,
    auto_start_suppressed_until: null,
    updated_at: now,
  };
}

function shifted(now, offsetMinutes) {
  return new Date(now.getTime() + offsetMinutes * 60_000);
}

export function localHour(now, config) {
  return shifted(now, config.utcOffsetMinutes).getUTCHours();
}

export function nextWakeTime(now, config) {
  const local = shifted(now, config.utcOffsetMinutes);
  let wake = new Date(Date.UTC(
    local.getUTCFullYear(),
    local.getUTCMonth(),
    local.getUTCDate(),
    config.wakeHour,
    config.wakeMinute ?? 0,
    0,
    0,
  ) - config.utcOffsetMinutes * 60_000);
  if (wake <= now) wake = new Date(wake.getTime() + 86_400_000);
  return wake;
}

export function shouldAutoStart(now, config) {
  const local = shifted(now, config.utcOffsetMinutes);
  const currentMinute = local.getUTCHours() * 60 + local.getUTCMinutes();
  const startMinute = config.autoStartHour * 60;
  const wakeMinute = config.wakeHour * 60 + (config.wakeMinute ?? 0);
  if (startMinute < wakeMinute) {
    return currentMinute >= startMinute && currentMinute < wakeMinute;
  }
  return currentMinute >= startMinute || currentMinute < wakeMinute;
}

function normalizeEnd(value, now, config) {
  const candidate = typeof value === "string" ? new Date(value) : null;
  const maximum = new Date(now.getTime() + 86_400_000);
  if (candidate && Number.isFinite(candidate.getTime()) && candidate > now && candidate <= maximum) {
    return candidate.toISOString();
  }
  return nextWakeTime(now, config).toISOString();
}

function suppressionEnd(now, config) {
  if (!shouldAutoStart(now, config)) return null;
  return nextWakeTime(now, config).toISOString();
}

export function applyGuardEvent(previous, payload, receivedAt, config) {
  const now = new Date(receivedAt);
  let state = previous ? structuredClone(previous) : emptyState(receivedAt);
  if (state.active && state.ends_at && Date.parse(state.ends_at) <= now.getTime()) {
    state.active = false;
    state.updated_at = receivedAt;
  }

  if (!EVENT_NAMES.has(payload.event)) {
    return { state, ignored: true, auto_started: false, stage: "inactive", error: "invalid_event" };
  }

  if (payload.event === "sleep_guard_started") {
    if (state.active) {
      state.updated_at = receivedAt;
      return { state, ignored: false, auto_started: false, stage: "armed" };
    }
    return {
      state: {
        active: true,
        attempts: 0,
        unlock_request_count: 0,
        unlocks_revoked: false,
        session_id: crypto.randomUUID(),
        started_at: receivedAt,
        ends_at: normalizeEnd(payload.ends_at, now, config),
        auto_start_suppressed_until: null,
        updated_at: receivedAt,
      },
      ignored: false,
      auto_started: false,
      stage: "armed",
    };
  }

  if (payload.event === "sleep_guard_ended") {
    return {
      state: {
        ...state,
        active: false,
        auto_start_suppressed_until: suppressionEnd(now, config),
        updated_at: receivedAt,
      },
      ignored: !state.active,
      auto_started: false,
      stage: "ended",
    };
  }

  if (payload.event === "temporary_unlock_requested") {
    const revoked = Boolean(state.unlocks_revoked);
    if (!state.active || revoked) {
      state.unlocks_revoked = revoked;
      state.updated_at = receivedAt;
      return {
        state,
        ignored: true,
        auto_started: false,
        stage: revoked ? "refused_sleep" : "inactive",
      };
    }
    const unlockRequestCount = Math.min(Number(state.unlock_request_count ?? 0) + 1, 999);
    return {
      state: {
        ...state,
        unlock_request_count: unlockRequestCount,
        unlocks_revoked: unlockRequestCount >= 3,
        updated_at: receivedAt,
      },
      ignored: false,
      auto_started: false,
      stage: state.attempts === 1 ? "first_warning" : state.attempts === 2 ? "locked" : "refused_sleep",
    };
  }

  if (!state.active) {
    const suppressed = state.auto_start_suppressed_until
      && Date.parse(state.auto_start_suppressed_until) > now.getTime();
    if (!suppressed && shouldAutoStart(now, config)) {
      return {
        state: {
          active: true,
          attempts: 1,
          unlock_request_count: 0,
          unlocks_revoked: false,
          session_id: crypto.randomUUID(),
          started_at: receivedAt,
          ends_at: normalizeEnd(payload.ends_at, now, config),
          auto_start_suppressed_until: null,
          updated_at: receivedAt,
        },
        ignored: false,
        auto_started: true,
        stage: "first_warning",
      };
    }
    state.updated_at = receivedAt;
    return { state, ignored: true, auto_started: false, stage: "inactive" };
  }

  const attempts = Math.min(Number(state.attempts ?? 0) + 1, 999);
  return {
    state: { ...state, attempts, updated_at: receivedAt },
    ignored: false,
    auto_started: false,
    stage: attempts === 1 ? "first_warning" : attempts === 2 ? "locked" : "refused_sleep",
  };
}

function cleanText(value, maxLength) {
  if (typeof value !== "string") return null;
  const result = value.trim().replace(/[\r\n\t]+/g, " ").slice(0, maxLength);
  return result || null;
}

export class GuardService {
  constructor(store, config) {
    this.store = store;
    this.config = config;
  }

  async status() {
    return this.store.withLock(async () => {
      const now = new Date();
      const current = await this.store.readState(emptyState(now.toISOString()));
      if (current.active && current.ends_at && Date.parse(current.ends_at) <= now.getTime()) {
        const expired = { ...current, active: false, updated_at: now.toISOString() };
        await this.store.writeState(expired);
        return expired;
      }
      return current;
    });
  }

  async event(payload, source = "unknown") {
    return this.store.withLock(async () => {
      const receivedAt = new Date().toISOString();
      const previous = await this.store.readState(emptyState(receivedAt));
      const transition = applyGuardEvent(previous, payload, receivedAt, this.config);
      if (transition.error) return { ok: false, error: transition.error };
      await this.store.writeState(transition.state);
      const event = {
        id: crypto.randomUUID(),
        request_id: cleanText(payload.request_id, 100),
        event: payload.event,
        source: cleanText(source || payload.source, 64) ?? "unknown",
        app_name: cleanText(payload.app_name, 80),
        attempts: transition.state.attempts,
        unlock_request_count: Number(transition.state.unlock_request_count ?? 0),
        unlocks_revoked: Boolean(transition.state.unlocks_revoked),
        active: transition.state.active,
        stage: transition.stage,
        ignored: transition.ignored,
        auto_started: transition.auto_started,
        session_id: transition.state.session_id,
        received_at: receivedAt,
      };
      await this.store.appendEvent(event);
      return { ok: true, ...transition, event_id: event.id, received_at: receivedAt };
    });
  }
}

export function publicState(state) {
  return {
    active: Boolean(state?.active) && (!state?.ends_at || Date.parse(state.ends_at) > Date.now()),
    attempts: Number(state?.attempts ?? 0),
    unlock_request_count: Number(state?.unlock_request_count ?? 0),
    unlocks_revoked: Boolean(state?.unlocks_revoked),
    session_id: state?.session_id ?? null,
    started_at: state?.started_at ?? null,
    ends_at: state?.ends_at ?? null,
    updated_at: state?.updated_at ?? null,
  };
}

const EVENT_NAMES = new Set([
  "sleep_guard_started",
  "sleep_guard_ended",
  "blocked_app_opened",
  "temporary_unlock_requested",
  "emergency_guard_ended",
]);

export function emptyState(now = new Date().toISOString()) {
  return {
    active: false,
    temporary_unlock_until: null,
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
  const startMinute = config.autoStartHour * 60 + (config.autoStartMinute ?? 0);
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
  return nextWakeTime(now, config).toISOString();
}

function schedule(config) {
  return { start_hour: config.autoStartHour, start_minute: config.autoStartMinute ?? 0,
    wake_hour: config.wakeHour, wake_minute: config.wakeMinute ?? 0, utc_offset_minutes: config.utcOffsetMinutes };
}

function newSession(now, config, endsAt, sessionId) {
  return { active: true, attempts: 0, unlock_request_count: 0, unlocks_revoked: false,
    temporary_unlock_until: null, session_id: sessionId ?? crypto.randomUUID(),
    started_at: now.toISOString(), ends_at: normalizeEnd(endsAt, now, config),
    auto_start_suppressed_until: null, updated_at: now.toISOString() };
}

// Pure clock transition: also run without any phone request, and after server restarts.
export function advanceClock(previous, now, config) {
  const state = structuredClone(previous ?? emptyState(now.toISOString()));
  state.schedule = schedule(config);
  const night = nextWakeTime(now, config).toISOString();
  if (state.active && shouldAutoStart(now, config)) state.last_auto_night = night;
  if (state.active && Date.parse(state.ends_at) <= now.getTime()) {
    state.active = false;
    state.temporary_unlock_until = null;
    state.updated_at = now.toISOString();
  }
  if (!state.active && shouldAutoStart(now, config)
      && !(Date.parse(state.auto_start_suppressed_until) > now.getTime())
      && state.last_auto_night !== night) {
    Object.assign(state, newSession(now, config, night, `night-${night}`));
    state.last_auto_night = night;
  }
  return state;
}

export function applyGuardEvent(previous, payload, receivedAt, config) {
  const now = new Date(receivedAt);
  let state = advanceClock(previous, now, config);
  const autoStarted = state.session_id !== previous?.session_id && state.session_id?.startsWith("night-");
  const reply = (ignored = false, stage = "armed", error) => ({ state, ignored,
    auto_started: Boolean(autoStarted), stage, ...(error ? { error } : {}) });
  if (!EVENT_NAMES.has(payload.event)) return reply(true, "inactive", "invalid_event");

  if (payload.event === "sleep_guard_started") {
    if (!state.active) Object.assign(state, newSession(now, config, payload.ends_at));
    return reply();
  }
  if (payload.event === "emergency_guard_ended") {
    const reason = typeof payload.reason === "string" ? payload.reason.trim() : "";
    if (!reason || [...reason].length > 200 || !payload.session_id || !payload.request_id) {
      return reply(true, "inactive", "reason_and_session_required");
    }
    // Offline history may arrive after another session has begun. Record it, never end the newer one.
    if (payload.session_id !== state.session_id) return reply(true, "stale_session");
    state.active = false;
    state.temporary_unlock_until = null;
    const occurred = new Date(payload.occurred_at ?? receivedAt);
    const eventTime = Number.isFinite(occurred.getTime()) && occurred <= now ? occurred : now;
    state.auto_start_suppressed_until = suppressionEnd(eventTime, config);
    state.updated_at = receivedAt;
    return reply(false, "ended");
  }
  if (payload.event === "sleep_guard_ended") {
    const wasActive = state.active;
    state.active = false;
    state.temporary_unlock_until = null;
    state.auto_start_suppressed_until = suppressionEnd(now, config);
    state.updated_at = receivedAt;
    return reply(!wasActive, "ended");
  }
  if (payload.session_id && payload.session_id !== state.session_id) return reply(true, "stale_session");
  if (!state.active) return reply(true, "inactive");
  if (Date.parse(state.temporary_unlock_until) > now.getTime()) return reply(true, "temporary_unlock");
  if (payload.event === "temporary_unlock_requested") {
    if (state.unlock_request_count >= 3) return reply(true, "refused_sleep");
    state.unlock_request_count = Number(state.unlock_request_count ?? 0) + 1;
    state.unlocks_revoked = state.unlock_request_count >= 3;
    state.temporary_unlock_until = new Date(Math.min(now.getTime() + 600_000, Date.parse(state.ends_at))).toISOString();
    state.updated_at = receivedAt;
    return reply(false, "temporary_unlock");
  }
  state.attempts = Math.min(Number(state.attempts ?? 0) + 1, 999);
  state.updated_at = receivedAt;
  return reply(false, state.attempts === 1 ? "first_warning" : state.attempts === 2 ? "locked" : "refused_sleep");
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
    this.listeners = new Set();
  }

  subscribe(listener) {
    if (typeof listener !== "function") return () => {};
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  publish(state) {
    const snapshot = publicState(state);
    for (const listener of [...this.listeners]) {
      try {
        listener(snapshot);
      } catch {
        this.listeners.delete(listener);
      }
    }
  }

  async flushLog(state) {
    if (!state.log_outbox) return;
    await this.store.appendEventOnce(state.log_outbox);
    delete state.log_outbox;
    await this.store.writeState(state);
  }

  async status() {
    let changed = false;
    const state = await this.store.withLock(async () => {
      const now = new Date();
      const previous = await this.store.readState(emptyState(now.toISOString()));
      await this.flushLog(previous);
      const current = advanceClock(previous, now, this.config);
      changed = JSON.stringify(current) !== JSON.stringify(previous);
      if (changed) {
        current.revision = Number(previous.revision ?? 0) + 1;
        await this.store.writeState(current);
      }
      return current;
    });
    if (changed) this.publish(state);
    return state;
  }

  async event(payload, source = "unknown") {
    const result = await this.store.withLock(async () => {
      const receivedAt = new Date().toISOString();
      const previous = await this.store.readState(emptyState(receivedAt));
      await this.flushLog(previous);
      const requestId = cleanText(payload.request_id, 100);
      if (requestId && (previous.processed_requests ?? []).includes(requestId)) {
        return { ok: true, state: previous, ignored: true, stage: "duplicate", event_id: requestId };
      }
      const transition = applyGuardEvent(previous, payload, receivedAt, this.config);
      if (transition.error) return { ok: false, error: transition.error };
      const event = {
        id: requestId ?? crypto.randomUUID(), request_id: requestId,
        event: payload.event, source: cleanText(source || payload.source, 64) ?? "unknown",
        app_name: cleanText(payload.app_name, 80),
        attempts: transition.state.attempts,
        unlock_request_count: Number(transition.state.unlock_request_count ?? 0),
        active: transition.state.active, stage: transition.stage, ignored: transition.ignored,
        auto_started: transition.auto_started, session_id: payload.session_id ?? transition.state.session_id,
        received_at: receivedAt,
        ...(payload.event === "emergency_guard_ended" ? {
          reason: payload.reason.trim(), occurred_at: cleanText(payload.occurred_at, 40),
          original_ends_at: cleanText(payload.original_ends_at, 40),
          original_started_at: cleanText(payload.original_started_at, 40),
        } : {}),
      };
      transition.state.revision = Number(previous.revision ?? 0) + 1;
      transition.state.processed_requests = [...(previous.processed_requests ?? []), ...(requestId ? [requestId] : [])];
      // Atomic durable state + outbox: a crash cannot lose a reason or duplicate its journal entry.
      transition.state.log_outbox = event;
      await this.store.writeState(transition.state);
      await this.flushLog(transition.state);
      return { ok: true, ...transition, event_id: event.id, received_at: receivedAt };
    });
    if (result.ok) this.publish(result.state);
    return result;
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
    protocol_version: 2,
    revision: Number(state?.revision ?? 0),
    temporary_unlock_until: state?.temporary_unlock_until ?? null,
    auto_start_suppressed_until: state?.auto_start_suppressed_until ?? null,
    last_auto_night: state?.last_auto_night ?? null,
    schedule: state?.schedule ?? null,
  };
}

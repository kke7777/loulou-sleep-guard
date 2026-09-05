import assert from "node:assert/strict";
import test from "node:test";
import { applyGuardEvent, emptyState, publicState, shouldAutoStart, advanceClock } from "../src/guard-state.mjs";

const config = { wakeHour: 6, wakeMinute: 30, autoStartHour: 1, utcOffsetMinutes: 480 };

test("three full ten-minute passes, no counting during a pass, no fourth pass", () => {
  let now = new Date("2026-08-28T14:00:00Z");
  let state = applyGuardEvent(null, { event: "sleep_guard_started" }, now.toISOString(), config).state;
  for (let i = 1; i <= 3; i++) {
    let result = applyGuardEvent(state, { event: "blocked_app_opened" }, now.toISOString(), config);
    assert.equal(result.state.attempts, i);
    result = applyGuardEvent(result.state, { event: "temporary_unlock_requested" }, now.toISOString(), config);
    state = result.state;
    assert.equal(state.unlock_request_count, i);
    assert.equal(Date.parse(state.temporary_unlock_until) - now.getTime(), 600_000);
    assert.equal(state.unlocks_revoked, i === 3);
    result = applyGuardEvent(state, { event: "temporary_unlock_requested" }, now.toISOString(), config);
    assert.equal(result.state.unlock_request_count, i);
    result = applyGuardEvent(state, { event: "blocked_app_opened" }, new Date(now.getTime() + 599_999).toISOString(), config);
    assert.equal(result.ignored, true);
    assert.equal(result.state.attempts, i);
    now = new Date(now.getTime() + 600_000);
  }
  const fourth = applyGuardEvent(state, { event: "temporary_unlock_requested" }, now.toISOString(), config);
  assert.equal(fourth.ignored, true);
  assert.equal(fourth.state.unlock_request_count, 3);
});

test("pass ends with the guard and expiry does not reactivate the same night", () => {
  const options = { ...config, autoStartHour: 0, autoStartMinute: 20 };
  const now = "2026-08-28T16:30:00.000Z";
  let state = applyGuardEvent(null, { event: "sleep_guard_started", ends_at: "2026-08-28T16:35:00.000Z" },
    "2026-08-28T16:19:00.000Z", options).state;
  state = applyGuardEvent(state, { event: "temporary_unlock_requested" }, now, options).state;
  assert.equal(state.temporary_unlock_until, state.ends_at);
  state = advanceClock(state, new Date(state.ends_at), options);
  assert.equal(state.active, false);
  assert.equal(advanceClock(state, new Date("2026-08-28T17:00:00Z"), options).active, false);
});

test("00:20 proactive start, wake boundary, daily recurrence and restart recovery", () => {
  const options = { ...config, autoStartHour: 0, autoStartMinute: 20 };
  let state = advanceClock(null, new Date("2026-08-28T16:19:59Z"), options);
  assert.equal(state.active, false);
  state = advanceClock(state, new Date("2026-08-28T16:20:00Z"), options);
  assert.equal(state.active, true);
  assert.equal(state.attempts, 0);
  assert.equal(state.session_id, "night-2026-08-28T22:30:00.000Z");
  const session = state.session_id;
  state = advanceClock(state, new Date("2026-08-28T16:21:00Z"), options);
  assert.equal(state.session_id, session);
  state = advanceClock(state, new Date("2026-08-28T22:30:00Z"), options);
  assert.equal(state.active, false);
  state = advanceClock(state, new Date("2026-08-29T16:20:00Z"), options);
  assert.equal(state.active, true);
  assert.notEqual(state.session_id, session);
});

test("reason required, emergency ends night, stale offline reason cannot end a newer session", () => {
  const time = "2026-08-28T17:00:00.000Z";
  const state = advanceClock(null, new Date(time), config);
  let payload = { event: "emergency_guard_ended", session_id: state.session_id, request_id: "emergency-1", reason: "  " };
  assert.ok(applyGuardEvent(state, payload, time, config).error);
  payload.reason = "单位临时工作";
  let result = applyGuardEvent(state, payload, time, config);
  assert.equal(result.state.active, false);
  assert.equal(advanceClock(result.state, new Date("2026-08-28T18:00:00Z"), config).active, false);
  const restarted = applyGuardEvent(result.state, { event: "sleep_guard_started" }, time, config).state;
  result = applyGuardEvent(restarted, payload, time, config);
  assert.equal(result.state.active, true);
  assert.equal(result.state.session_id, restarted.session_id);
  assert.equal(result.stage, "stale_session");
});

test("repeat start preserves attempts and a new session resets unlock state", () => {
  let transition = applyGuardEvent(null, { event: "sleep_guard_started" }, "2026-08-28T14:00:00.000Z", config);
  transition = applyGuardEvent(transition.state, { event: "blocked_app_opened" }, "2026-08-28T14:01:00.000Z", config);
  transition = applyGuardEvent(transition.state, { event: "temporary_unlock_requested" }, "2026-08-28T14:01:30.000Z", config);
  const session = transition.state.session_id;

  transition = applyGuardEvent(transition.state, { event: "sleep_guard_started" }, "2026-08-28T14:02:00.000Z", config);
  assert.equal(transition.state.session_id, session);
  assert.equal(transition.state.attempts, 1);
  assert.equal(transition.state.unlock_request_count, 1);

  transition = applyGuardEvent(transition.state, { event: "sleep_guard_ended" }, "2026-08-28T14:03:00.000Z", config);
  transition = applyGuardEvent(transition.state, { event: "sleep_guard_started" }, "2026-08-28T14:04:00.000Z", config);
  assert.notEqual(transition.state.session_id, session);
  assert.equal(transition.state.attempts, 0);
  assert.equal(transition.state.unlock_request_count, 0);
  assert.equal(transition.state.unlocks_revoked, false);
});

test("late-night app open auto-starts and a manual wake suppresses it", () => {
  assert.equal(shouldAutoStart(new Date("2026-08-28T17:00:00.000Z"), config), true);
  assert.equal(shouldAutoStart(new Date("2026-08-28T22:29:00.000Z"), config), true);
  assert.equal(shouldAutoStart(new Date("2026-08-28T22:30:00.000Z"), config), false);
  let transition = applyGuardEvent(emptyState("2026-08-28T17:00:00.000Z"), { event: "blocked_app_opened" }, "2026-08-28T17:00:00.000Z", config);
  assert.equal(transition.auto_started, true);
  assert.equal(transition.state.attempts, 1);
  transition = applyGuardEvent(transition.state, { event: "sleep_guard_ended" }, "2026-08-28T18:00:00.000Z", config);
  transition = applyGuardEvent(transition.state, { event: "blocked_app_opened" }, "2026-08-28T19:00:00.000Z", config);
  assert.equal(transition.ignored, true);
  assert.equal(transition.auto_started, false);
});

test("publicState treats expired sessions as inactive", () => {
  const state = emptyState();
  state.active = true;
  state.ends_at = "2000-01-01T00:00:00.000Z";
  assert.equal(publicState(state).active, false);
});

test("default end is the next China-time 06:30", () => {
  let transition = applyGuardEvent(
    null,
    { event: "sleep_guard_started" },
    "2026-08-28T14:30:00.000Z",
    config,
  );
  assert.equal(transition.state.ends_at, "2026-08-28T22:30:00.000Z");

  transition = applyGuardEvent(
    null,
    { event: "sleep_guard_started" },
    "2026-08-28T18:00:00.000Z",
    config,
  );
  assert.equal(transition.state.ends_at, "2026-08-28T22:30:00.000Z");
});

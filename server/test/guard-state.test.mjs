import assert from "node:assert/strict";
import test from "node:test";
import { applyGuardEvent, emptyState, publicState, shouldAutoStart } from "../src/guard-state.mjs";

const config = { wakeHour: 6, wakeMinute: 30, autoStartHour: 1, utcOffsetMinutes: 480 };

test("start, count, and stop preserve one session", () => {
  let transition = applyGuardEvent(null, { event: "sleep_guard_started", ends_at: "2026-08-29T03:00:00.000Z" }, "2026-08-28T14:00:00.000Z", config);
  assert.equal(transition.state.active, true);
  assert.equal(transition.state.attempts, 0);
  assert.equal(transition.state.unlock_request_count, 0);
  assert.equal(transition.state.unlocks_revoked, false);
  const session = transition.state.session_id;

  transition = applyGuardEvent(transition.state, { event: "sleep_guard_started" }, "2026-08-28T14:01:00.000Z", config);
  assert.equal(transition.state.session_id, session);
  transition = applyGuardEvent(transition.state, { event: "blocked_app_opened" }, "2026-08-28T14:02:00.000Z", config);
  assert.equal(transition.state.attempts, 1);
  assert.equal(transition.stage, "first_warning");
  transition = applyGuardEvent(transition.state, { event: "temporary_unlock_requested" }, "2026-08-28T14:02:30.000Z", config);
  assert.equal(transition.state.unlock_request_count, 1);
  assert.equal(transition.ignored, false);
  transition = applyGuardEvent(transition.state, { event: "blocked_app_opened" }, "2026-08-28T14:03:00.000Z", config);
  assert.equal(transition.stage, "locked");
  assert.equal(transition.state.unlocks_revoked, false);
  transition = applyGuardEvent(transition.state, { event: "blocked_app_opened" }, "2026-08-28T14:03:30.000Z", config);
  assert.equal(transition.stage, "refused_sleep");
  assert.equal(transition.state.unlocks_revoked, false);
  transition = applyGuardEvent(transition.state, { event: "temporary_unlock_requested" }, "2026-08-28T14:03:45.000Z", config);
  assert.equal(transition.ignored, false);
  assert.equal(transition.state.unlock_request_count, 2);
  transition = applyGuardEvent(transition.state, { event: "temporary_unlock_requested" }, "2026-08-28T14:03:50.000Z", config);
  assert.equal(transition.state.unlock_request_count, 3);
  assert.equal(transition.state.unlocks_revoked, true);
  transition = applyGuardEvent(transition.state, { event: "temporary_unlock_requested" }, "2026-08-28T14:03:55.000Z", config);
  assert.equal(transition.ignored, true);
  assert.equal(transition.state.unlock_request_count, 3);
  transition = applyGuardEvent(transition.state, { event: "sleep_guard_ended" }, "2026-08-28T14:04:00.000Z", config);
  assert.equal(transition.state.active, false);
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

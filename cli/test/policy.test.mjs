import test from "node:test";
import assert from "node:assert/strict";
import { evaluateQuietHours, inspectPolicy } from "../src/policy.mjs";

test("policy precedence is explicit, Channel, global, then preset", () => {
  const base = { event: "failed", durationReliable: false, now: new Date("2026-01-01T12:00:00Z") };
  assert.equal(inspectPolicy({ ...base, explicitAction: "off", channelPolicy: { events: { failed: "ring" } }, globalPolicy: { events: { failed: "quiet" } } }).source, "explicit");
  assert.equal(inspectPolicy({ ...base, channelPolicy: { events: { failed: "ring" } }, globalPolicy: { events: { failed: "quiet" } } }).action, "ring");
  assert.equal(inspectPolicy({ ...base, globalPolicy: { events: { failed: "quiet" } } }).action, "quiet");
  assert.equal(inspectPolicy(base).action, "notify");
});

test("balanced completion requires reliable duration at its threshold", () => {
  const globalPolicy = { preset: "balanced" };
  assert.equal(inspectPolicy({ event: "completed", globalPolicy }).action, "off");
  assert.equal(inspectPolicy({ event: "completed", globalPolicy, durationReliable: true, durationSeconds: 59 }).action, "off");
  assert.equal(inspectPolicy({ event: "completed", globalPolicy, durationReliable: true, durationSeconds: 60 }).action, "quiet");
});

test("ring can only come from an explicit action or explicit event rule", () => {
  assert.equal(inspectPolicy({ event: "blocked", explicitAction: "ring" }).action, "ring");
  assert.equal(inspectPolicy({ event: "completed", explicitAction: "ring" }).action, "ring");
  assert.equal(inspectPolicy({ event: "blocked", globalPolicy: { events: { blocked: "ring" } } }).action, "ring");
  assert.equal(inspectPolicy({ event: "blocked", globalPolicy: { preset: "balanced" } }).action, "notify");
  assert.throws(() => inspectPolicy({ event: "blocked", repositoryPolicy: { events: { blocked: "ring" } } }), /不得授權 ring/);
  assert.equal(inspectPolicy({ event: "blocked", repositoryPolicy: { events: { blocked: "quiet" } } }).action, "quiet");
});

test("cross-midnight quiet hours downgrade notify without swallowing ring", () => {
  const quietHours = { start: "22:00", end: "07:00", timeZone: "UTC", mode: "downgrade" };
  assert.equal(evaluateQuietHours(quietHours, new Date("2026-01-01T23:00:00Z")).active, true);
  assert.equal(evaluateQuietHours(quietHours, new Date("2026-01-01T12:00:00Z")).active, false);
  assert.equal(inspectPolicy({ event: "failed", globalPolicy: { quietHours }, now: new Date("2026-01-01T23:00:00Z") }).action, "quiet");
  assert.equal(inspectPolicy({ event: "failed", explicitAction: "ring", globalPolicy: { quietHours }, now: new Date("2026-01-01T23:00:00Z") }).action, "ring");
});

test("invalid timezone and conflicting values fail closed", () => {
  assert.throws(() => evaluateQuietHours({ start: "22:00", end: "07:00", timeZone: "Mars/Olympus" }), /時區/);
  assert.throws(() => inspectPolicy({ event: "unknown" }), /未知/);
  assert.throws(() => inspectPolicy({ event: "failed", globalPolicy: { events: { failed: "page-everyone" } } }), /無效/);
  assert.throws(() => inspectPolicy({ event: "failed", globalPolicy: { automationToken: "forbidden" } }), /未知欄位/);
});

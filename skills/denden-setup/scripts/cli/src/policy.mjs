const EVENTS = new Set(["completed", "failed", "partial", "blocked", "needs-reply", "manual"]);
const ACTIONS = new Set(["off", "quiet", "notify", "ring"]);

export const PRESETS = {
  important: {
    events: { completed: "off", failed: "notify", partial: "off", blocked: "notify", "needs-reply": "notify", manual: "notify" },
  },
  balanced: {
    minCompletedDurationSeconds: 60,
    events: { completed: "quiet", failed: "notify", partial: "notify", blocked: "notify", "needs-reply": "notify", manual: "notify" },
  },
  "all-completed": {
    events: { completed: "quiet", failed: "notify", partial: "notify", blocked: "notify", "needs-reply": "notify", manual: "notify" },
  },
};

export function inspectPolicy({ event, durationSeconds, durationReliable = false, explicitAction, channelPolicy, globalPolicy, repositoryPolicy, now = new Date() }) {
  if (!EVENTS.has(event)) throw new Error(`未知標準事件: ${event}`);
  if (explicitAction !== undefined && !ACTIONS.has(explicitAction)) throw new Error(`無效明確動作: ${explicitAction}`);
  validatePolicy(channelPolicy, "Channel");
  validatePolicy(globalPolicy, "全域");
  validatePolicy(repositoryPolicy, "repository Channel", { allowRing: false });

  const presetName = channelPolicy?.preset || globalPolicy?.preset || repositoryPolicy?.preset || "all-completed";
  const preset = PRESETS[presetName];
  if (!preset) throw new Error(`未知政策 preset: ${presetName}`);
  const candidates = [
    ["explicit", explicitAction],
    ["channel", channelPolicy?.events?.[event]],
    ["global", globalPolicy?.events?.[event]],
    ["repository", repositoryPolicy?.events?.[event]],
    [`preset:${presetName}`, preset.events[event]],
  ];
  let [source, action] = candidates.find(([, value]) => value !== undefined) || [];
  if (!ACTIONS.has(action)) throw new Error(`事件 ${event} 的政策動作無效`);

  const threshold = channelPolicy?.minCompletedDurationSeconds ?? globalPolicy?.minCompletedDurationSeconds ?? repositoryPolicy?.minCompletedDurationSeconds ?? preset.minCompletedDurationSeconds;
  const reasons = [];
  if (source !== "explicit" && event === "completed" && threshold !== undefined) {
    if (!durationReliable) {
      action = "off";
      reasons.push("缺少可靠耗時，未達完成通知門檻");
    } else if (!Number.isFinite(durationSeconds) || durationSeconds < threshold) {
      action = "off";
      reasons.push(`可靠耗時未達 ${threshold} 秒門檻`);
    }
  }

  const quietHours = channelPolicy?.quietHours ?? globalPolicy?.quietHours ?? repositoryPolicy?.quietHours;
  const quiet = quietHours ? evaluateQuietHours(quietHours, now) : { active: false };
  if (quiet.active && action !== "ring" && action !== "off") {
    if ((quietHours.mode || "downgrade") === "skip") {
      action = "off";
      reasons.push("命中安靜時段並略過通知");
    } else if (action === "notify") {
      action = "quiet";
      reasons.push("命中安靜時段並降級為 quiet");
    }
  }
  if (action === "ring" && source !== "explicit" && !isExplicitRingRule(channelPolicy, globalPolicy, event, source)) {
    action = "notify";
    reasons.push("ring 缺少明確事前規則，降級為 notify");
  }
  return { event, action, source, preset: presetName, durationReliable, durationSeconds: durationSeconds ?? null, threshold: threshold ?? null, quietHours: quiet, reasons };
}

export function validatePolicy(policy, label, { allowRing = true } = {}) {
  if (policy == null) return;
  if (typeof policy !== "object" || Array.isArray(policy)) throw new Error(`${label}政策必須是物件`);
  const allowed = new Set(["preset", "events", "minCompletedDurationSeconds", "quietHours"]);
  const unknown = Object.keys(policy).filter((key) => !allowed.has(key));
  if (unknown.length) throw new Error(`${label}政策包含未知欄位: ${unknown.join(", ")}`);
  if (policy.events !== undefined && (!policy.events || typeof policy.events !== "object" || Array.isArray(policy.events))) {
    throw new Error(`${label}政策 events 必須是物件`);
  }
  for (const [event, action] of Object.entries(policy.events || {})) {
    if (!EVENTS.has(event) || !ACTIONS.has(action)) throw new Error(`${label}政策事件或動作無效: ${event}`);
    if (!allowRing && action === "ring") throw new Error(`${label}政策不得授權 ring`);
  }
  if (policy.preset !== undefined && !PRESETS[policy.preset]) throw new Error(`${label}政策 preset 無效`);
  if (policy.minCompletedDurationSeconds !== undefined && (!Number.isFinite(policy.minCompletedDurationSeconds) || policy.minCompletedDurationSeconds < 0)) {
    throw new Error(`${label}政策耗時門檻無效`);
  }
  if (policy.quietHours !== undefined) {
    const quietHours = policy.quietHours;
    if (!quietHours || typeof quietHours !== "object" || Array.isArray(quietHours)) throw new Error(`${label}安靜時段必須是物件`);
    const quietUnknown = Object.keys(quietHours).filter((key) => !new Set(["start", "end", "timeZone", "mode"]).has(key));
    if (quietUnknown.length) throw new Error(`${label}安靜時段包含未知欄位: ${quietUnknown.join(", ")}`);
    evaluateQuietHours(quietHours, new Date(0));
  }
}

function isExplicitRingRule(channelPolicy, globalPolicy, event, source) {
  if (source === "channel") return channelPolicy?.events?.[event] === "ring";
  if (source === "global") return globalPolicy?.events?.[event] === "ring";
  return false;
}

export function evaluateQuietHours(value, now = new Date()) {
  const { start, end, timeZone, mode = "downgrade" } = value;
  if (!/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(start || "") || !/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(end || "")) {
    throw new Error("安靜時段必須使用 HH:mm");
  }
  if (!timeZone) throw new Error("安靜時段必須設定 IANA 時區");
  if (!new Set(["skip", "downgrade"]).has(mode)) throw new Error("安靜時段 mode 無效");
  let parts;
  try {
    parts = new Intl.DateTimeFormat("en-GB", { timeZone, hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).formatToParts(now);
  } catch {
    throw new Error(`無效 IANA 時區: ${timeZone}`);
  }
  const hour = Number(parts.find((part) => part.type === "hour")?.value);
  const minute = Number(parts.find((part) => part.type === "minute")?.value);
  const current = hour * 60 + minute;
  const toMinutes = (text) => Number(text.slice(0, 2)) * 60 + Number(text.slice(3));
  const from = toMinutes(start);
  const to = toMinutes(end);
  const active = from === to ? true : from < to ? current >= from && current < to : current >= from || current < to;
  return { active, timeZone, start, end, mode, localMinutes: current };
}

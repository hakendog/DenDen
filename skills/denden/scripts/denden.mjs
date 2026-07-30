#!/usr/bin/env node
import { listPresets, runChannelCommand, runDailyCommand } from "./runtime/commands.mjs";

const RUNTIME_PROTOCOL = "direct-fcm-v2";

async function main() {
  const argv = process.argv.slice(2);
  if (argv[0] === "capabilities") {
    if (argv.length !== 1) throw new Error("capabilities 不接受參數");
    return { schemaVersion: 1, runtimeProtocol: RUNTIME_PROTOCOL, requiresAutomationToken: false };
  }
  if (["setup", "integration"].includes(argv[0])) throw new Error("DenDen 日常 skill CLI 不提供設定或整合管理命令");
  if (argv[0] === "channel") return runChannelCommand(argv.slice(1));
  if (argv[0] === "presets") return listPresets();
  if (["--help", "help", undefined].includes(argv[0])) {
    return { usage: "denden <capabilities|report|notify|ring|stop|policy inspect|channel>" };
  }
  return runDailyCommand(argv);
}

try {
  process.stdout.write(`${JSON.stringify(await main(), null, 2)}\n`);
} catch (error) {
  process.stderr.write(`${JSON.stringify({ error: error.message, status: error.status })}\n`);
  process.exitCode = 1;
}

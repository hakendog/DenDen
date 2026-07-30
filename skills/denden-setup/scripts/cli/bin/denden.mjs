#!/usr/bin/env node
import { listPresets, runChannelCommand, runDailyCommand } from "../src/commands.mjs";
import { runIntegrationHook } from "../src/integration.mjs";
import { runDirectInstallCommand, runDirectRollbackCommand } from "../src/direct-setup.mjs";
import { runDirectBrandCommand } from "../src/direct-branding.mjs";
import { runPortableConfigCommand } from "../src/portable-config.mjs";
import { RUNTIME_PROTOCOL } from "../src/source.mjs";
import {
  runDirectDoctorCommand,
  runFirstSetupCommand,
  runFirstSetupPlanCommand,
  runDailySkillInstallCommand,
  runDailySkillPlanCommand,
  runManagementAuthCommand,
  runManagementRevokeCommand,
  runManagementRevokePlanCommand,
  runPairingStatusCommand,
  runReissueQrCommand,
  runRotatePairingCommand,
  runRotatePlanCommand,
  runSenderAuthCommand,
  runSenderAuthPlanCommand,
  runSenderRevokeCommand,
  runSenderRevokePlanCommand,
  runSenderVerifyCommand,
  runRemoveQrCommand,
  runWithSetupMutationLock,
} from "../src/setup-operations.mjs";

async function main() {
  const argv = process.argv.slice(2);
  if (argv[0] === "capabilities") {
    if (argv.length !== 1) throw new Error("capabilities 不接受參數");
    return { schemaVersion: 1, runtimeProtocol: RUNTIME_PROTOCOL, requiresAutomationToken: false };
  }
  if (argv[0] === "channel") return runChannelCommand(argv.slice(1));
  if (argv[0] === "setup" && [undefined, "help", "--help"].includes(argv[1])) return setupHelp();
  if (argv[0] === "setup" && argv[1] === "install") return runDirectInstallCommand(argv.slice(2));
  if (argv[0] === "setup" && argv[1] === "rollback") return runDirectRollbackCommand(argv.slice(2));
  if (argv[0] === "setup" && argv[1] === "doctor") return runDirectDoctorCommand(argv.slice(2));
  if (argv[0] === "setup" && argv[1] === "management-auth") return lockedSetup(argv.slice(2), runManagementAuthCommand);
  if (argv[0] === "setup" && argv[1] === "management-revoke") return lockedSetup(argv.slice(2), runManagementRevokeCommand);
  if (argv[0] === "setup" && argv[1] === "management-revoke-plan") return runManagementRevokePlanCommand(argv.slice(2));
  if (argv[0] === "setup" && argv[1] === "sender-auth-plan") return runSenderAuthPlanCommand(argv.slice(2));
  if (argv[0] === "setup" && argv[1] === "sender-auth") return lockedSetup(argv.slice(2), runSenderAuthCommand);
  if (argv[0] === "setup" && argv[1] === "sender-verify") return runSenderVerifyCommand(argv.slice(2));
  if (argv[0] === "setup" && argv[1] === "sender-revoke-plan") return runSenderRevokePlanCommand(argv.slice(2));
  if (argv[0] === "setup" && argv[1] === "sender-revoke") return lockedSetup(argv.slice(2), runSenderRevokeCommand);
  if (argv[0] === "setup" && argv[1] === "plan") return runFirstSetupPlanCommand(argv.slice(2));
  if (argv[0] === "setup" && argv[1] === "direct") return runFirstSetupCommand(argv.slice(2));
  if (argv[0] === "setup" && argv[1] === "status") return runPairingStatusCommand(argv.slice(2));
  if (argv[0] === "setup" && argv[1] === "qr") return lockedSetup(argv.slice(2), runReissueQrCommand);
  if (argv[0] === "setup" && argv[1] === "qr-remove") return lockedSetup(argv.slice(2), runRemoveQrCommand);
  if (argv[0] === "setup" && argv[1] === "rotate-plan") return runRotatePlanCommand(argv.slice(2));
  if (argv[0] === "setup" && argv[1] === "rotate") return lockedSetup(argv.slice(2), runRotatePairingCommand);
  if (argv[0] === "setup" && ["export", "import"].includes(argv[1])) return lockedSetup(argv.slice(1), runPortableConfigCommand);
  if (argv[0] === "setup" && argv[1] === "skill-plan") return runDailySkillPlanCommand(argv.slice(2));
  if (argv[0] === "setup" && argv[1] === "skill-install") return runDailySkillInstallCommand(argv.slice(2));
  if (argv[0] === "setup" && argv[1] === "brand") return lockedSetup(argv.slice(2), runDirectBrandCommand);
  if (argv[0] === "integration" && argv[1] === "hook") return runIntegrationHook();
  if (argv[0] === "presets") return listPresets();
  if (["--help", "help", undefined].includes(argv[0])) {
    return {
      usage: "denden <capabilities|report|notify|ring|stop|policy inspect|channel|setup install|setup direct|setup brand|integration hook>",
      note: "notify/ring 可重複使用 --tag；ring 僅供明確要求或已設定的確定規則使用；stop 只解除手機警報。",
    };
  }
  return runDailyCommand(argv);
}

function lockedSetup(args, command) {
  return runWithSetupMutationLock(args, () => command(args));
}

function setupHelp() {
  return {
    usage: "denden setup <install|rollback|doctor|management-auth|plan|direct|sender-auth-plan|sender-auth|sender-verify|sender-revoke-plan|sender-revoke|status|qr|qr-remove|rotate-plan|rotate|export|import|brand|skill-plan|skill-install|management-revoke-plan|management-revoke>",
    note: "先以 plan 選定日常技能目的地並顯示完整初次設定摘要；direct 使用同一 digest 完成 Firebase、最低權限發送身分、驗證、管理登入撤銷與技能安裝。",
  };
}

try {
  const result = await main();
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
} catch (error) {
  process.stderr.write(`${JSON.stringify({ error: error.message, status: error.status })}\n`);
  process.exitCode = 1;
}

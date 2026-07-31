import { spawn } from "node:child_process";

export async function runExternal(command, args, {
  input,
  env = process.env,
  cwd = process.cwd(),
  interactive = false,
  timeoutMillis = interactive ? 15 * 60_000 : 60_000,
} = {}) {
  if (typeof command !== "string" || !command.trim() || /[\0\r\n]/.test(command) ||
      !Array.isArray(args) || args.some((value) => typeof value !== "string" || value.includes("\0"))) {
    throw new Error("外部命令或參數無效");
  }
  if (!Number.isSafeInteger(timeoutMillis) || timeoutMillis < 1_000 || timeoutMillis > 30 * 60_000) {
    throw new Error("外部命令 timeout 無效");
  }
  return new Promise((resolve, reject) => {
    let executable = command;
    let commandArgs = args;
    const native = new Set(["git", "node", "winget"]);
    const windowsCommand = /\.[a-z0-9]+$/i.test(command)
      ? command
      : native.has(command.toLowerCase()) ? `${command}.exe` : `${command}.cmd`;
    if (process.platform === "win32" && /\.(cmd|bat)$/i.test(windowsCommand)) {
      const unsafe = [windowsCommand, ...args].find((value) => typeof value !== "string" || /[\r\n"&|<>^()%!]/.test(value));
      if (unsafe !== undefined) {
        reject(new Error(`${command} 參數含有 Windows 批次命令不安全字元`));
        return;
      }
      executable = env.ComSpec || "cmd.exe";
      commandArgs = ["/d", "/s", "/c", windowsCommand, ...args];
    } else if (process.platform === "win32") {
      executable = windowsCommand;
    }
    const child = spawn(executable, commandArgs, {
      cwd,
      env,
      shell: false,
      windowsHide: true,
      stdio: interactive ? "inherit" : [input === undefined ? "ignore" : "pipe", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    child.stdout?.on("data", (chunk) => { stdout += chunk; });
    child.stderr?.on("data", (chunk) => { stderr += chunk; });
    let settled = false;
    const finish = (callback) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      callback();
    };
    const timer = setTimeout(() => {
      child.kill();
      finish(() => reject(new Error(`${command} ${args[0] || ""} 執行逾時（${timeoutMillis}ms）；可安全重跑目前階段`)));
    }, timeoutMillis);
    timer.unref?.();
    child.on("error", (error) => finish(() => reject(error)));
    child.on("close", (code) => finish(() => code === 0
      ? resolve({ stdout: stdout.trim(), stderr: stderr.trim() })
      : reject(new Error(`${command} ${args[0] || ""} 失敗（exit ${code}）：${redactOutput(stderr.trim() || stdout.trim()).slice(0, 500)}`))));
    if (input !== undefined) child.stdin?.end(input);
  });
}

function redactOutput(value) {
  return String(value || "")
    .replace(/DDC\.[A-Za-z0-9_-]+/g, "[REDACTED_DDC]")
    .replace(/Bearer\s+[A-Za-z0-9._~-]+/gi, "Bearer [REDACTED]")
    .replace(/-----BEGIN PRIVATE KEY-----[\s\S]*?-----END PRIVATE KEY-----/g, "[REDACTED_PRIVATE_KEY]")
    .replace(/("(?:access_token|refresh_token|private_key|client_secret|eventKey|brandKey|topic)"\s*:\s*")[^"]+("?)/gi, "$1[REDACTED]$2");
}

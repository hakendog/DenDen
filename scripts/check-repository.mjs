import { execFileSync } from "node:child_process";
import { lstat, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const contentRules = [
  ["Google API key", /AIza[0-9A-Za-z_-]{35}/],
  ["Google OAuth access token", /ya29\.[0-9A-Za-z_-]{20,}/],
  ["Google OAuth refresh token", /1\/\/[0-9A-Za-z_-]{20,}/],
  ["GitHub token", /gh[pousr]_[0-9A-Za-z]{36,}/],
  [
    "private key",
    /-----BEGIN ((?:RSA |EC |OPENSSH )?PRIVATE KEY)-----[\r\n]+[0-9A-Za-z+/=\r\n]{200,}-----END \1-----/,
  ],
];

export function isForbiddenRepositoryPath(value) {
  const path = value.replaceAll("\\", "/");
  return /(^|\/)\.signing\//i.test(path) ||
    /(^|\/)\.denden\.json$/i.test(path) ||
    /(^|\/)google-services\.json$/i.test(path) ||
    /(^|\/)(?:service[-_]?account|application_default_credentials)[^/]*\.json$/i.test(path) ||
    /\.(?:jks|keystore|p12|pfx|pem|key)$/i.test(path);
}

export function findForbiddenRepositoryContent(value) {
  const normalized = value.replaceAll("\\n", "\n");
  return contentRules.filter(([, pattern]) => pattern.test(normalized)).map(([label]) => label);
}

async function main() {
  const files = execFileSync(
    "git",
    ["ls-files", "--cached", "--others", "--exclude-standard", "-z"],
    { encoding: "buffer" },
  ).toString("utf8").split("\0").filter(Boolean);
  const failures = [];
  let scanned = 0;

  for (const path of files) {
    if (isForbiddenRepositoryPath(path)) failures.push(`${path}: 禁止的秘密或簽章檔名`);
    const info = await lstat(path).catch((error) => {
      if (error.code === "ENOENT") return null;
      throw error;
    });
    if (info === null || !info.isFile()) continue;
    const bytes = await readFile(path);
    scanned++;
    if (bytes.includes(0)) continue;
    for (const label of findForbiddenRepositoryContent(bytes.toString("utf8"))) {
      failures.push(`${path}: 疑似 ${label}`);
    }
  }

  if (failures.length > 0) {
    console.error(failures.join("\n"));
    process.exitCode = 1;
    return;
  }
  console.log(`repository safety check passed (${scanned} files)`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();

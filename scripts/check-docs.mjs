import { existsSync, readFileSync, readdirSync } from "node:fs";
import { dirname, extname, join, relative, resolve, sep } from "node:path";

const root = resolve(import.meta.dirname, "..");
const zhPages = markdownFiles(join(root, "docs", "zh-TW")).map((path) => relative(join(root, "docs", "zh-TW"), path)).sort();
const enPages = markdownFiles(join(root, "docs", "en")).map((path) => relative(join(root, "docs", "en"), path)).sort();
const files = [
  "README.md",
  "README.zh-TW.md",
  "SECURITY.md",
  "docs/README.md",
  "docs/agent-install.md",
  ...markdownFiles(join(root, "docs", "en")).map((path) => relative(root, path)),
  ...markdownFiles(join(root, "docs", "zh-TW")).map((path) => relative(root, path)),
];
const failures = [];
const banned = /<owner>|<verified-full-commit-sha>|D:\\workspace\\DenDen|UNLICENSED|尚未發布的本機來源|主要賣點|預發布|本機驗收|刻意保持不可執行/;
const unpinnedInstallGuide = /raw\.githubusercontent\.com\/hakendog\/DenDen\/(?![0-9a-f]{40}\/)/;

if (JSON.stringify(enPages) !== JSON.stringify(zhPages)) failures.push("繁中與英文文件頁面必須一一對應");

for (const file of files) {
  const path = join(root, file);
  const text = readFileSync(path, "utf8");
  const headings = [...text.matchAll(/^(#{1,6})\s+.+$/gm)].map((match) => match[1].length);
  if (headings.filter((level) => level === 1).length !== 1) failures.push(`${file}: 必須恰有一個 H1`);
  for (let index = 1; index < headings.length; index += 1) {
    if (headings[index] > headings[index - 1] + 1) failures.push(`${file}: 標題階層不連續`);
  }
  if (banned.test(text)) failures.push(`${file}: 含公開文件禁止內容`);
  if (file !== "docs/agent-install.md" && unpinnedInstallGuide.test(text)) {
    failures.push(`${file}: AI 安裝入口必須使用已發佈的完整 commit SHA`);
  }

  for (const match of text.matchAll(/!?\[([^\]]*)\]\(([^)]+)\)/g)) {
    const isImage = match[0].startsWith("!");
    if (isImage && !match[1].trim()) failures.push(`${file}: 圖片缺少 alt text`);
    checkTarget(file, match[2]);
  }
  for (const match of text.matchAll(/<img\s+[^>]*src="([^"]+)"[^>]*>/gi)) {
    if (!/\balt="[^"]+"/i.test(match[0])) failures.push(`${file}: HTML 圖片缺少 alt text`);
    checkTarget(file, match[1]);
  }
}

if (failures.length) {
  console.error(failures.join("\n"));
  process.exitCode = 1;
} else {
  console.log(`documentation check passed (${files.length} files)`);
}

function checkTarget(file, rawTarget) {
  const target = rawTarget.trim().replace(/^<|>$/g, "").split("#", 1)[0];
  if (!target || /^(?:https?:|mailto:)/i.test(target)) return;
  if (/^[A-Za-z]:[\\/]|^[/\\]/.test(target)) {
    failures.push(`${file}: 站內連結必須使用相對路徑: ${rawTarget}`);
    return;
  }
  const destination = resolve(root, dirname(file), decodeURIComponent(target));
  if (destination !== root && !destination.startsWith(`${root}${sep}`)) failures.push(`${file}: 連結逸出 repository: ${rawTarget}`);
  else if (!existsSync(destination)) failures.push(`${file}: 找不到連結目標: ${rawTarget}`);
}

function markdownFiles(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? markdownFiles(path) : extname(entry.name) === ".md" ? [path] : [];
  });
}

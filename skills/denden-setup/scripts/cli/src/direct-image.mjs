import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { PNG } from "pngjs";

const MAX_IMAGE_BYTES = 64 * 1024;
const QUANTIZATION_BITS = [7, 6, 5, 4, 3];

export function validateBackgroundColor(value) {
  return validateColor(value, "DenDen 背景色");
}

export function validateBrandColor(value) {
  return validateColor(value, "DenDen 品牌色");
}

export async function prepareDirectBrandImage(inputPath) {
  const source = PNG.sync.read(await readFile(resolve(inputPath)));
  const image = resize(source, 512, 512);
  validateSubject(image, alphaBounds(image));
  const original = PNG.sync.write(image);
  if (original.length <= MAX_IMAGE_BYTES) return original;
  for (const bits of QUANTIZATION_BITS) {
    const candidate = PNG.sync.write(quantize(image, bits));
    if (candidate.length <= MAX_IMAGE_BYTES) return candidate;
  }
  throw new Error("遠端品牌 PNG 無法壓縮到 64 KiB 內");
}

export async function createWhiteBrandPreview(inputPath) {
  const image = PNG.sync.read(await prepareDirectBrandImage(inputPath));
  for (let index = 0; index < image.data.length; index += 4) {
    const alpha = image.data[index + 3] / 255;
    for (let channel = 0; channel < 3; channel += 1) {
      image.data[index + channel] = Math.round(image.data[index + channel] * alpha + 255 * (1 - alpha));
    }
    image.data[index + 3] = 255;
  }
  return PNG.sync.write(image);
}

function quantize(source, bits) {
  const result = new PNG({ width: source.width, height: source.height });
  result.data.set(source.data);
  const levels = (1 << bits) - 1;
  for (let index = 0; index < result.data.length; index += 4) {
    if (result.data[index + 3] < 16) {
      result.data.fill(0, index, index + 4);
      continue;
    }
    for (let channel = 0; channel < 4; channel += 1) {
      result.data[index + channel] = Math.round(Math.round(result.data[index + channel] * levels / 255) * 255 / levels);
    }
  }
  return result;
}

function resize(source, width, height) {
  const result = new PNG({ width, height });
  for (let y = 0; y < height; y += 1) for (let x = 0; x < width; x += 1) {
    result.data.set(sample(source, ((x + 0.5) * source.width) / width - 0.5, ((y + 0.5) * source.height) / height - 0.5), (y * width + x) * 4);
  }
  return result;
}

function sample(image, x, y) {
  const x0 = clamp(Math.floor(x), 0, image.width - 1);
  const y0 = clamp(Math.floor(y), 0, image.height - 1);
  const x1 = clamp(x0 + 1, 0, image.width - 1);
  const y1 = clamp(y0 + 1, 0, image.height - 1);
  const fx = clamp(x - Math.floor(x), 0, 1);
  const fy = clamp(y - Math.floor(y), 0, 1);
  return [0, 1, 2, 3].map((channel) => {
    const a = image.data[(y0 * image.width + x0) * 4 + channel] * (1 - fx) + image.data[(y0 * image.width + x1) * 4 + channel] * fx;
    const b = image.data[(y1 * image.width + x0) * 4 + channel] * (1 - fx) + image.data[(y1 * image.width + x1) * 4 + channel] * fx;
    return Math.round(a * (1 - fy) + b * fy);
  });
}

function alphaBounds(image) {
  let left = image.width; let right = -1; let top = image.height; let bottom = -1; let pixels = 0;
  for (let y = 0; y < image.height; y += 1) for (let x = 0; x < image.width; x += 1) {
    if (image.data[(y * image.width + x) * 4 + 3] < 16) continue;
    left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = Math.max(bottom, y); pixels += 1;
  }
  return { left, right, top, bottom, pixels };
}

function validateSubject(image, bounds) {
  const total = image.width * image.height;
  if (bounds.pixels < total * 0.02 || bounds.pixels > total * 0.85) throw new Error("角色佔圖比例不合理");
  if (bounds.left < 16 || bounds.top < 16 || bounds.right > image.width - 17 || bounds.bottom > image.height - 17) {
    throw new Error("角色必須完整置中並保留安全留白");
  }
}

function clamp(value, minimum, maximum) { return Math.max(minimum, Math.min(maximum, value)); }

function validateColor(value, label) {
  const color = String(value || "").trim().toUpperCase();
  if (!/^#[0-9A-F]{6}$/.test(color)) throw new Error(`${label}必須是 #RRGGBB`);
  return color;
}

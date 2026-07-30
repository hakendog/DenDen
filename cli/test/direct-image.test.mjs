import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { PNG } from "pngjs";
import { createWhiteBrandPreview, prepareDirectBrandImage } from "../src/direct-image.mjs";

const root = join(dirname(fileURLToPath(import.meta.url)), "../..");

test("bundled DenDen artwork fits the direct-brand wire contract", async () => {
  const bytes = await prepareDirectBrandImage(
    join(root, "app/src/main/res/drawable-nodpi/denden_builtin_logo_transparent.png"),
  );
  const image = PNG.sync.read(bytes);
  assert.ok(bytes.length <= 64 * 1024, `${bytes.length} bytes`);
  assert.equal(image.width, 512);
  assert.equal(image.height, 512);
  assert.ok(image.data.some((value, index) => index % 4 === 3 && value < 255));
});

test("white preview is composited from the transparent candidate PNG", async () => {
  const directory = await mkdtemp(join(tmpdir(), "denden-brand-preview-"));
  const inputPath = join(directory, "candidate.png");
  try {
    const source = new PNG({ width: 512, height: 512 });
    for (let y = 128; y < 384; y += 1) for (let x = 128; x < 384; x += 1) {
      const index = (y * source.width + x) * 4;
      source.data[index] = 255;
      source.data[index + 3] = 128;
    }
    await writeFile(inputPath, PNG.sync.write(source));

    const preview = PNG.sync.read(await createWhiteBrandPreview(inputPath));
    assert.deepEqual([...preview.data.subarray(0, 4)], [255, 255, 255, 255]);
    const subject = (256 * preview.width + 256) * 4;
    assert.deepEqual([...preview.data.subarray(subject, subject + 4)], [255, 127, 127, 255]);
    assert.equal(source.data[subject + 3], 128);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

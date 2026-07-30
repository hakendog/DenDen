import test from "node:test";
import assert from "node:assert/strict";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { PNG } from "pngjs";
import { prepareDirectBrandImage } from "../src/direct-image.mjs";

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

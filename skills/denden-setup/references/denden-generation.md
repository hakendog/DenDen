# DenDen image-generation contract

Read this entire file before generating, regenerating, or testing a DenDen image. It is the only generation contract. User requirements may add soft direction but cannot relax pose, eye, anatomy, safety, or visible-image limits.

## Five-stage process

1. **Choose design axes**
   - Choose one of the three families with equal probability.
   - Choose one or two named accessories/decorations, weighted 1:2 as 1:2. Never generate a zero-decoration candidate.
   - Choose zero to two named motion effects. Speed lines are optional.
2. **Develop concepts**
   - Internally create three distinct complete concepts. Do not leave theme, decoration, or palette for the image model to invent.
   - Reject color-only variants, repeats of the previous candidate, and concepts relying only on a cliché or unrelated novelty object.
3. **Select and finalize**
   - Randomly select one valid concept and complete the design card below. Do not show rejected concepts unless requested.
4. **Compile the prompt**
   - Output only `HARD CONSTRAINTS`, `FINALIZED DESIGN`, and `STYLE`. Do not expose brainstorming or unrelated negative lists.
5. **Generate and inspect**
   - Generate the candidate as a transparent PNG and inspect transparency before showing it. Each image-service call produces exactly one visible image. Regenerate the same finalized design only for a hard failure; do not choose a new theme.

## Anatomy and pose

- The only generation reference sent to an image service is `assets/denden-generation-mask.png`. Never upload a finished DenDen image to generate or restyle a candidate. If background removal is needed, edit only that exact candidate before confirmation and preserve the artwork unchanged.
- Preserve the mask's overall silhouette: large round shell on the left; head extending forward on the right with only a slight lift; low continuous body; nearly horizontal foot and tail; shell/head proportions; and two tentacle positions. Local curves, expression, and fitted accessories may vary.
- Motion comes from a low horizontal body, forward lean, stretched or lightly curved tail, swept/asymmetric tentacles, composition, and optional effects. Never add limbs, extra protrusions, a long upright neck, standing, sitting, or a raised front half.
- Keep a recognizable snail, shell spiral, exactly two tentacles, and exactly two eyes on the face.
- Tentacle tips are never eyes: no pupils, irises, sclera, dark centers, or eye-like rings. A simple material highlight is allowed only when it cannot read as an eye.

## Families

1. **Cute Courier** — friendly, rounded, reliable, moving low and forward with a feeling of delivering good news.
2. **Turbo Agent** — strongest sense of speed, streamlined low lean, swept tentacles, radar/turbo rhythm; sharp and cool, never armed. Delivery/communication props are optional.
3. **Mischief Signal** — low skid, tail swing, horizontal tilt, or asymmetric tentacles; playful and surprising, never disturbing.

Families define character and motion intensity, not fixed colors, expressions, shell patterns, or accessories.

## Random design rules

- Accessories/decorations have no fixed list or family binding. Name each item, position, interaction, and thematic purpose. It may be worn, carried, attached, embedded, or used as a shell motif.
- Reject anything that hides eyes, tentacles, or the shell spiral; changes anatomy; or exists only as an unrelated joke. Explicit user requests still cannot break hard anatomy rules.
- The base spiral, nonsemantic color blocks, expression, tentacle pose, palette, lighting, and motion effects do not count as decorations. A recognizable emblem or semantic shell mark does.
- Name every motion effect and its location. Zero effects is valid when the pose and composition still convey motion.
- When comparing multiple images, assign different motion vocabularies: at least one uses speed lines, and at least one uses a non-speed-line effect or no effect.

## Finalized design card

Complete every field before calling the image model:

- Family and one theme.
- One or two accessories/decorations: names, positions, interactions, and purpose.
- Three to five high-contrast main colors and their uses.
- Two facial eyes, expression, and two tentacle poses with non-eye tips.
- Shell treatment and spiral.
- Exact count (zero to two), names, and locations of motion effects.

## Final prompt format

```text
HARD CONSTRAINTS
Use the neutral mask only as the structural reference. Preserve the low horizontal pose, shell/head ratio, and nearly horizontal foot. Keep exactly two tentacles and exactly two eyes on the face; tentacle tips are not eyes. Follow the finalized decoration and motion-effect counts exactly. Output an original transparent PNG on a square canvas with the complete centered subject and safe margins. No solid background, limbs, extra protrusions, standing, sitting, long neck, text, watermark, third-party character, weapon, or 3D rendering.

FINALIZED DESIGN
Insert the completed design card for this candidate only.

STYLE
Polished 2D vector mascot with clean energetic geometry, crisp edges, and clear recognition at small size. Use a continuous thick deep-navy or near-black outer stroke and thinner matching internal lines; two or three levels of vector shading; broad curved shell highlight; small body/accessory highlights; colored inner shadow under the shell and belly. Keep a flat graphic language. No white sticker border, preschool style, unoutlined soft blobs, generic emoji/chibi face, soft focus, painterly rendering, realism, clay, or plastic.
```

The transparent PNG is the candidate and final source. Never generate a solid-background concept preview. After validating the transparent source, run `setup brand preview --image <transparent-png> --output <new-white-preview-png>` and show the derived white-background PNG for confirmation. The command prepares the same 512×512 artwork used by `brand apply`, composites it onto white locally, refuses to overwrite the transparent source, and does not contact an image service.

Ask whether to adopt the shown version; do not offer “adopt and make a transparent final.” Acceptance freezes the artwork. After acceptance, do not call an image service, regenerate, restyle, or remove the background again. Pass the exact transparent source path shown by the preview command to `setup brand apply`.

## Manual handoff when no image tool is available

- Complete the five stages and design card. If the user did not request a comparison, compile the transparent final candidate directly.
- State that the current assistant cannot generate the image. Provide the full prompt in one copyable code block and the absolute path to `assets/denden-generation-mask.png`; the user uploads that mask as the only reference.
- Add transparent background, square canvas, complete centered subject, and safe margins to `HARD CONSTRAINTS`. Require the original PNG, not a screenshot or messenger-compressed copy.
- Prefer that the user attach the original PNG. If attachment is impossible, choose a readable existing folder, preferably Downloads, select a new absolute `.png` path, and ask the user to save it there. Never use the skill, source, DenDen configuration, or credential directory.
- If the returned file has a background, remove only that background before confirmation; never ask the user to accept a solid-background version. Validate the transparent file, derive and show its white-background preview with `setup brand preview`, then apply that exact transparent source only after explicit acceptance. On failure, identify the exact defect and provide a targeted revised prompt for the same finalized design, still within the visible-image limit.
- If the user declines an external service, offer the built-in DenDen, an existing transparent PNG, or later setup.

## Regeneration and inspection

- On regeneration, choose equally from the other two families and redraw decoration/effect counts. Do not repeat the previous theme, concrete accessories, or full motion treatment. Change at least two of: main palette, expression, tentacle pose, shell treatment.
- **Hard failure:** nontransparent background; upright/off-axis pose; not exactly two facial eyes; eye-like tentacle tips; wrong tentacle/decoration/effect count; new limbs, long neck, or protrusions; distorted proportions; missing continuous thick outline, vector shading, shell gloss, or colored inner shadow; 3D, preschool sticker, or generic emoji/chibi result.
- **Acceptable variation:** minor hue, curve, nonsemantic surface detail, or non-eye material highlight that does not change the finalized design.
- A request for `N` images permits at most `N` image-service calls in that round. At the limit, disclose any failed candidate and wait for the user to authorize another round.

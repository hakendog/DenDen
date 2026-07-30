---
name: denden-setup
description: Set up and manage DenDen without a maintainer backend, including fixed-source installation, an unbilled Firebase project, isolated Google authorization, one DenDen pairing code, custom appearance, additional sending or receiving devices, pairing rotation, encrypted backups, and the daily denden skill. Use for installation, setup, pairing, re-pairing, adding a sender, or changing DenDen appearance. Do not use for daily notifications.
---

# DenDen setup and management

This skill is self-contained. It does not need a DenDen source checkout or a separately installed management tool. Run every command as `node <this skill directory>/scripts/cli/bin/denden.mjs`. Never run its `setup install` or `setup rollback` commands. The bundled CLI is the deterministic execution layer; do not reimplement FCM, encryption, pairing codes, or appearance chunks.

Before the first command, check for Node.js 22 or newer. Installing Node.js, npm, gcloud, or any other computer-level software requires explaining its purpose and official source and obtaining consent first. Run `capabilities` before deciding that skill dependencies are missing; do not infer this from the absence of `scripts/node_modules`, because Node.js may resolve the locked dependencies from the verified host installation. Only if `capabilities` fails with `ERR_MODULE_NOT_FOUND` for `qrcode` or `pngjs`, check for npm. If npm is already available, automatically run `npm ci --omit=dev --ignore-scripts --no-audit --no-fund` in `scripts` without asking; these are locked, skill-local dependencies. If npm is missing, obtain consent before installing npm, then run the same command. Retry `capabilities` once and continue only for `direct-fcm-v2` with `requiresAutomationToken: false`. Never request an Automation credential.

Except for the `brand` command, protocol fields, and the technical term “brand key,” call the feature “DenDen” or “DenDen appearance” in all user-facing choices and reports.

## Tools and pairing

- Resolve the absolute skill directory first, then use the bundled CLI path above for every command.
- Run `setup status`. Enter first-time setup only when configuration is absent or invalid. Report only the project ID and short fingerprints, never raw configuration or secrets.

## Fixed safety boundaries

- Do not deploy a DenDen backend or install Firebase CLI. Do not enable Firebase Authentication, Firestore, Cloud Functions, Hosting, Storage, Analytics, Secret Manager, Budget, or billing. DenDen uses one dedicated least-privilege service-account key shared only through its CLI-generated sender transfer package.
- Keep management login and the daily sender key in separate isolated directories inside the DenDen configuration directory. Do not search, read, or overwrite other gcloud configurations, ADC, or service-account keys.
- The user may explicitly choose a new or existing unbilled project dedicated to DenDen. Before adopting an existing project, show the account, project ID, intended use, and irreversible effects, and pass `--allow-existing-dedicated` to both `plan` and `direct`. Never choose automatically or adopt an unknown-purpose or billed project.
- Normal first-time setup shows one complete CLI-generated summary covering Firebase, IAM, the shared private key, least-privilege verification, management-login revocation, and the selected daily-skill action. Obtain approval once for that exact summary. Later rotation, revocation, and deletion operations each require their own summary and approval.
- A DenDen pairing code contains a private FCM topic and two keys. Report only the protected PNG path, expiry, and short fingerprints. In an interface that supports local images, display that PNG directly; Codex desktop must use a Markdown image with its absolute local path. Never read or decode the code, convert it to base64 or a data URL, upload it, or print its contents, topic, or keys. If local images are unsupported, show the path and open a local viewer.
- The phone does not sign in to Google and never gives an FCM device token to the agent. FCM acceptance is not delivery proof; the user must confirm the phone screen.
- The user installs, updates, authorizes, and operates the Android app. This skill does not run npm, Gradle, Android, development, or ADB tests.
- Use one user-selected Firebase project. Do not create maintainer OAuth clients or extra projects. Daily sending uses one shared DenDen service account with only `cloudmessaging.messages.create` and `serviceusage.services.use`. Never output or manually copy its private key; move it only inside the CLI-generated sender transfer package.
- If a DenDen installer defect is confirmed, preserve resumable state, report it, and stop. Do not ask an ordinary user to approve source-code changes; use a separate development task.
- Without a server, DenDen cannot guarantee delivery, restore missed history, or revoke only one receiving phone. If pairing secrets leak, rotate the entire pairing and scan every retained phone again.

## Management menu

First classify image-related requests:

- “image,” “character,” “snail,” “icon,” “appearance,” “brand color,” or “background color” means DenDen appearance and uses option 5.
- Only explicit mention of a QR code, pairing code, or new receiving phone/device uses option 3.
- If “regenerate” has no object, ask whether the user means the DenDen image or pairing code.

Run a read-only status check, then offer:

1. First-time installation and pairing.
2. Check or repair management/daily authorization.
3. Add a receiving phone or regenerate the same pairing code.
4. Rotate the entire pairing.
5. Create, update, resume, or restore DenDen appearance.
6. Export/import ordinary sender settings or an encrypted appearance-management backup.
7. Rotate the shared sender authorization.
8. Install the daily `denden` skill.
9. Exit without changes.

Reuse valid existing configuration; do not recreate the project or pairing.

## First-time setup

1. Run `setup doctor`. Explain that Google still handles FCM metadata and that `projects.addFirebase` creates required support APIs, an API key, and Firebase-managed service accounts. Firebase Authentication cannot replace FCM HTTP v1 sender authorization and remains disabled.
2. Run `setup management-auth`, then list accessible projects read-only. Let the user explicitly choose an existing unbilled DenDen-only project or, when quota permits, a new globally unique project ID. Before the summary, ask whether to install the daily skill globally, at a specified location, or skip it. If not skipped, ask for notification preferences.
   Prefer one three-choice question: (1) quiet on every completion and standard for other results (default); (2) quiet only when completion took over one minute and standard for other results; (3) notify only for failure, blocked, reply-needed, and manual events. For custom settings, ask in small groups whether completed, failed, partial, blocked, reply-needed, and manual events are off, quiet, or standard. Ask separately for exact events allowed to ring; default is never, and the agent must not infer permission.
3. For a new project run `setup plan --project-id <id> --skill-choice <global|specified|skip> --skill-agent <codex|claude|gemini>`. Add `--skill-destination <path>` for a specified location and `--allow-existing-dedicated` for an existing project. Notification choices 1–3 map to `--notification-preset <all-completed|balanced|important>`; custom choices use `--notification-<event> <off|quiet|notify|ring>`. Present the management account, project mode, source version and hash, all paths, the shared DenDen service account, two permissions, long-lived private-key risk, login revocation, skill action, notification policy, exclusions, `senderAccountId`, and the single `approvalDigest`.
4. After approval, run `setup direct` with exactly the same options plus `--sender-account-id <id> --approved-digest <digest>`. It performs Firebase setup, pairing, shared sender identity creation, FCM `validate_only`, negative management-API checks, management-login revocation, and the selected skill action. Do not request a second DenDen approval.
5. If Firebase terms block progress, explain there is no separate terms page. Ask the user to open Firebase Console, choose Create project, select “Add Firebase to Google Cloud project” at the bottom, choose the existing project, accept only required terms, continue one step, and stop. Do not enable Analytics or AI assistance or finish the manual setup. Rerun `setup direct` with the same options and digest.
6. If FCM validation fails or any ordinary Google Cloud management probe is not denied, stop and preserve exact resumable state. Do not use human ADC, a maintainer OAuth client, or `cloud-platform`. Any change to account, project, mode, source, path, service account, or skill destination invalidates the digest. On timeout, first confirm no other setup process is running, then rerun with the same digest and options. Never delete checkpoints, blindly repeat non-idempotent writes, create a second private key, or broaden access.
7. Display the one protected pairing-code PNG by absolute local path. Ask the user to install and open the official APK, allow notifications, scan it, and confirm that the app shows subscribed. Mobile data is fine; the phone and computer need not share a network.
8. After pairing, run `setup qr-remove`. Suspected exposure still requires full rotation.
9. After appearance setup or an explicit skip, dry-run a standard notification, then send it. Ring and stop require separate explicit consent. Report only whether FCM accepted it; the user confirms the phone.
10. Offer an optional encrypted offline backup for appearance management. The daily-skill action was already approved in the first summary.

## Custom DenDen appearance

- Ask: “Choose a DenDen appearance: 1. Built-in DenDen; 2. Generate a new DenDen image; 3. Generate one from your requirements; 4. Import an existing transparent PNG; 5. Set it later.”
- For choices 2 or 3, regeneration, or test generation, read `references/denden-generation.md` in full and treat it as the only generation contract.
- Before an external image service, name the service, the single mask asset, and the data scope, then obtain consent. If no image tool is available, follow the reference's manual handoff instead of removing generation choices. If the user declines the external service or mask upload, offer the built-in image, an existing transparent PNG, or later setup.
- Generate each candidate on one flat high-contrast removal matte chosen after the subject palette. The matte color must not occur anywhere in the character, especially either facial eye, outlines, highlights, shadows, accessories, or effects. Remove only that matte before confirmation, then compare the result with the original and reject it if either complete facial eye or any other foreground detail changed.
- Finish and validate the transparent PNG before asking for acceptance. Run `setup brand preview --image <transparent-png> --output <new-white-preview-png>` and show that locally derived white-background preview. Ask only whether to adopt the shown version; never offer to create a transparent final after acceptance.
- Apply only after explicit acceptance, using the exact transparent source path returned by `brand preview`. After acceptance, never call an image service, regenerate, restyle, or remove the background again. A request for `N` visible candidates permits at most `N` candidate-generation calls; a removal-only edit must reuse the same source and preserve the character. Generation does not mean the phone applied it; the app shows a second preview for approval. Default to the app's built-in brand and themed surface colors. Only ask for optional `#RRGGBB` brand or fixed background colors when requested.
- Run `setup brand apply --image <transparent-png> [--brand-color <#RRGGBB>] [--background-color <#RRGGBB>]`. The CLI converts the same source to 512×512 and quantizes when needed for the 64 KiB limit.
- On interruption, partial acceptance, or FCM acceptance without phone application, run `setup brand resume`; reuse the same ciphertext and generation.
- Restore the built-in image with `setup brand reset`. Appearance control uses a separate key, normal priority, and `ttl=0`.
- After all chunks verify, the app stores a candidate until that phone's user accepts it. Every phone is confirmed separately. Daily sender configuration has no brand key.

## New phones, rotation, and sender computers

- Use `setup qr` for a short-lived code for another phone. Display the protected local PNG as above. Existing phones remain paired.
- For exposure or loss, ensure no appearance transfer is pending, run `setup rotate-plan`, obtain approval, then run `setup rotate --approved-digest <digest>`. Scan all retained phones again.
- The sender transfer package is unencrypted and contains the shared Google private key, private topic, and notification key. Treat possession as full sender authority. Show only its local path; never read, print, encode, upload, attach, or move it through chat, email, cloud sync, or another untrusted channel. Use a user-controlled local transfer medium.
- Before export, show the exact output path and unencrypted-secret warning and obtain approval. Run `setup export sender --output <path>` without a password. On the other computer, run `setup import-plan sender --input <path>` and present the project ID, paths, short fingerprints, replacement status, effect, and `approvalDigest`. After approval, run `setup import sender --input <path> --approved-digest <digest>`; add `--replace-existing true` only when the plan requires replacement. Then run `sender-verify`; do not run `management-auth`, `sender-auth`, or copy raw keys or gcloud directories.
- After a successful sender import, ask whether to delete that local transfer-package copy. Delete only after explicit approval and report that ordinary deletion may still leave recoverable storage traces. If either copy is lost or exposed, rotate the shared sender and the entire pairing.
- A single computer cannot be revoked remotely. For a lost or untrusted sender, rerun `management-auth` on a retained computer, obtain approval with `sender-revoke-plan`, run `sender-revoke`, create a new shared sender with approved `sender-auth`, re-export, and re-import using the exact approved import plan on every retained computer. Suspected sender-package, notification, or appearance-secret exposure also requires full pairing rotation.
- Appearance-backup passwords must be entered by the user in an interactive terminal with echo disabled and contain at least 12 characters. If private input is unavailable, stop; never use a command-line argument or environment variable. Sender transfer packages do not use passwords.

## Daily skill installation

First-time setup includes destination and notification preferences in its single summary and installs through `setup direct`. Use `setup skill-plan`/`setup skill-install` only for a later standalone installation. Stop rather than overwrite different existing content. Install only the low-privilege `denden` skill and its bundled sender; do not include Google, IAM, pairing, or appearance authority.

## Completion report

Match the user's language. Report only the source commit, release APK verification, project ID, unbilled status, pairing-code image path, pairing result, appearance result, authorization checks, notification test, and skill location. Never transcribe the pairing code or output private topics, keys, FCM tokens, Google tokens, service-account private keys, passwords, or configuration contents.

---
name: denden-setup
description: Set up and manage DenDen without a maintainer backend, including fixed-source installation, an unbilled Firebase project, isolated Google authorization, one DenDen pairing code, custom appearance, additional sending or receiving devices, pairing rotation, encrypted backups, and the daily denden skill. Use for installation, setup, pairing, re-pairing, adding a sender, or changing DenDen appearance. Do not use for daily notifications.
---

# DenDen setup and management

This skill is self-contained. It does not need a DenDen source checkout or a separately installed management tool. Run every command as `node <this skill directory>/scripts/cli/bin/denden.mjs`. Never run its `setup install` or `setup rollback` commands. The bundled CLI is the deterministic execution layer; do not reimplement FCM, encryption, pairing codes, or appearance chunks.

Before the first command, check for Node.js 22 or newer and npm. If either is missing, explain its purpose and official source, then obtain consent before installing it. If `scripts/node_modules` is missing, explain that dependencies will be installed only from `scripts/package-lock.json`, obtain consent, then run `npm ci --omit=dev --ignore-scripts --no-audit --no-fund` in `scripts`. Run `capabilities` and continue only for `direct-fcm-v2` with `requiresAutomationToken: false`. Never request an Automation credential.

Except for the `brand` command, protocol fields, and the technical term “brand key,” call the feature “DenDen” or “DenDen appearance” in all user-facing choices and reports.

## Tools and pairing

- Resolve the absolute skill directory first, then use the bundled CLI path above for every command.
- Run `setup status`. Enter first-time setup only when configuration is absent or invalid. Report only the project ID and short fingerprints, never raw configuration or secrets.

## Fixed safety boundaries

- Do not deploy a DenDen backend or install Firebase CLI. Do not enable Firebase Authentication, Firestore, Cloud Functions, Hosting, Storage, Analytics, Secret Manager, Budget, or billing. Each sender computer gets one dedicated least-privilege service-account key.
- Keep management login and the daily sender key in separate isolated directories inside the DenDen configuration directory. Do not search, read, or overwrite other gcloud configurations, ADC, or service-account keys.
- The user may explicitly choose a new or existing unbilled project dedicated to DenDen. Before adopting an existing project, show the account, project ID, intended use, and irreversible effects, and pass `--allow-existing-dedicated` to both `plan` and `direct`. Never choose automatically or adopt an unknown-purpose or billed project.
- Normal first-time setup shows one complete CLI-generated summary covering Firebase, IAM, per-computer private key, least-privilege verification, management-login revocation, and the selected daily-skill action. Obtain approval once for that exact summary. Later rotation, revocation, and deletion operations each require their own summary and approval.
- A DenDen pairing code contains a private FCM topic and two keys. Report only the protected PNG path, expiry, and short fingerprints. In an interface that supports local images, display that PNG directly; Codex desktop must use a Markdown image with its absolute local path. Never read or decode the code, convert it to base64 or a data URL, upload it, or print its contents, topic, or keys. If local images are unsupported, show the path and open a local viewer.
- The phone does not sign in to Google and never gives an FCM device token to the agent. FCM acceptance is not delivery proof; the user must confirm the phone screen.
- The user installs, updates, authorizes, and operates the Android app. This skill does not run npm, Gradle, Android, development, or ADB tests.
- Use one user-selected Firebase project. Do not create maintainer OAuth clients or extra projects. Daily sending uses one service account per computer with only `cloudmessaging.messages.create` and `serviceusage.services.use`. Never output, copy, or import its private key to another computer.
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
7. Add or revoke another sender computer.
8. Install the daily `denden` skill.
9. Exit without changes.

Reuse valid existing configuration; do not recreate the project or pairing.

## First-time setup

1. Run `setup doctor`. Explain that Google still handles FCM metadata and that `projects.addFirebase` creates required support APIs, an API key, and Firebase-managed service accounts. Firebase Authentication cannot replace FCM HTTP v1 sender authorization and remains disabled.
2. Run `setup management-auth`, then list accessible projects read-only. Let the user explicitly choose an existing unbilled DenDen-only project or, when quota permits, a new globally unique project ID. Before the summary, ask whether to install the daily skill globally, at a specified location, or skip it. If not skipped, ask for notification preferences.
   Prefer one three-choice question: (1) quiet on every completion and standard for other results (default); (2) quiet only when completion took over one minute and standard for other results; (3) notify only for failure, blocked, reply-needed, and manual events. For custom settings, ask in small groups whether completed, failed, partial, blocked, reply-needed, and manual events are off, quiet, or standard. Ask separately for exact events allowed to ring; default is never, and the agent must not infer permission.
3. For a new project run `setup plan --project-id <id> --skill-choice <global|specified|skip> --skill-agent <codex|claude|gemini>`. Add `--skill-destination <path>` for a specified location and `--allow-existing-dedicated` for an existing project. Notification choices 1–3 map to `--notification-preset <all-completed|balanced|important>`; custom choices use `--notification-<event> <off|quiet|notify|ring>`. Present the management account, project mode, source version and hash, all paths, per-computer service account, two permissions, long-lived private-key risk, login revocation, skill action, notification policy, exclusions, `senderAccountId`, and the single `approvalDigest`.
4. After approval, run `setup direct` with exactly the same options plus `--sender-account-id <id> --approved-digest <digest>`. It performs Firebase setup, pairing, per-computer sender identity, FCM `validate_only`, negative management-API checks, management-login revocation, and the selected skill action. Do not request a second DenDen approval.
5. If Firebase terms block progress, explain there is no separate terms page. Ask the user to open Firebase Console, choose Create project, select “Add Firebase to Google Cloud project” at the bottom, choose the existing project, accept only required terms, continue one step, and stop. Do not enable Analytics or AI assistance or finish the manual setup. Rerun `setup direct` with the same options and digest.
6. If FCM validation fails or any ordinary Google Cloud management probe is not denied, stop and preserve exact resumable state. Do not use human ADC, a maintainer OAuth client, a shared service account, or `cloud-platform`. Any change to account, project, mode, source, path, service account, or skill destination invalidates the digest. On timeout, first confirm no other setup process is running, then rerun with the same digest and options. Never delete checkpoints, blindly repeat non-idempotent writes, create a second private key, or broaden access.
7. Display the one protected pairing-code PNG by absolute local path. Ask the user to install and open the official APK, allow notifications, scan it, and confirm that the app shows subscribed. Mobile data is fine; the phone and computer need not share a network.
8. After pairing, run `setup qr-remove`. Suspected exposure still requires full rotation.
9. After appearance setup or an explicit skip, dry-run a standard notification, then send it. Ring and stop require separate explicit consent. Report only whether FCM accepted it; the user confirms the phone.
10. Offer an optional encrypted offline backup for appearance management. The daily-skill action was already approved in the first summary.

## Custom DenDen appearance

- Ask: “Choose a DenDen appearance: 1. Built-in DenDen; 2. Generate a new DenDen image; 3. Generate one from your requirements; 4. Import an existing transparent PNG; 5. Set it later.”
- For choices 2 or 3, regeneration, or test generation, read `references/denden-generation.md` in full and treat it as the only generation contract.
- Before an external image service, name the service, the single mask asset, and the data scope, then obtain consent. If no image tool is available, follow the reference's manual handoff instead of removing generation choices. If the user declines the external service or mask upload, offer the built-in image, an existing transparent PNG, or later setup.
- Show the final candidate before transfer. Apply only after explicit acceptance. A request for `N` visible candidates permits at most `N` image-service calls in that round. Generation does not mean the phone applied it; the app shows a second preview for approval. Default to the app's built-in brand and themed surface colors. Only ask for optional `#RRGGBB` brand or fixed background colors when requested.
- Run `setup brand apply --image <png> [--brand-color <#RRGGBB>] [--background-color <#RRGGBB>]`. The CLI converts to 512×512 and quantizes when needed for the 64 KiB limit.
- On interruption, partial acceptance, or FCM acceptance without phone application, run `setup brand resume`; reuse the same ciphertext and generation.
- Restore the built-in image with `setup brand reset`. Appearance control uses a separate key, normal priority, and `ttl=0`.
- After all chunks verify, the app stores a candidate until that phone's user accepts it. Every phone is confirmed separately. Daily sender configuration has no brand key.

## New phones, rotation, and sender computers

- Use `setup qr` for a short-lived code for another phone. Display the protected local PNG as above. Existing phones remain paired.
- For exposure or loss, ensure no appearance transfer is pending, run `setup rotate-plan`, obtain approval, then run `setup rotate --approved-digest <digest>`. Scan all retained phones again.
- Another computer imports only a password-encrypted ordinary sender package; it does not contain a private key. On that computer run `management-auth`, `sender-auth-plan`, approved `sender-auth`, `sender-verify`, and approved `management-revoke`. Never copy Google credentials, service-account keys, or gcloud directories.
- To retire a sender, rerun `management-auth`, then `sender-revoke-plan`; after approval run `sender-revoke`, delete that computer's remote service account and local key, then revoke management login. Suspected notification or appearance-secret exposure still requires full pairing rotation.
- Export/import passwords must be entered by the user in an interactive terminal with echo disabled. If private input is unavailable, stop; never use a command-line argument or environment variable.

## Daily skill installation

First-time setup includes destination and notification preferences in its single summary and installs through `setup direct`. Use `setup skill-plan`/`setup skill-install` only for a later standalone installation. Stop rather than overwrite different existing content. Install only the low-privilege `denden` skill and its bundled sender; do not include Google, IAM, pairing, or appearance authority.

## Completion report

Match the user's language. Report only the source commit, release APK verification, project ID, unbilled status, pairing-code image path, pairing result, appearance result, authorization checks, notification test, and skill location. Never transcribe the pairing code or output private topics, keys, FCM tokens, Google tokens, service-account private keys, passwords, or configuration contents.

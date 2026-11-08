# AGENTS.md — TFitApp

Guidance for AI coding agents working in this repository. The user-level
`~/.claude/CLAUDE.md`, `ENGINEERING.md`, and `ENGINEERING_PERSONA.md` still
apply; this file adds what is specific to TFitApp. Where they conflict, this
file wins.

## What this is

A personal, customised fitness app for Android. Workout data lives **on the
device** (Room / SQLite). There is no backend of our own and no sign-in,
except one recorded exception: bodyspec and weekly-goal fields sync to the
user's own Supabase project so Claude can read/update them over MCP (see
"Working with the profile MCP server" below and design basis §2). Nothing
else about "no backend, no sign-in" has changed.
Features, goal model, and the reasoning behind each decision are in
[`docs/DESIGN_BASIS.md`](docs/DESIGN_BASIS.md) — read it before starting a
feature.

The one thing that leaves the device is a call to **Jev**, TypeSafe AI's
"System One" model (https://docs.typesafe.ai/). Jev is not a chat model. You
send it *state* (JSON) plus typed *questions*, and it returns structured,
calibrated answers — a probability, a chosen option, or a score on a rubric —
that code can act on directly. We use it for judgement calls that would
otherwise be fragile hand-written heuristics (e.g. "does this free-text note
describe an injury?", "which of these templates does today's session most
resemble?"), never for control flow.

## Stack

| Layer | Choice | Notes |
|---|---|---|
| Language | Kotlin | JVM target 17. No Java sources. |
| UI | Jetpack Compose, Material 3 | Single-activity, Navigation Compose. |
| Build | Gradle Kotlin DSL + version catalog (`gradle/libs.versions.toml`) | Every dependency version lives in the catalog. Never inline a version in a `build.gradle.kts`. |
| DI | Hilt | |
| Persistence | Room, `kotlinx.coroutines` Flow | Schema exported to `schemas/`; every schema change ships a `Migration` and a migration test. |
| Concurrency | Kotlin coroutines + `StateFlow` | No RxJava, no LiveData. |
| Serialization | `kotlinx.serialization` | Used for Jev payloads and Room type converters. |
| HTTP | Ktor client (OkHttp engine) | Only in `:infrastructure:typesafe`. |
| Jev | REST, `POST https://api.typesafe.ai/v1/systemone`, model `jev-latest` | No Kotlin SDK exists; we own the client. Talk to TypeSafe directly — do not copy Vercel AI Gateway examples (`ai-gateway.vercel.sh`, `ai-gateway-*` headers); that is a different endpoint. |
| Food data | Bundled USDA FDC (Foundation + SR Legacy) in Room; FDC API `api.nal.usda.gov/fdc/v1` on local miss | Only in `:infrastructure:fdc`. |
| Weather | NWS `api.weather.gov` | Only in `:infrastructure:nws`. Needs a `User-Agent`. |
| Profile sync | REST/GoTrue, the user's own Supabase project (`profile_snapshot` table, `current_profile` view) | Only in `:infrastructure:supabase`. Design basis §2 exception — see "Working with the profile MCP server". |
| Profile MCP server | Supabase Edge Function (Deno/TypeScript), `supabase/functions/mcp` | Not part of the Gradle build. Deployed via the connected `mcp__supabase__deploy_edge_function` tool or `supabase functions deploy mcp`. |
| minSdk / targetSdk | 26 / latest stable | *Assumption — confirm before first release.* |
| Package | `com.tfitapp` | *Assumption — confirm before first commit.* |

## Module layout and dependency rule

Dependencies flow inward. Each outward-facing technology is quarantined in
exactly one module. `:application` owns the port interfaces; outer modules
implement them.

```
:app                      Android host. Compose UI, ViewModels, Hilt wiring, navigation.
:application              Use cases, port interfaces (repositories, JudgementPort), DTOs.
:domain                   Entities, value objects, invariants. Pure Kotlin. No Android, no coroutines-android.
:infrastructure:room      Room database, DAOs, entities, migrations. Implements repository ports.
:infrastructure:typesafe  Jev HTTP client. Implements JudgementPort. The only module that knows the Jev URL or JSON shape.
:infrastructure:fdc       USDA FoodData Central API client. Implements FoodLookupPort.
:infrastructure:nws       National Weather Service client. Implements ForecastPort.
:infrastructure:supabase  Supabase profile-sync client. Implements ProfileSyncPort. The only
                          Kotlin module that knows the Supabase URL, PostgREST/GoTrue shapes,
                          or the profile_snapshot columns (design basis §2 exception).
```

`supabase/functions/mcp` sits beside this module graph, not inside it — it is
a Deno/TypeScript Edge Function, not a Gradle module. It is the only code
that knows the same shapes from Claude's side of the connection.

Each `:infrastructure:*` module has its own Ktor `HttpClient` instance
configured for that one API (base URL, headers, timeout). They share nothing
but the `:application` ports they implement.

- `:domain` and `:application` are plain `kotlin("jvm")` modules and must stay
  that way — they are tested with plain JUnit, no emulator.
- `:app` may depend on everything. Nothing depends on `:app`.
- `:infrastructure:*` modules depend on `:application` and `:domain` only.
  They never depend on each other.
- If you need a new external SDK, it gets its own `:infrastructure:<name>`
  module. Do not add it to an existing one.

## Working with Jev

Read https://docs.typesafe.ai/llms.txt before touching anything in
`:infrastructure:typesafe` or `application/.../judgement/`. The Claude Code
plugin is worth installing once per machine:

```
claude plugin marketplace add typesafe-ai/skills
claude plugin install typesafe@typesafe-ai
```

### The contract

Request: `state` (string | object | array), `model` (`"jev-latest"`),
`questions` (map of id → question). Question `type` is one of:

| Type | Ask when | Returns |
|---|---|---|
| `noul` | a yes/no judgement | `noul: 0..1` — probability the statement is true |
| `choice` | pick one of N named options | `choice`, `probabilities{}`, `confidence: 0..1` |
| `score` | rate against an ordered rubric (≥ 2 levels) | `score`, `legend{}`, `probabilities{}`, `confidence: 0..1` |

Response: `model`, `answers` (keyed by our question ids), `usage`.
Errors: `401` bad key, `422` malformed request, `429` rate limit, `529`
overloaded. Retry `429`/`529` with exponential backoff; never retry `422` —
that is a bug in our request.

### Rules

1. **All questions and thresholds live in one file:**
   `application/src/main/kotlin/com/tfitapp/application/judgement/JevQuestions.kt`.
   Reviewers read that file first. A question defined anywhere else is a
   defect.
2. **Decompose.** One property per question. "Is this note concerning?" is
   too broad; ask `mentions_pain`, `mentions_injury`, `requests_rest_day`
   separately and combine in code.
3. **Batch.** Independent questions about the same state go in one request.
   A second round-trip is a design smell.
4. **State is minimal and structured.** Send a JSON object with only the
   fields the questions need. Refer to fields in instructions by path
   (`session.notes`). Never dump the whole database.
5. **Code owns control flow.** Jev answers; code decides. No loops where the
   model picks the next step. Deterministic rules (rest-day scheduling, PR
   detection, volume math) never go near Jev.
6. **Use `confidence` to route, not to decorate.** Low confidence → fall back
   to asking the user, never to a silent default. If you only need the best
   option, take the argmax and skip the threshold.
7. **Thresholds are measured, not guessed.** A new threshold constant needs a
   comment naming the fixture set it was tuned against.
8. **Jev is optional at runtime.** Every feature that uses it must degrade to
   a non-AI path (manual pick, plain list) when the key is absent, the device
   is offline, or the call fails. The app must be fully usable without Jev.
9. **Idempotent by construction.** Jev calls are pure reads. Persisting an
   answer is keyed on the `(entityId, questionSetVersion)` pair and upserted.

### API keys — recorded trade-off

Jev and the FDC API are cloud-only, there is no backend of ours, so the keys
must be on the phone. Decision:

- The user pastes their own keys — Jev from https://console.typesafe.ai/keys,
  FDC from https://fdc.nal.usda.gov/api-key-signup — into **Settings → API
  keys**. They are stored via `EncryptedSharedPreferences` backed by the
  Android Keystore, behind a single `SecretStore` port.
- No key is **ever** in source, `local.properties`, `BuildConfig`, a
  resource, a log line, or a crash report. A grep for `TYPESAFE_API_KEY` or
  `FDC_API_KEY` in the repo should return only this file and the live-test
  tag below.
- This is acceptable because the app is single-user and the key is the user's
  own. If the app is ever distributed, this decision must be revisited (a
  proxy that holds the key becomes mandatory). Track that as a follow-up
  before any Play Store listing.

## Working with the profile MCP server

Design basis §2's one recorded sync exception: `BodyProfile`, `GoalSettings`,
`trainingDays`, and `timeBudgetMinutes` mirror to the user's own Supabase
project so Claude can read and update them conversationally. Nothing else
syncs. Read this section before touching `:infrastructure:supabase`,
`application/.../profile/`, or `supabase/functions/mcp`.

### Shape

- **Table:** `public.profile_snapshot` — insert-only history, one row per
  change, never updated or deleted (matches "never delete history" below).
  `public.current_profile` is a `security_invoker` view giving the latest row
  per user.
- **Kotlin side** (`:application/profile`, `:infrastructure:supabase`):
  `ProfileSyncPort.push`/`pull`, used by `SyncProfileToCloud` (push-on-save,
  skips an unchanged profile so retries don't spam history) and
  `PullProfileFromCloud` (periodic catch-up via `ProfileSyncWorker`,
  last-writer-wins by `effectiveAt`). Supabase is authoritative for these
  fields when reachable; the phone falls back to its last-synced local copy
  otherwise — this must never block the Settings screen.
- **Claude side** (`supabase/functions/mcp/index.ts`): an Edge Function
  running the official MCP TypeScript SDK (`@modelcontextprotocol/server`)
  behind Supabase Auth's own OAuth 2.1 authorization server
  (`withOAuthProtectedResource` + `withSupabase({ auth: 'user' })`). Every
  tool call runs RLS-scoped as the signed-in user — there is **no
  service_role key** in this function. Tools: `get_profile` (read-only),
  `set_weekly_goal`, `update_bodyspec`.
- **Numeric bounds are deliberately duplicated three times** — the Kotlin
  domain invariants (`BodyProfile.init`, `GoalSettings`), the SQL `check`
  constraints on `profile_snapshot`, and the Zod schemas in the Edge
  Function — so a bad value from an LLM tool call can't reach the table even
  if one layer's validation has a bug. Change all three together.
- **Phase changes reset to that phase's defaults** unless the caller
  overrides them, in both the app (`SettingsViewModel.setPhase`) and the
  Edge Function (`PHASE_DEFAULTS` in `index.ts`) — keep these two in sync
  with `GoalPhase`'s defaults in `domain/nutrition/Goal.kt`.

### Rules

1. **Read-write, not read-only.** Claude can set goals and update bodyspec,
   not just read them — a deliberate choice, not the safer default. Don't
   quietly narrow this to read-only or widen it to more fields without
   asking.
2. **Scope stays narrow.** Equipment, home coordinate, lines course, meal
   hours, and excluded exercises stay local-only. Adding another field to
   the sync needs a matching migration + Edge Function + Kotlin change, not
   a quiet expansion of `SyncedProfile`.
3. **Auth is OAuth via Supabase Auth, not a shared secret.** The project's
   `[auth.oauth_server]` config in `supabase/config.toml` and the function's
   `verify_jwt = false` (custom auth in code instead) are both required for
   this to work. Don't add a `service_role` key or a bearer-secret check to
   this function — that was considered and deliberately rejected in favour
   of RLS-scoped per-user access.
4. **Deploying `supabase/functions/mcp`** is via the connected
   `mcp__supabase__deploy_edge_function` tool (or `supabase functions deploy
   mcp` from the CLI once linked). Schema changes go through
   `mcp__supabase__apply_migration` or a committed migration file, never a
   hand-run dashboard edit. Re-run `mcp__supabase__generate_typescript_types`
   and update `supabase/functions/mcp/database.types.ts` after any schema
   change — don't hand-edit that file.

## Data and domain rules

- **Every write is idempotent.** Sets, sessions, and body-weight entries carry
  a client-generated `UUID` primary key created at the moment the user starts
  the action, so a retried save is a no-op. Use `@Upsert` DAOs; unique indexes
  on natural keys where one exists (`(exerciseId, sessionId, ordinal)`).
- **Timestamps are `Instant` (UTC) in storage**, converted to the device zone
  only in the UI layer. A workout's "day" is computed from the zone at the
  time it was logged, stored alongside it.
- **Units are stored once.** Weight in kilograms as `Double`, distance in
  metres, duration in seconds. Imperial is a display concern.
- **Never delete history.** Sets and sessions get `deletedAt`; queries filter
  it. Exercises are archived, not removed.
- **Room migrations are forward-only, tested, and re-runnable.** Guard DDL
  with `IF NOT EXISTS`. Export schema JSON and commit it.

## Testing — definition of done

| Layer | Framework | Where |
|---|---|---|
| `:domain`, `:application` | JUnit 5 + kotlin.test, no Android | `src/test` |
| `:infrastructure:room` | Room in-memory DB + `MigrationTestHelper` | `src/androidTest` |
| `:infrastructure:typesafe`, `:fdc`, `:nws` | Ktor `MockEngine`; recorded JSON fixtures in `src/test/resources/<api>/` | `src/test` |
| Compose UI | `compose-ui-test` | `:app/src/androidTest` |
| End-to-end | Compose test rule driving real navigation with a fake `JudgementPort` | `:app/src/androidTest/e2e/` |

- A feature is not done without an E2E test of its user-facing flow and an
  integration test at every seam it crosses (Room, Jev).
- Every idempotent operation has a test that performs it twice and asserts
  the second call changed nothing.
- Jev fixtures are the real response shape captured once from the live API
  with the key redacted. When the shape changes, re-record; do not hand-edit.
- Nothing in `src/test` touches the network. Tests that need a live API are
  tagged `@Tag("live")`, excluded by default, and read keys from the
  `TYPESAFE_API_KEY` / `FDC_API_KEY` environment variables only.
- The Jev client parses responses against a strict `kotlinx.serialization`
  schema. A missing or malformed answer is surfaced per question id as
  "no answer", never as an exception that discards the other answers — the
  caller decides what a gap means (see design basis §4.2).
- Every outbound call has an explicit timeout set at the call site by the use
  case (Jev 4 s for food entry, FDC 3 s, NWS 10 s). No client-wide default
  that a caller can forget it is relying on.

Run before saying "tests pass":

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
.\gradlew.bat test                         # JVM tests, all modules
.\gradlew.bat connectedDebugAndroidTest "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true"
                                           # needs a running emulator; the flag keeps the app installed afterwards,
                                           # so its data (the pasted API keys included) survives the run
.\gradlew.bat lint                         # detekt is not configured yet — open item
```

Emulator on this machine: AVD `tfit` (API 36, x86_64, WHPX). Boot headless with
`emulator -avd tfit -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect`
and wait for `adb shell getprop sys.boot_completed` to print `1` (~90 s). Pass
`sdkmanager` package names through PowerShell with `--%`; bash splits them on `;`.

"Tests pass" means those commands ran, here, now, on this code.

## Commands

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath
.\gradlew.bat detekt
adb logcat -s TFitApp:V
```

Windows host. Use `.\gradlew.bat`, not `./gradlew`. Paths use backslashes.
The Bash tool is POSIX and wants `/c/projects/TFitApp`; PowerShell wants
`C:\projects\TFitApp`. Don't mix the two in one command.

## Conventions

- One class per file, file named for the class. One reason to change per file.
- `internal` by default; `public` only for what another module actually calls.
- ViewModels expose a single `StateFlow<UiState>` and take a single
  `onEvent(UiEvent)`. No business logic in Composables.
- Use cases are `class VerbNoun @Inject constructor(...) { suspend operator fun invoke(...) }`.
- No `!!`. No `runBlocking` outside tests. No `GlobalScope`.
- Logging through a `Logger` port; the `:app` implementation is Timber. Never
  log request bodies sent to Jev — they contain the user's notes.
- Follow the official Kotlin style guide; `detekt` config is the source of
  truth. Match the surrounding code's comment density.

### UI

- Tokens live in `app/…/ui/theme/Theme.kt` (`TFit.colors`, Barlow type scale) and
  shared pieces in `Components.kt` (`ScreenList`, `Heading`, `Rule`, `Field`, `Primary`,
  `Secondary`, `Quiet`, `Toggle`, `Notice`). Use them; do not reach for raw Material
  components or `OutlinedTextField`.
- Words in Barlow, numbers in Barlow Condensed (`displayLarge`…`headlineLarge`,
  `numberStyle(...)`). Width is the label — do not add a caption above a number.
- One orange (`signal`) action per screen. The timer phase field is the other place
  orange appears. `shade` is rest, `amber` is a degraded-path notice, `moss` is "done/ok".
- Lists are rows separated by `Rule()`, not cards. Number a list only when it is a
  sequence (session blocks are; foods are not).
- Facts sit side by side or in a sentence; never join them with middle dots.
  Domain titles are plain names (`SessionCandidate.title`, `SessionBlock.title`);
  the prescription carries the numbers, the screen formats them.
- Copy: sentence case, plain verbs, the button names the outcome ("Log 3 items,
  640 kcal"). Empty and degraded states say what to do next.

## Things agents should not do without asking

- Add a backend, a sign-in, or any analytics/telemetry SDK beyond the one
  approved exception (Supabase profile sync — "Working with the profile MCP
  server" above). That exception doesn't open the door to another.
- Add a module or an external dependency outside the version catalog.
- Change the `JudgementPort` or `ProfileSyncPort` interfaces — they are seams
  every test relies on.
- Widen a Jev timeout or retry count to make a flaky test pass. Find the cause.
- Put the API key anywhere other than the Keystore-backed store described above.
- Widen what syncs to Supabase beyond `BodyProfile`/`GoalSettings`/
  `trainingDays`/`timeBudgetMinutes`, or add a `service_role` key /
  shared-secret auth to `supabase/functions/mcp` in place of the OAuth setup.
- Refactor across modules when the task was scoped to one.

## Open decisions (fill in, then delete from this list)

- [ ] Confirm `minSdk` (26 assumed) and `applicationId` (`com.tfitapp` assumed).
- [ ] Decide first Jev use cases; write the initial `JevQuestions.kt` together — questions need human editing, not just agent drafts.
- [ ] Health Connect integration: in or out of scope for v1?
- [ ] Backup/export format (JSON export to user-chosen `Uri` is the default proposal).

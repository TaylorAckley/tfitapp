# TFitApp — Design Basis

**Status:** Draft 1, 2026-09-19. Personal project, single user, not distributed.

This document fixes the assumptions, constraints, and decisions that detailed
design and code derive from. It says *what* the app is and *why* each part is
shaped the way it is. Module layout, coding rules, and testing standards live
in [`AGENTS.md`](../AGENTS.md); this document does not repeat them.

---

## 1. Purpose and scope

One user, one phone. Three features:

1. **Calorie tracker** — log what you ate with the least possible effort;
   know whether the week is on target.
2. **Workout generator** — propose today's session from the equipment on
   hand, the weather outside, and how you feel.
3. **Interval timer** — run the conditioning piece of that session (or any
   preset) reliably with the screen off.

Everything else is out of scope (§10).

### What "personal, not public" buys us

These consequences are deliberate and should not be "fixed":

- No accounts, no sign-in, no multi-user data model.
- No analytics, crash reporting, or telemetry SDK. Bugs are found by the user.
- No Play Store policy work. Foreground-service types, permission
  justifications, and data-safety forms are chosen for correctness, not
  review.
- API keys are the user's own and live on the phone (see `AGENTS.md`,
  "API key — recorded trade-off").
- Nutrition advice, medical disclaimers, and safe-training guardrails are
  the user's responsibility. The app does arithmetic and suggests; it does
  not coach.

## 2. Design principles

**Laziness is a requirement, not a character flaw.** Every daily interaction
must be completable in one screen and under ten seconds. Concretely:

| Principle | What it means in the UI |
|---|---|
| Propose, don't ask | The app fills every field with its best guess. The user corrects, never composes. |
| One text box beats a form | Food is one sentence. Check-in is one sentence. Settings are the only forms. |
| Weekly beats daily | Calorie compliance is judged on the 7-day average. A bad Tuesday is information, not failure. No streaks, no red badges. |
| Degrade, never block | Jev unreachable → manual picker. NWS unreachable → indoor session. Nothing waits on the network. |
| Silence is data | An unlogged meal slot after 21:00 prompts once with three one-tap answers. Ignoring the prompt is allowed. |
| Remember everything | Every logged food becomes a favourite. Every completed session informs the next one. The user never re-enters something the app has seen. |

**Code decides, Jev judges.** Deterministic logic — arithmetic, scheduling,
progression, thresholds — is code. Jev is called only where the input is
unstructured (a sentence) and the output is a pick, a score, or a
probability. Jev never generates text, never chooses the next step, and is
never on the critical path (§8).

**On-device is the default.** Data lives in Room. Two things leave the phone:
food/check-in sentences to Jev, and a rounded home coordinate to NWS. Nothing
else leaves as a matter of course, and nothing is synced — except the one
recorded exception below.

**Exception, recorded 2026-09-20: bodyspec and weekly-goal sync for Claude.**
`BodyProfile` (sex/age/height/weight/activity factor), `GoalSettings`
(phase/budget offset/protein target), `trainingDays`, and
`timeBudgetMinutes` — and only those fields — are mirrored to a Supabase
project the user owns, so Claude can read and update them through the MCP
server at `supabase/functions/mcp`. Supabase is authoritative for this data
when reachable; the phone falls back to its last-synced local copy when it
is not, the same degrade-gracefully posture as Jev/FDC/NWS (§7.1). Every
other decision in this section — no accounts, no cloud sync, no backend of
our own — still stands for everything else: food log, sessions, timer,
equipment, home coordinate, lines course. See `AGENTS.md` for the module and
auth details. This is a deliberate, scoped trade-off, not a reversal of the
principle above.

## 3. Personal parameters

Values the app needs about *this* user. **All of them are editable in
Settings** and stored in Room; nothing here is compiled in. The table gives
the current value where known and the default otherwise. Every parameter is
included in export/import.

| Parameter | Used by | Current value / default |
|---|---|---|
| Sex, age, height | TDEE estimate | *to fill in* |
| Current weight | TDEE, protein floor | Updated by weigh-ins |
| Activity factor | TDEE estimate | 1.4 (light) until adaptive TDEE takes over |
| Goal phase | Calorie budget, protein floor | `Recomp` |
| Equipment inventory | Generator | See §5.2 — filled in |
| Training locations | Generator | Outdoor (primary), indoor (fallback) |
| Lines course | Generator, timer | Point distances, see §5.2 — *to measure* |
| Home coordinate | NWS forecast | Lat/long, 2-decimal precision — *to fill in* |
| Training days & time budget | Generator | e.g. Mon/Wed/Fri/Sat, 45 min — *to fill in* |
| Injuries / no-go movements | Generator filter | Free text + exercise exclusions |
| Liked / disliked movements | Generator weighting | Seeded from §5.2; adjusted from *Swap* history |
| Meal slots & typical times | Reminder prompt | Breakfast 08:00, lunch 12:30, dinner 19:00 |
| Units | Display only | lb for equipment, °F for weather; kg stored |

## 4. Feature 1 — Calorie tracker

### 4.1 Goal model

Three switchable phases. Switching phase is one tap and takes effect the next
day; history keeps the phase that was active when it was logged.

| Phase | Daily budget | Protein floor | Intent |
|---|---|---|---|
| Cut | TDEE − 500 kcal | 2.0 g/kg | ≈ 0.45 kg/week loss |
| Recomp | TDEE | 1.8 g/kg | Hold weight, shift composition |
| Lean gain | TDEE + 250 kcal | 1.8 g/kg | ≈ 0.25 kg/week gain |

Offsets and g/kg are defaults, editable. Protein is the only macro tracked by
default; carbs/fat are stored but hidden unless the user turns them on.

**TDEE** starts from Mifflin-St Jeor × activity factor and is replaced by an
**adaptive estimate** once there are 14 days of both intake and weight data:

```
TDEE_adaptive = mean(daily intake, last 21 days)
              − (Δ smoothed weight over window, kg) × 7700 / days in window
```

Weight is smoothed with an exponential moving average (α = 0.1) over daily
weigh-ins to filter water noise. The adaptive figure is re-computed weekly
and shown with its window so the user can see why the budget moved. All of
this is plain code in `:domain`.

### 4.2 Entry: one sentence

The primary screen is a text field with the placeholder *"What did you eat?"*.
The user types `2 eggs, toast with butter, black coffee` and taps once.

Pipeline (one Jev round-trip for the whole sentence):

1. **Split** — code. Fragments on `,`, ` and `, ` with `, ` + `. Quantities
   and units are pulled from each fragment by regex (`2`, `1/2 cup`, `300g`).
2. **Candidates** — code. Each fragment runs against the user's own foods
   and favourites first, then the bundled food database via FTS (§4.3). Top
   5 per fragment. A fragment with **no local hit** is searched against the
   USDA FoodData Central API (§4.3) — one request per missed fragment, in
   parallel, with a 3 s budget; a miss there too yields a *quick add* row
   with the fragment as its label.
3. **Judge** — one Jev call. State is the original sentence plus, per
   fragment, its candidates and each candidate's standard portions.
   Questions, per fragment:
   - `f{i}_match` (`choice`): which candidate, or `none_of_these`.
   - `f{i}_portion` (`choice`): which standard portion descriptor, when the
     regex found no explicit quantity.
   - `f{i}_is_food` (`noul`): the fragment actually describes food (filters
     "then went for a walk").
4. **Route on confidence** — code. Confidence ≥ threshold → prefilled row.
   Below threshold or `none_of_these` → that row opens a picker with the
   candidates; every other row stays prefilled. Thresholds live in
   `JevQuestions.kt` and are tuned against a fixture set of the user's own
   sentences.

   The Jev response is parsed against a strict schema. A missing or
   malformed answer for a fragment is treated as *low confidence for that
   row only* — the other rows keep their answers. This is a deliberate
   departure from the all-or-nothing pattern in server-side Jev clients:
   there, a partial result means a wrong grade; here, it means one extra
   tap.

   **Latency budget:** 4 s for the Jev call. Past that, the screen renders
   as if Jev were absent and the answer is discarded when it arrives. A
   food log is a thing you do standing in the kitchen, not a thing you wait
   for.
5. **Confirm** — one tap logs all rows. Each row remembers its Jev
   confidence and whether the user overrode it, so threshold tuning has data.

If Jev is unreachable, step 3 is skipped and every row opens with the
top-ranked FTS candidate preselected. The screen looks the same; it just
guesses worse.

### 4.3 Food data

- **Bundled:** USDA FoodData Central *Foundation* + *SR Legacy* datasets
  (public domain, ~8 000 foods with standard portions), shipped as a
  prepopulated Room database with an FTS4 table. Read-only; replaced
  wholesale when USDA publishes a new release.
- **User foods:** anything not in the bundle. Created from a sentence
  (`"protein bar 220 kcal 20g protein"` — quantities parsed in code) or by
  editing a bundled item. Recipes are user foods with ingredients.
- **Favourites and recents:** derived from the log, not maintained by hand.
- **On-demand: USDA FoodData Central API** (`api.nal.usda.gov/fdc/v1`,
  user's own api.data.gov key, stored alongside the Jev key). Used only when
  the local search misses, which covers the *Branded* dataset (~400 k
  packaged products) without bundling it. Any FDC result the user logs is
  copied into the local database as a user food, so the same product is
  found offline next time. The bundle is therefore a seed, and the local
  database grows toward exactly the foods this one person eats.
- **Not bundled and not fetched:** barcode lookup (open decision, §11). The
  FDC API does support GTIN/UPC search, so if barcode scanning is ever
  added it needs no new data source — only ML Kit for the scan.

### 4.4 Lazy paths

Each is one tap from the home screen:

- **Same as yesterday** — copies a meal slot or the whole day.
- **Quick add** — kcal (and optional protein) with a label. For takeaway and
  guesses. Marked `source = quick` so weekly stats can show how much of the
  week was estimated.
- **Usual** — a *saved meal*: the user names today's breakfast (or any slot)
  once, e.g. "weekday breakfast", and from then on it is one tap, or one word
  in the sentence. `f{i}_meal` (choice, §8) lets Jev recognise a nickname
  ("the usual") against the saved names; an exact name never asks. Other
  fragments edit the expansion in code: a food the meal already holds replaces
  that item ("but 3 eggs"), a negated fragment removes it ("without toast",
  "no butter"). Items are frozen at save time; an estimate stays an estimate.
  The name is the natural key, so re-saving replaces rather than duplicates.
- **Evening prompt** — if a meal slot has nothing logged 90 minutes after
  its typical time, one notification: *Usual · Quick add · Skip*.

### 4.5 What the user sees

Home shows three numbers and nothing else above the fold: **kcal left
today**, **protein so far / floor**, **7-day average vs budget**. Colour is
neutral. The **weekly review** lives one tap away ("This week"): a fixed
set of plain sentences first — days logged, intake against budget, the weight
trend against the rate the phase expects, protein floor days, sessions
finished — then the charts they were read from (seven intake bars with the
budget line; raw weigh-ins as dots with the smoothed series). The trend rate
is a least-squares slope through the raw weigh-ins so a steady loss is not
understated by EMA lag. The verdict is arithmetic and thresholds in `:domain`
(`WeeklyReview`); it suggests a ±150 kcal nudge only after the trend has
disagreed with the plan for a week, and never applies it.

### 4.6 Data model (sketch)

```
BundledFood(fdcId, description, kcalPer100g, proteinPer100g, ..., fts)
BundledPortion(fdcId, label, grams)
UserFood(id UUID, name, kcalPer100g, proteinPer100g, isRecipe, archivedAt)
FoodLogEntry(id UUID, loggedAt Instant, localDate, mealSlot,
             foodRef (bundled|user), grams, kcal, protein,
             source (jev|manual|quick|copy), jevConfidence?, userOverrode)
WeightEntry(id UUID, at Instant, kg)
GoalPhase(effectiveFrom LocalDate, phase, budgetOffset, proteinPerKg)
```

`FoodLogEntry.id` is generated when the user starts typing; retrying a save
is a no-op.

## 5. Feature 2 — Workout generator

### 5.1 Inputs

| Input | Source | Freshness |
|---|---|---|
| Equipment inventory | Settings | Static |
| Location availability | Settings | Static |
| Weather for the training window | NWS hourly forecast (§5.4) | Fetched ≤ 1 h before; cached |
| Time budget today | Settings default, editable per day | Per session |
| Recent sessions | Room | Live |
| How I feel | One free-text check-in, optional | Per session |
| Program state | Room | Live |

### 5.2 Equipment, movements, and the lines course

**Equipment** is a list in Settings, not free text, because the generator
filters on it. Items have a kind, an optional detail (load, attachment), and
a *present* flag so something can be parked without deleting its history.
Unknown gear is added as a custom item with the same fields. Current
inventory:

| Item | Detail | Movements it unlocks |
|---|---|---|
| Torque Tank sled | Tow rope, wheelbarrow handles; resistance settings recorded per session | Push, reverse drag, rope pull (hand-over-hand), wheelbarrow carry/push |
| Kettlebell | 45 lb | Swings, goblet squat, clean, press, snatch, carries, Turkish get-up |
| Kettlebell | 10 lb | Halos, presses for warm-up/high-rep, single-arm carries, light snatch technique |
| Battle rope | — | Waves, slams, alternating waves, circles |
| Weighted jump rope | — | Timed skipping, double-under practice |
| Body | Always present | Star jumps, burpees, push-ups (and variations), mountain climbers, squats, lunges, planks, bear crawls |

The 45 lb / 10 lb gap matters: there is no intermediate load, so the
generator treats each kettlebell movement as **fixed-load** and progresses
by reps, density, and variation (§5.3), not by adding weight. If a
kettlebell between the two is ever added, the progression rule picks up the
new load automatically.

**Liked movements** — star jumps, burpees, push-ups, mountain climbers, and
lines — are seeded with a preference weight so they appear often. Weights
move over time: a movement the user *Swaps* out three times in a row is
down-weighted; one the user keeps is up-weighted. No Jev involved.

**Lines** are a first-class conditioning modality, not an exercise. The user
defines a **course**: an origin and an ordered set of points at increasing
distance (P1 nearest, Pn farthest). A lines set is *run to P1 and back, run
to P2 and back, …, run to Pn and back*. The generator varies the number of
points used, the number of rounds, the rest between rounds, and optionally a
movement at the origin between legs (e.g. 5 burpees). Course distances are
entered in Settings once (paced or measured); the app never needs GPS for
this.

Every exercise in the library declares the equipment it needs, the locations
it works in, and its **weather sensitivity** (sled and lines are outdoor-only;
kettlebell and bodyweight work anywhere; battle rope needs space). The filter
is a set intersection — code.

### 5.3 Generation is a rules engine; Jev ranks

The session is *composed by code* from a tagged exercise library and a
program structure. Jev is asked two kinds of question, both after
composition:

**Program structure.** A weekly template with slots. With this equipment the
split is not "lifting vs cardio" but *kettlebell strength*, *sled*, *lines*,
and *bodyweight circuits*, mixed within a session. A first template to start
from (editable, seeded in code):

| Slot | Shape (≈45 min) |
|---|---|
| **KB strength + circuit** | Warm-up (10 lb halos, mountain climbers) · 45 lb swings / goblet squats / presses in sets · 3-round bodyweight circuit (star jumps, push-ups, burpees) |
| **Sled day** | Sled push and rope-pull intervals · wheelbarrow carries · finisher: battle rope |
| **Lines day** | Lines rounds with an origin movement between legs · jump rope cool-down |
| **Mixed conditioning** | Short circuit alternating sled, KB swings, battle rope, jump rope |

Each slot is a list of *blocks*; a block names a movement pattern or
modality (hinge, squat, press, carry, sled-push, lines, circuit, …) and a
prescription shape (sets × reps, rounds × work/rest, points × rounds).

**Composition, per slot:**

1. Filter the library by equipment ∩ location ∩ weather feasibility ∩
   not-excluded, then weight by preference (§5.2).
2. For each block, pick the movement with the oldest last-used date among
   the highest-weighted candidates (rotation), preferring the one with
   progression history.
3. Prescribe by the **progression rule that fits the movement's load
   model**:
   - *Variable load* (a kettlebell range, if one ever exists): double
     progression — top of rep range on all sets → next load up; miss the
     bottom → hold.
   - *Fixed load* (45 lb / 10 lb kettlebell, bodyweight): progress **reps**
     to the top of the range, then **density** (same work, shorter rest),
     then **variation** (push-up → decline push-up; swing → single-arm
     swing; goblet squat → clean-and-squat). Regress one step after two
     consecutive misses.
   - *Sled*: progress resistance setting first, then distance or time per
     interval, then reduce rest. Resistance is logged per set because the
     Tank's setting is the load.
   - *Lines*: progress points used (P1–P3 → P1–P4), then rounds, then rest;
     hold total distance when adding an origin movement.
   - *Timed conditioning* (rope, jump rope): progress work:rest ratio.
4. Conditioning blocks emit an **interval plan** or a **lines plan** (§6) —
   this is the handoff to the timer.
5. Produce **three candidate sessions** that differ in one axis: shorter
   (time budget), indoor alternative (weather), lighter (fatigue).

**Jev, one call:**

- State: the three candidates (structured), today's forecast summary, time
  budget, the check-in sentence, last 7 days of session summaries.
- `checkin_upper_limited`, `checkin_lower_limited`, `checkin_low_energy`,
  `checkin_wants_short` (`noul`): read the sentence. Code drops or lightens
  candidates accordingly.
- `session_pick` (`choice` over the three candidate ids): which fits today.
  High confidence → present it with a *Swap* on every exercise. Low
  confidence → present two, user picks.
- `outdoor_ok` (`noul`): the forecast summary is acceptable for outdoor
  conditioning given the check-in — a second opinion on top of the code
  thresholds, used only to *veto* outdoor, never to override an indoor pick.

No check-in sentence → the `checkin_*` questions are omitted and the
program's default candidate is used unless weather forces a change. The app
works with Jev completely absent; it just never adapts to how you feel.

### 5.4 Weather: National Weather Service

- **Endpoint chain:** `GET https://api.weather.gov/points/{lat},{lon}` once
  (cached indefinitely; it returns the gridpoint), then
  `GET {properties.forecastHourly}` for the hourly forecast.
- **Headers:** NWS requires a `User-Agent` identifying the app and a contact
  address. No API key.
- **Fields used:** `temperature`, `windSpeed`, `probabilityOfPrecipitation`,
  `relativeHumidity`, `shortForecast`, for the hours covering the planned
  training window.
- **Feasibility is code.** Outdoor conditioning is feasible when every hour
  in the window satisfies the user's thresholds (defaults: 25 °F ≤ temp ≤
  90 °F, wind < 25 mph, precipitation probability < 40 %, `shortForecast`
  not containing thunder/ice/snow). Heat index and wind chill are computed
  in code from temperature, humidity, and wind.
- **Cache 1 hour; stale is fine.** Forecast older than 6 hours or missing →
  outdoor is *not* feasible. Indoor never depends on weather.
- **Coordinate precision:** 2 decimals (~1 km). NWS grid cells are 2.5 km;
  more precision buys nothing and leaks more.
- **US only** by design. Travelling outside NWS coverage yields an indoor
  session.
- **Weather carries more weight here than in a gym programme.** Sled and
  lines are outdoor-only, so a bad forecast removes two of the four slot
  types. The indoor alternative is always a kettlebell + bodyweight session,
  with battle rope if the indoor location is flagged as having room. If
  weather forces indoor twice in a row, the next feasible outdoor day is
  biased toward whichever outdoor slot was skipped.

### 5.5 In-session

The generated session is a list of blocks. Each strength block shows
exercise, target sets × reps × load, and the last performance. The user
taps *Done* per set (load and reps prefilled; edit if different) and an
optional RPE. A rest timer starts automatically after each set (§6). The
conditioning block hands off to the interval timer with one tap. *Swap*
on any exercise asks the rules engine for the next candidate in the same
pattern — no Jev call.

**Check-out.** After *Finish session* the user gets one line — "swings were
easy, Tank at 4 was brutal" — and a row of Easy / Right / Hard toggles per
block. Jev reads the line into a feel per block (`b{i}_feel`, choice with a
`not_mentioned` option); the toggles are prefilled from that and any tap wins
over the reading. Without Jev the toggles alone do the job. The feel is
stored per (session, block) and feeds progression: *too hard* holds a block
the numbers alone would have progressed; *too easy* with every set hit takes
the bigger step. A miss streak still wins over any feel.

### 5.6 Data model (sketch)

```
Equipment(id, kind, detail, loadKg?, present, isCustom)
Exercise(id, name, patterns[], equipmentNeeded[], locations[], weatherSensitive,
         loadModel (variable|fixed|sled|lines|timed), progressionLadder[],
         preferenceWeight, isUserDefined, excluded)
ProgramTemplate(id, slots[])           // seeded, editable
Session(id UUID, plannedFor LocalDate, startedAt?, finishedAt?, slot,
        candidateSetJson, chosenCandidateId, jevConfidence?, checkInText?, checkOutText?)
BlockFeedback(sessionId, blockId, exerciseId, feel (too_easy|right|too_hard), source (jev|manual), at)
SessionBlock(id UUID, sessionId, ordinal, exerciseId?, intervalPlanId?, prescriptionJson)
SetLog(id UUID, blockId, ordinal, reps, kg, rpe?, at Instant)
ForecastCache(gridpoint, fetchedAt, hourlyJson)
```

## 6. Feature 3 — Interval timer

### 6.1 Requirements

- Runs with the screen off and the app in the background, for up to an
  hour, without drift or missed cues.
- Audible cues (beeps for countdown, distinct tone for phase change, TTS for
  the next exercise name) that duck other audio rather than stopping it.
  Vibration in parallel. Cues work over Bluetooth headphones.
- Controls from the lock-screen notification: pause/resume, skip, stop.
- Works in airplane mode. No Jev, no network, ever.
- Structures: work/rest rounds (Tabata, custom), EMOM, AMRAP countdown,
  circuits with named exercises and per-exercise durations, and a
  standalone rest timer. Presets plus plans emitted by the generator.
- **Lines mode (self-paced).** A lines plan is a sequence of legs (*P1 out
  and back*, *P2 out and back*, …) that end when the runner does, not when a
  clock does. The timer shows the current leg, counts *up*, and records a
  split on one tap (or a volume-button press, so a phone in a pocket works).
  Rest between rounds is a normal countdown. Splits per point are stored so
  the same course can be compared across sessions. An origin movement
  between legs is shown as a prompt with a rep count, also dismissed by tap.

### 6.2 Mechanism

- **Foreground service** owns the run. It holds a partial wake lock for the
  duration and posts an ongoing notification with the current phase and
  remaining time.
- **Time is derived, not counted.** The service records the
  `SystemClock.elapsedRealtime()` at start and at each pause; every phase
  boundary is computed from that anchor. A UI tick only re-renders; missing
  ticks cannot drift the timer. Wall-clock (`System.currentTimeMillis`) is
  never used for timing.
- **Cues are scheduled from the same anchor**, a few hundred milliseconds
  ahead, via a coroutine delay to the computed boundary. Sounds are
  pre-loaded in `SoundPool`; TTS is warmed up at service start so the first
  exercise name is not late.
- **Audio focus** is requested as transient-may-duck for each cue.
- **Foreground service type.** Android 14+ requires one. The natural fit is
  `health` (fitness use), but that type carries permission prerequisites I
  have not verified for the target API level; `mediaPlayback` is the
  fallback and is honest (the service plays audio cues). *Decide when the
  target SDK is fixed — check `foregroundServiceType` requirements for that
  level before committing.*
- **Process death** during a run: the plan and anchor are persisted at start
  and on every pause, so a restarted service resumes at the right phase.

### 6.3 UI

One screen: phase name, huge remaining-time digits, round *n of N*, a
progress ring, and two buttons — pause and skip — sized for a sweaty thumb.
Keep-screen-on is a toggle. Landscape is supported for a phone propped on a
bench.

### 6.4 Data model (sketch)

```
IntervalPlan(id UUID, name, isPreset, planJson)
  // phases: [{label, kind: work|rest|prep|leg|origin, seconds? , pointIndex?, reps?}]
  // kind=leg/origin are self-paced: no seconds, ended by tap
LinesCourse(id UUID, name, points: [{index, label, metres}])
TimerRun(id UUID, planId, sessionBlockId?, startedAt, finishedAt?, completedPhases, pausedTotalMs)
Split(id UUID, runId, phaseIndex, pointIndex?, elapsedMs)
```

## 7. Cross-cutting

### 7.1 Offline behaviour

| Dependency | Down or absent | Result |
|---|---|---|
| Jev (no key / offline / 5xx / > 4 s) | Food entry | Rows open with top FTS candidate; user picks |
| Jev | Generator | Default candidate; check-in ignored |
| FDC API (no key / offline / > 3 s) | Food entry | Local-miss fragments become quick-add rows |
| NWS | Generator | Indoor session; badge says "no forecast" |
| Everything | Timer | Unaffected |

### 7.2 Notifications

Three channels: **Meals** (evening prompt), **Training** (planned-session
reminder, morning of), **Timer** (ongoing, silent). All can be turned off
individually. None are sticky except the timer while running.

### 7.3 Backup

There is no cloud. The single point of failure is the phone. Mitigation:

- **Export** the whole database as JSON to a user-chosen location via the
  storage picker (SAF). Import replaces or merges by UUID (idempotent).
- **Reminder** to export weekly, dismissible, in the Training channel.
- Room database is included in Android's device backup if the user has it
  enabled; we do not depend on it.

### 7.4 Privacy

What leaves the device, in full:

| Destination | Payload | Why |
|---|---|---|
| `api.typesafe.ai` | Food sentences; check-in sentences; candidate food names; session summaries | Judgement |
| `api.nal.usda.gov` | Single food fragments that missed locally (e.g. `"quest bar"`) | Food lookup |
| `api.weather.gov` | Home coordinate at 2-decimal precision; User-Agent string | Forecast |
| Your own Supabase project | `BodyProfile`, `GoalSettings`, `trainingDays`, `timeBudgetMinutes` — read and write, both directions | §2 exception: conversational read/write via Claude MCP |

No identifiers beyond the two API keys and the Supabase session. Body
weight, goal, and schedule history go nowhere except the user's own
Supabase project (history stays there as an append-only log, never
deleted, same as Room) — nothing else in this table sees them. Location
history goes nowhere at all; only the rounded home coordinate reaches NWS.
Request bodies are never logged.

Two things leave as *text the user typed*. That text is the user's own, so
prompt injection is not a threat here; the instructions sent to Jev still
say the state is data, not instructions, because it costs nothing and the
habit is worth keeping.

## 8. Where Jev is, and is not

| Judgement | Jev? | Why |
|---|---|---|
| Which food does "toast" mean | Yes | Unstructured text → pick |
| Portion when unstated | Yes | Common-sense default from context |
| How many kcal in 60 g of it | No | Arithmetic |
| TDEE, budget, protein floor | No | Arithmetic |
| Is the check-in describing a shoulder problem | Yes | Unstructured text → probability |
| Which of three sessions fits today | Yes | Holistic pick over structured state |
| Which exercises go in a session | No | Set filter + preference weight + rotation |
| Next reps / variation / sled setting | No | Progression rule per load model |
| How many lines points today | No | Progression rule |
| Is 88 °F and 80 % humidity OK to run in | No (thresholds) + Jev veto | Code decides; Jev may say "no" given the check-in |
| Anything in the timer | No | Real-time, offline |

All questions and thresholds are in one file, `JevQuestions.kt`, per
`AGENTS.md`. The initial question set below is a *draft* — TypeSafe's own
guidance is that questions need human editing against real examples before
they are trusted.

### Draft question registry

| Id | Type | Feature | Notes |
|---|---|---|---|
| `f{i}_match` | choice | Food | Options: candidate ids + `none_of_these` |
| `f{i}_portion` | choice | Food | Options: the food's standard portions; only when no explicit quantity |
| `f{i}_is_food` | noul | Food | Drops non-food fragments |
| `f{i}_meal` | choice | Food | Options: saved meal ids + `none_of_these`; only when meals exist |
| `checkin_upper_limited` | noul | Generator | Pain/limitation in shoulder, elbow, wrist, neck |
| `checkin_lower_limited` | noul | Generator | Hip, knee, ankle, low back |
| `checkin_low_energy` | noul | Generator | Poor sleep, illness, fatigue |
| `checkin_wants_short` | noul | Generator | Explicit or implied time pressure |
| `session_pick` | choice | Generator | Options: three candidate ids |
| `outdoor_ok` | noul | Generator | Veto only |
| `b{i}_feel` | choice | Check-out | Options: `too_easy`, `about_right`, `too_hard`, `not_mentioned`; one per block |

## 9. Milestones

Order chosen so each milestone is usable on its own and the riskiest
integration (Jev over free text) is exercised early with real data.

1. **Timer** — no dependencies, immediately useful, proves the
   foreground-service and audio mechanism.
2. **Tracker, manual** — bundled USDA DB, FTS search, favourites, quick add,
   weigh-ins, weekly view. No Jev yet; this generates the sentence corpus.
3. **Tracker, Jev** — sentence pipeline, confidence routing, threshold tuning
   against the corpus from (2).
4. **Generator, code only** — equipment, library, program template,
   composition, progression, session logging, handoff to timer.
5. **Generator, weather + Jev** — NWS, feasibility, check-in questions,
   session pick.
6. **Export/import, evening prompt, polish.**

## 10. Non-goals

Multiple users · social features · Play Store release · wearables / Wear OS ·
photo or barcode food logging (v1) · full macro/micronutrient tracking ·
coaching, form cues, or medical advice · step counting or activity
detection · cloud sync of anything beyond the §2 bodyspec/goal exception —
food log, sessions, timer runs, equipment, home coordinate, and lines
course stay on-device · a backend of our own beyond the Supabase project
that exception uses.

## 11. Open decisions

- [ ] Fill in the remaining §3 parameters: body stats, home coordinate,
      training days and time budget.
- [ ] Measure the lines course: how many points, and the distance to each.
- [ ] Torque Tank model — which resistance settings exist (the progression
      rule needs the ordered list).
- [ ] Variation ladders for each fixed-load movement (push-up → ? → ?;
      swing → ? → ?) — these encode what you consider "harder".
- [ ] Foreground service type for the timer (`health` vs `mediaPlayback`) —
      verify prerequisites at the chosen target SDK.
- [x] USDA Branded dataset — *resolved:* on-demand via the FDC API on local
      miss, results cached as user foods (§4.3).
- [ ] Barcode scanning (ML Kit on-device; lookup via the FDC API's GTIN
      search, so no new data source). v2 unless it turns out to be the main
      friction.
- [ ] Health Connect: read weight from a smart scale? Write sessions?
      Out unless a scale is in the picture.
- [ ] Seed program template — write the first one together; it encodes
      training preferences the document cannot guess.
- [ ] Jev thresholds — tune after milestone 2 produces ≥ 100 real sentences.

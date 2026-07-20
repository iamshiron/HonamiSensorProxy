# BridgeApp — a device-agnostic sensor → HTTP bridge

A standalone Android app whose only job is: **read health/sensor data from whatever
device is available, normalize it, and push it to one or more protected HTTP
endpoints.** It knows nothing about BeatDash or any specific backend — every backend is
just a configured *sink* that speaks a small, standard contract.

Reuse target: drop the same app onto any project that needs wearable data. New project =
new sink config (a URL + an auth issuer), nothing else.

---

## 1. Design principles

1. **Backend-agnostic.** The app never contains a line of BeatDash-specific code. All
   backend knowledge lives in per-sink configuration.
2. **Ports & adapters.** Two pluggable seams — *sources* (where data comes from) and
   *authenticators* (how a push is authorized). Adding a device or an auth scheme = one
   new adapter, nothing downstream changes.
3. **One canonical sample.** Every source normalizes to the same `Sample` type; every
   sink receives the same JSON envelope. The wire is uniform.
4. **The wire is always Bearer.** Pushes are always `Authorization: Bearer <token>`
   (RFC 6750). Only *how you obtain the token* varies, and that is itself pluggable.
5. **Secret-free client.** The app is a public OAuth client. It never embeds a client
   secret. Anything needing a secret (vendor cloud OAuth) is out of scope — see §11.
6. **Fail soft.** A missing permission, an unavailable sensor, or a dead sink degrades
   that one path; everything else keeps flowing.

### Non-goals

- Not a fitness UI. No charts, history, or analytics — that's the backend's job.
- Not a vendor-cloud aggregator (Fitbit/Garmin/Withings OAuth). That needs a server-side
  broker; see §11.
- Not Apple Watch (no Android runtime; no server API to reach either).

---

## 2. The core flow

```
              ┌── HealthConnectSource ──┐
 sensors ────►├── BleHeartRateSource ───┤─►  merge()  ─►  buffer/batch  ─►  for each Sink:
              └── WearHealthServices ───┘   Flow<Sample>   (size/time)        POST envelope
                                                                              with Bearer token
```

Every source is a `Flow<Sample>`. The engine merges them into one stream, batches, and
forwards each batch to every configured sink. That single `Flow<Sample>` merge point is
the entire "same flow for any watch" story.

---

## 3. The canonical data model

```kotlin
enum class Metric { HeartRate, Calories, Steps, SpO2, /* extend freely */ }

/** One normalized reading. `value` is float32 in the metric's canonical unit. */
data class Sample(
    val metric: Metric,
    val value: Float,        // bpm / kcal / count / % — unit implied by metric
    val epochMillis: Long,   // UTC, source-provided timestamp (never "now" unless unknown)
    val source: String,      // adapter id, e.g. "wear-health-services"
)
```

Canonical units (the contract — adapters convert to these):

| Metric     | Unit    |
|------------|---------|
| HeartRate  | bpm     |
| Calories   | kcal    |
| Steps      | count   |
| SpO2       | percent |

A bare float is ambiguous (148 vs 3.2), so `metric` + implied unit always travels with
the value.

---

## 4. Sources (the device seam)

```kotlin
interface SensorSource {
    val id: String                                   // "health-connect", "ble-hrs", "wear-health-services"
    val metrics: Set<Metric>                         // what this adapter can produce
    suspend fun isAvailable(): Boolean               // SDK present + device supported
    suspend fun ensurePermissions(): Boolean         // request/verify runtime permissions
    fun stream(want: Set<Metric>): Flow<Sample>      // adapter drives it however its SDK works
    fun close()
}
```

**Why a `Flow`, not `poll()`.** SDKs split into two shapes and a stream unifies both:

- **Pull** (Health Connect `readRecords`): the adapter polls internally and emits.
- **Push** (BLE notifications, Wear passive listener): the adapter emits from its callback.

Downstream never sees the difference.

### Planned adapters

| Adapter                    | Runs on | Covers                                                   | Live?      |
|----------------------------|---------|---------------------------------------------------------|------------|
| `HealthConnectSource`      | Phone   | Broadest — anything writing Health Connect (Samsung Health, Fitbit, Google Fit, Zepp, Garmin-via-sync) | Batched, post-hoc |
| `BleHeartRateSource`       | Phone   | Standard BLE Heart Rate Service (0x180D): chest straps + watches in broadcast mode | Real-time (~1 Hz) |
| `WearHealthServicesSource` | Watch   | Wear OS watches (Galaxy 4+, Pixel Watch) via Jetpack Health Services | Real-time, on-device |

**Coverage reality:** no single on-device app reads every watch — vendors wall off their
sensors. `HealthConnectSource` is the universal phone-side net; `WearHealthServicesSource`
is the real-time bonus for Wear OS. For a Galaxy Watch specifically, live/dense HR needs
the Wear module (Samsung does not broadcast standard BLE HR).

**There is no fourth door.** Every consumer wearable vendor funnels third parties through
exactly one of three surfaces: an **on-watch app SDK** (Wear OS Health Services, Garmin
Connect IQ, old Tizen), a **health datastore** (Health Connect on Android, HealthKit on
iOS), or a **cloud API**. No vendor — including Samsung, Google, Xiaomi — ships a
phone-side SDK that streams sensor data straight off the watch. So a whole class of
devices collapses to "read Health Connect, accept it's batched." That's not a gap in this
design; it's the platform reality the `SensorSource` abstraction exists to absorb.

> **Health Connect is on-device, not a cloud.** Your app reads it via an Android runtime
> permission — no cloud call, no vendor OAuth. It's *populated* by the user's own vendor
> companion app (Mi Fitness, Samsung Health, Zepp…), which may sync via that vendor's
> cloud, but that's the half you don't own; the read is local.

Adding a new device later = implement one `SensorSource` and register it. Nothing else
changes.

### 4.1 Local device coverage (no cloud OAuth in the app)

Union of the three local mechanisms. "Real-time" = usable for a live/dense curve;
"batched" = post-hoc, resolution capped by what the companion app recorded.

| Vendor / family | Best local path | Real-time? | Notes |
|---|---|---|---|
| **Google / Wear OS** (Pixel Watch, TicWatch, OnePlus WearOS, Fossil Gen 6) | Wear Health Services (watch app) | ✅ dense | Full HR/steps/calories on-device. |
| **Samsung Galaxy Watch 4+** | Wear Health Services (watch app) | ✅ dense | Runs Wear OS. Health Connect via Samsung Health is a delayed backup. |
| Samsung Tizen (Watch 3 & older) | Health Connect via Samsung Health | ❌ batched | No Wear OS → no on-watch app. |
| **Garmin** | BLE broadcast HR (0x180D) | ✅ HR only | "Broadcast Heart Rate" mode. No native Health Connect (needs a paid bridge); no steps/cal over BLE. Connect IQ app = separate on-watch path. |
| **Xiaomi Watch 2 / 2 Pro** (2023) | Wear Health Services (watch app) | ✅ dense | The only Wear OS Xiaomi. Same top tier. |
| **Xiaomi — everything else** (Watch S1/S2/S3, Mi Watch, Redmi Watch, Smart Band 5–9) | Health Connect via Mi Fitness | ⚠️ batched | Proprietary OS, closed to apps; **no standard BLE broadcast**. See §4.2. |
| Amazfit / Zepp OS | BLE broadcast (some models) or Health Connect via Zepp | ⚠️ mixed | Broadcast varies by model; HC otherwise. |
| Chest straps (Polar, Garmin HRM, Wahoo, Coros) | BLE HR (0x180D) | ✅ HR only | Cleanest real-time HR of all. |
| **Apple Watch** | — | ❌ | iOS/HealthKit only; needs a Swift app. Out of scope. |

**Verdict:** Wear module + BLE + Health Connect together cover the large majority of the
Android-ecosystem watch market for at least HR — real-time on all Wear OS (incl. Samsung
Galaxy 4+ and Xiaomi Watch 2) plus Garmin/straps, batched for most of the rest. Dead
zones: Apple, old Tizen, and dense HR from proprietary Xiaomi/Huawei/Amazfit.

### 4.2 Proprietary watches via Health Connect (Xiaomi, Huawei, most non-Wear-OS)

For these there is **no on-device app and no standard BLE broadcast** — Health Connect is
the *only* path, so the `HealthConnectSource` is the highest-leverage adapter to make
robust. Two platform weaknesses to engineer around:

**1. Sync lag — read incrementally with a changes token, retry on backoff.** Companion
apps (Mi Fitness etc.) write to Health Connect on their own schedule, often minutes after a
session. A one-shot read at session end misses the data. Instead keep a Health Connect
**changes token** (`getChangesToken` / `getChanges`) and let a **WorkManager** job re-check
on a backoff (now, +2 min, +10 min, +30 min) until the window fills or a deadline passes.
Push only *new* records each cycle (or a rolling 24–72 h window) — never re-ship all-time
history. Because the backend dedupes by `(subject, metric, recordedAt)`, late arrivals just
slot into the right session and replays are free.

**2. Density is set at record time, not push time.** Passive HR is sparse (auto/~10 min →
often 0–1 samples per song); pushing "everything available" can't recover samples the
watch never recorded. The fix is a **one-time setting**, not a per-session workout:

> **Onboarding nudge:** *"For heart-rate tracking, set your watch app's HR monitoring to
> continuous / 1-minute (Mi Fitness, Samsung Health, Zepp all have this). Otherwise
> readings are too sparse to use."*

Continuous/1-min passive HR (~1 sample/min) covers any session with no user action per
play, at some battery cost. A recorded workout gives 1–few-second HR if the user wants a
finer curve, but it's optional — not required for matching.

**Bulk-push + server-side filtering is the right division of labor.** The app stays dumb:
it reads what Health Connect has (incrementally) and pushes it; the *server* owns "what's a
session" and windows/discards. The app never needs to know a backend's session times, which
keeps it agnostic. (Server keep-vs-discard tradeoff is noted in §12.6.)

---

## 5. Authentication (the authorization seam)

Two separable halves:

- **The push** — always `Authorization: Bearer <access_token>` (RFC 6750). Universal,
  never varies.
- **Acquiring the token** — pluggable, so static-token and full OAuth coexist.

```kotlin
interface Authenticator {
    /** A currently-valid bearer for the push. Refreshes silently; re-pairs if needed. */
    suspend fun accessToken(): String
}

class StaticTokenAuthenticator(private val token: String) : Authenticator
class OAuthDeviceAuthenticator(private val cfg: OidcConfig, private val store: TokenStore) : Authenticator
```

### Tier 1 — static token (ship now)

Backend mints a long-lived token; the app stores it and sends it as Bearer. Provisioned
by QR/deep link (§7). Already generic — any backend can hand out a token. This is what
BeatDash does today.

### Tier 2 — OAuth 2.0 Device Authorization Grant (the standard others implement)

The app is an input-constrained public client, so the right grant is **Device
Authorization Grant (RFC 8628) + PKCE (RFC 7636)**, discovered via **OIDC Discovery**:

1. App fetches `https://<issuer>/.well-known/openid-configuration` → learns
   `device_authorization_endpoint`, `token_endpoint`, `jwks_uri`.
2. App calls the device endpoint → gets `user_code` + `verification_uri`; shows it (and a
   QR): *"visit <uri>, enter WXYZ-1234."*
3. User approves in a normal browser, logged into that site.
4. App polls the token endpoint → receives **access + refresh tokens**; starts pushing.

No secret in the app, no redirect-URI deep links, works headless on a watch. Refresh
tokens live in the Android Keystore / EncryptedSharedPreferences; refresh is silent.

**Why this makes the app website-agnostic:** a sink's whole identity is an issuer URL.
Any backend that publishes a discovery document + implements the two endpoints (roll your
own, or drop in Keycloak / Ory Hydra / Auth0 / Zitadel) works with the app unchanged.

---

## 6. Sinks (the backend seam)

A sink is pure config plus an `Authenticator`:

```kotlin
data class SinkConfig(
    val name: String,                 // "BeatDash"
    val ingestUrl: String,            // https://beatdash.app/api/health/ingest
    val metrics: Set<Metric>,         // which metrics this sink wants
    val cadenceSeconds: Int,          // batch flush interval
    val auth: AuthConfig,             // StaticToken | Oidc
)

sealed interface AuthConfig {
    data class StaticToken(val token: String) : AuthConfig
    data class Oidc(val issuer: String, val clientId: String, val scope: String) : AuthConfig
}
```

```kotlin
class Sink(private val cfg: SinkConfig, private val auth: Authenticator, private val http: HttpClient) {
    suspend fun push(batch: List<Sample>) {
        val relevant = batch.filter { it.metric in cfg.metrics }
        if (relevant.isEmpty()) return
        http.post(cfg.ingestUrl) {
            header("Authorization", "Bearer ${auth.accessToken()}")
            setBody(Envelope(source = deviceId, samples = relevant.map(::toWire)))
        }
    }
}
```

### The wire envelope (what every backend receives)

```jsonc
POST <ingestUrl>
Authorization: Bearer <token>
Content-Type: application/json
{
  "source": "galaxy-watch-fe",
  "samples": [
    { "metric": "heart_rate", "value": 148.0, "unit": "bpm",   "recordedAt": "2026-07-20T18:03:12Z" },
    { "metric": "calories",   "value": 3.2,   "unit": "kcal",  "recordedAt": "2026-07-20T18:03:12Z" },
    { "metric": "steps",      "value": 11.0,  "unit": "count", "recordedAt": "2026-07-20T18:03:12Z" }
  ]
}
```

Response: `{ "accepted": <int> }`. Backends route by `metric`, ignore unknown ones, and
dedupe by `(metric, recordedAt)`. Timestamps are UTC ISO-8601.

---

## 7. Provisioning (zero-friction setup)

A backend renders a QR (or deep link) encoding a sink config; the app scans it and adds
the sink in one tap.

```
beatsensor://sink?name=BeatDash
  &ingest=https%3A%2F%2Fbeatdash.app%2Fapi%2Fhealth%2Fingest
  &metrics=heart_rate,calories
  &auth=oidc&issuer=https%3A%2F%2Fbeatdash.app&clientId=sensor-bridge&scope=ingest%3Awrite
```

For tier 1, `auth=token&token=<opaque>` instead. New project → new QR → the app is
configured with no typing.

> **Implementation note — the app expects a JSON QR payload, not the deep link above.**
> A scanned QR carries a JSON object (no registered URL scheme required, trivial to parse
> from an in-app scan):
> ```json
> {"v":1,"name":"BeatDash","ingest":"http://<host>:<port>/api/hsp/ingest",
>  "auth":"token","token":"<opaque>","metrics":["heart_rate","calories","steps","spo2"]}
> ```
> The `beatsensor://sink?…` deep link is still accepted as a fallback (auto-detected by the
> parser). Fields map 1:1 to a `SinkConfig`; `ingest` may be `http://` for local-dev
> backends even though HTTPS is preferred in production (§12.1/§14).

---

## 8. Runtime & lifecycle

- **Live capture** (BLE / Wear) runs in a **foreground service** (`connectedDevice` /
  `health` FGS type) so the OS doesn't kill it mid-session.
- **Batched capture / retries** use **WorkManager** (constraints: network available;
  exponential backoff). Failed pushes persist to a local outbox (Room/DataStore) and
  retry — never drop samples on a flaky network.
- **Buffering:** `Flow<Sample>` → buffer by size *or* `cadenceSeconds`, whichever first.
- **Dedupe:** the app may re-emit overlapping windows (esp. Health Connect); the backend
  dedupes by `(metric, recordedAt)`, so at-least-once delivery is safe.
- **Clock:** timestamps come from the sensor/SDK, so device clock accuracy matters for
  backends that match samples to time windows (e.g. BeatDash matches HR to a play by
  `recordedAt ∈ [start, end]`).

---

## 9. Permissions

| Source              | Permission                                                        |
|---------------------|-------------------------------------------------------------------|
| Health Connect      | `android.permission.health.READ_HEART_RATE`, `READ_STEPS`, `READ_ACTIVE_CALORIES_BURNED`, … |
| BLE                 | `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (Android 12+), location on older APIs |
| Wear Health Services| `android.permission.BODY_SENSORS` (+ `BODY_SENSORS_BACKGROUND`), `ACTIVITY_RECOGNITION` |
| Network             | `INTERNET`, foreground-service type declarations                  |

`ensurePermissions()` on each source requests only what that source needs; a denied
permission disables just that source.

---

## 10. Module structure

A single Gradle project, three modules — phone + wear share the core:

```
bridge-app/
├── core/                      # pure Kotlin, no Android UI — the reusable heart
│   ├── model/                 # Metric, Sample, Envelope, SinkConfig, AuthConfig
│   ├── source/                # SensorSource interface
│   ├── auth/                  # Authenticator, OAuthDeviceAuthenticator, StaticTokenAuthenticator, TokenStore
│   ├── sink/                  # Sink, HttpClient wiring, outbox
│   └── engine/                # merge + buffer + dispatch
├── phone/                     # Android app module
│   ├── source/                # HealthConnectSource, BleHeartRateSource
│   ├── service/               # ForegroundService, WorkManager workers
│   └── ui/                    # sink list, QR scanner, permission prompts, pairing screen
├── wear/                      # Wear OS app module
│   ├── source/                # WearHealthServicesSource
│   └── service/               # on-watch FGS; POST directly over Wi-Fi/LTE OR relay to phone via Data Layer
└── build.gradle.kts
```

- `core` has **no** Android UI/vendor deps — it's where portability lives and where unit
  tests target.
- `phone` and `wear` each add only their platform sources + service plumbing.
- The wear module can either push directly (watch has Wi-Fi/LTE) or relay to the phone
  over the Wearable Data Layer if you prefer the phone to own all egress.

### Suggested libraries

- HTTP/JSON: **Ktor client** + **kotlinx.serialization** (multiplatform-friendly).
- OAuth: **AppAuth-Android** for auth-code+PKCE; a small custom impl (or Ktor calls) for
  the device-grant polling loop.
- Health Connect: `androidx.health.connect:connect-client`.
- Wear: `androidx.health:health-services-client`.
- Background: `androidx.work:work-runtime-ktx`.
- Secure storage: `androidx.security:security-crypto` (EncryptedSharedPreferences) /
  DataStore + Keystore.

---

## 11. Out of scope: authenticated *vendor cloud* sources

Fitbit / Garmin / Withings / Oura / Polar expose no live on-device sensor — you read
their **cloud API over OAuth2 with a client secret**, which **cannot** live in a
distributed app. Those require a **server-side broker** that holds the secrets, runs the
OAuth dance per user, ingests vendor webhooks, normalizes to the same envelope, and
forwards to sinks. That broker is a separate reusable *service*, not part of this app.
Build it only when you need a device with no local path — never for your own Galaxy Watch,
which is a local source.

---

## 12. Backend contract (complete server-side specification)

This is the full, self-contained contract a backend implements to be a sink. A backend
that satisfies §12.1–§12.3 works with the app today (tier 1); adding §12.4 makes it a
standards-based OAuth resource server (tier 2). Nothing here is BeatDash-specific except
§12.9.

### 12.1 Conventions

- **Transport:** HTTPS only. Reject cleartext.
- **Content type:** `application/json; charset=utf-8` for request and response bodies.
  Token endpoints (§12.4) use `application/x-www-form-urlencoded` per OAuth.
- **Timestamps:** UTC, RFC 3339 / ISO-8601, e.g. `2026-07-20T18:03:12Z` or
  `2026-07-20T18:03:12.482Z`. A timestamp with no offset is treated as UTC. Non-UTC
  offsets are converted to UTC on receipt.
- **Numbers:** `value` is a JSON number representable as float32. Reject non-finite
  (`NaN`/`Inf`).
- **Versioning:** the ingest URL is opaque to the app (it comes from sink config), so a
  backend versions by path (`/v1/...`) or media type at its discretion. The envelope
  itself is unversioned; add fields backward-compatibly.
- **Unknown fields:** ignore unknown JSON fields (forward compatibility). Unknown
  `metric` values are ignored, not errors (§12.2).
- **Error body (recommended shape)** for 4xx/5xx:
  ```jsonc
  { "error": "invalid_request", "message": "human readable", "details": ["field: reason"] }
  ```
  `error` is a stable machine code; `message`/`details` are informational. (BeatDash's
  current handlers return a bare `["reason", ...]` array — acceptable, but the object form
  above is preferred for new backends.)

### 12.2 Ingest endpoint (required)

```
POST {ingestUrl}
Authorization: Bearer <token>
Content-Type: application/json
Idempotency-Key: <opaque>        # optional, see §12.6
```

**Request body:**

```jsonc
{
  "source": "galaxy-watch-fe",         // string, required. Identifies the emitting device/adapter.
  "samples": [                          // array, required, 1..MAX_BATCH
    {
      "metric": "heart_rate",          // string, required. snake_case; see registry §12.7.
      "value": 148.0,                  // number (float32), required, finite.
      "unit": "bpm",                   // string, optional. Self-description; MUST match the
                                       //   metric's canonical unit if present. Server may ignore.
      "recordedAt": "2026-07-20T18:03:12Z", // string, required. UTC RFC 3339.
      "source": "…"                    // string, optional. Per-sample override of envelope source.
    }
  ]
}
```

**Field rules:**

| Field | Type | Required | Rule |
|-------|------|----------|------|
| `source` | string | yes | 1–64 chars. Free-form device id. |
| `samples` | array | yes | length 1..`MAX_BATCH` (recommend 5000). Empty → 400. |
| `samples[].metric` | string | yes | snake_case. Unknown → the sample is **dropped silently**, not a 400. |
| `samples[].value` | number | yes | finite float32. Per-metric range in §12.7; out-of-range → drop sample. |
| `samples[].unit` | string | no | if present must equal the metric's canonical unit; mismatch → drop sample. |
| `samples[].recordedAt` | string | yes | valid UTC RFC 3339; unparseable → drop sample. Far-future (> now + skew, e.g. 5 min) → drop. |
| `samples[].source` | string | no | overrides envelope `source` for that sample. |

**Processing semantics (in order):**

1. **Authenticate** the bearer (§12.3 / §12.4) → resolve a **subject** (the user). Failure → 401.
2. **Feature gate (optional):** if the subject hasn't enabled ingestion for this data,
   the backend MAY **silently no-op with `200 { "accepted": 0 }`** (BeatDash does this — the
   token stays valid but tracking is off), or return 403. No-op is preferred so the app
   doesn't treat a disabled feature as an auth failure.
3. **Validate** the envelope. Malformed JSON / missing required top-level fields / empty
   `samples` / oversized batch → 400 (or 413 for size).
4. **Filter** samples: drop unknown-metric, out-of-range, bad-unit, bad/future-timestamp
   ones (per the table). This is per-sample and never fails the whole request.
5. **Dedupe** the surviving samples against stored data and within the batch, keyed on
   `(subject, metric, recordedAt)` (§12.6).
6. **Persist** the new samples.
7. **Respond** `200`.

**Success response (200):**

```jsonc
{ "accepted": 12 }                     // count newly stored after validation + dedupe (required)
// Optional richer form:
{ "accepted": 12, "received": 20, "duplicates": 5, "rejected": 3 }
```

**Status codes:**

| Code | When |
|------|------|
| 200 | Processed (including feature-off no-op and all-duplicates → `accepted: 0`). |
| 400 | Malformed JSON, missing required field, empty `samples`, or no valid samples remain *and* the backend chooses to signal it (returning `200 {accepted:0}` is also acceptable). |
| 401 | Missing/invalid/expired token. |
| 403 | Token valid but lacks required scope, or feature disabled (if not using the no-op convention). |
| 413 | `samples` exceeds `MAX_BATCH`. |
| 429 | Rate limited. SHOULD include `Retry-After`. |
| 5xx | Server error — the app will retry with backoff, so transient failures are safe. |

### 12.3 Auth tier 1 — static token (required minimum)

The simplest conformant auth. Enough for personal/single-user use.

- **Presentation:** `Authorization: Bearer <token>`. Backends MAY also accept a fallback
  header `X-Bridge-Token: <token>` (BeatDash accepts `X-BeatDash-Health-Token`).
- **Storage:** store only a hash of the token (SHA-256, base64). Never persist plaintext.
- **Validation:** hash the incoming token, look up the owning subject (constant-time
  compare / indexed hash). No match → 401.
- **Scope:** a static token authorizes **ingest only** for its subject.
- **Minting endpoint (required to issue tokens):**
  ```
  POST {mintUrl}          # authenticated by the site's normal session (cookie/JWT), NOT the ingest token
  → 200 { "token": "<plaintext>" }   # shown to the user exactly once; re-minting rotates (replaces the hash)
  ```

### 12.4 Auth tier 2 — OAuth 2.0 / OIDC (optional; the interoperable standard)

Makes any website a drop-in sink for the app with no shared static secret. The app is a
**public client** using the **Device Authorization Grant (RFC 8628) + PKCE (RFC 7636)**,
discovered via **OIDC Discovery**.

**12.4.1 Discovery** — `GET {issuer}/.well-known/openid-configuration` → JSON containing at
least:

| Field | Requirement |
|-------|-------------|
| `issuer` | must equal `{issuer}` |
| `device_authorization_endpoint` | required |
| `token_endpoint` | required |
| `jwks_uri` | required if issuing JWT access tokens |
| `introspection_endpoint` | required if issuing opaque access tokens |
| `grant_types_supported` | must include `urn:ietf:params:oauth:grant-type:device_code` and `refresh_token` |
| `scopes_supported` | should include the ingest scope, e.g. `ingest:write` |
| `token_endpoint_auth_methods_supported` | should include `none` (public client) |
| `code_challenge_methods_supported` | should include `S256` |

**12.4.2 Device authorization request** (RFC 8628 §3.1) —
`POST {device_authorization_endpoint}` (form-encoded):
```
client_id=sensor-bridge&scope=ingest:write&code_challenge=<S256>&code_challenge_method=S256
```
Response (200):
```jsonc
{
  "device_code": "…",              // opaque, app-held
  "user_code": "WXYZ-1234",        // shown to the user
  "verification_uri": "https://beatdash.app/link",
  "verification_uri_complete": "https://beatdash.app/link?code=WXYZ-1234", // optional, for QR
  "expires_in": 600,
  "interval": 5                    // min seconds between token polls
}
```

**12.4.3 User approval** — the user opens `verification_uri` in any browser, authenticates
with the site's **own** login, and approves the requested scope for `client_id`.

**12.4.4 Token polling** (RFC 8628 §3.4) — `POST {token_endpoint}` (form-encoded):
```
grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=…&client_id=sensor-bridge&code_verifier=<pkce>
```
- **Success (200):**
  ```jsonc
  { "access_token": "…", "token_type": "Bearer", "expires_in": 3600,
    "refresh_token": "…", "scope": "ingest:write" }
  ```
- **Pending / control errors (400)** — the app handles these per spec: `authorization_pending`
  (keep polling), `slow_down` (increase interval by 5s), `access_denied` (user rejected),
  `expired_token` (restart the flow).

**12.4.5 Refresh** (RFC 6749 §6) — `POST {token_endpoint}` with
`grant_type=refresh_token&refresh_token=…&client_id=sensor-bridge`. The app refreshes
silently before expiry.

**12.4.6 Access-token validation at the ingest endpoint** — the ingest endpoint is a
**resource server**. Two accepted strategies:

- **JWT (stateless):** validate signature against `jwks_uri`, then check claims:

  | Claim | Check |
  |-------|-------|
  | `iss` | equals `{issuer}` |
  | `aud` | contains the resource identifier for this ingest API |
  | `exp` / `nbf` | not expired / already valid |
  | `scope` (or `scp`) | contains `ingest:write` |
  | `sub` | resolves to the subject the samples are stored under |

- **Opaque + introspection (RFC 7662):** `POST {introspection_endpoint}` → require
  `active: true`, then apply the same `aud`/`scope`/`sub` checks.

**12.4.7 Coexistence** — tier 1 and tier 2 can run side by side on the same ingest
endpoint during migration: if the bearer parses/validates as a JWT use §12.4.6, otherwise
fall back to the §12.3 hash lookup.

### 12.5 Provisioning deep link / QR (optional)

To enable one-tap setup, render a link the app scans (§7):

```
# Tier 1
beatsensor://sink?name=BeatDash&ingest=<url-enc ingestUrl>&metrics=heart_rate,calories&auth=token&token=<opaque>

# Tier 2
beatsensor://sink?name=BeatDash&ingest=<url-enc ingestUrl>&metrics=heart_rate,calories&auth=oidc&issuer=<url-enc issuer>&clientId=sensor-bridge&scope=ingest:write
```

Query params: `name` (label), `ingest` (ingest URL), `metrics` (csv of registry names),
`auth` (`token`|`oidc`), plus tier-specific fields. Unknown params ignored.

### 12.6 Idempotency, dedupe & retries

- The app delivers **at-least-once** and may resend overlapping windows (Health Connect
  especially). The backend **must** dedupe on `(subject, metric, recordedAt)` so replays
  are harmless.
- Backends SHOULD enforce this with a unique constraint on `(subject, metric, recordedAt)`
  and insert-or-ignore.
- `Idempotency-Key` (optional): if provided, a backend MAY short-circuit an exact retry of
  the same batch. Dedupe by key is an optimization; the `(subject, metric, recordedAt)`
  rule is the source of truth.

**Session-window filtering (keep-vs-discard).** The app bulk-pushes whatever it reads
(esp. Health Connect, incrementally — see §4.2); the backend decides what to keep. Two
policies:

- **Keep all, match on read (default).** Store every accepted sample; match to sessions by
  timestamp window at query time. Preserves out-of-session HR for resting/all-day context
  (e.g. BeatDash's 7-day average HR on `/health`).
- **Discard outside sessions at ingest.** Persist only samples that fall in a known
  activity window; drop the rest. Leaner storage, but loses resting/all-day context — keep
  a small daily HR rollup if you still want it. Choose per backend; it's not part of the
  wire contract.

### 12.7 Metric registry (canonical wire contract)

Backends route and validate by these names. Add rows as needed; keep names snake_case and
units canonical.

| `metric` (wire) | Canonical unit | Type | Suggested valid range | Notes |
|-----------------|----------------|------|-----------------------|-------|
| `heart_rate` | bpm | float32 | 20–240 | instantaneous HR |
| `calories` | kcal | float32 | 0–100 per sample | incremental burn for the sample interval |
| `steps` | count | float32 | 0–10000 per sample | incremental step count |
| `spo2` | percent | float32 | 50–100 | blood oxygen |

Samples failing the range are dropped per §12.2 step 4, not 400.

### 12.8 Subject model

Every accepted token (static or OAuth) resolves to exactly one **subject** (a user).
Samples are stored under that subject. The app never sends a user id — identity is carried
entirely by the token. This keeps the app oblivious to the backend's user model.

### 12.9 BeatDash implementation notes (non-normative)

- Generalize `POST /api/health/ingest/heartrate` → `POST /api/health/ingest` accepting the
  §12.2 envelope. Route `heart_rate` into the existing `HeartRateSamples` (reusing its
  window + dedupe logic), and map `calories`/`steps` onto the optional `CaloriesKcal` /
  `Steps` columns already on `HeartRateSample`. Keep the old HR route as a thin alias for
  one release.
- Current auth is tier 1: `HealthIngestTokenHash` (SHA-256, base64) on `User`, presented as
  `Authorization: Bearer` or `X-BeatDash-Health-Token`; the endpoint no-ops with
  `200 {accepted:0}` when `HealthTrackingEnabled` is false — this is the §12.2-step-2
  convention.
- Mint endpoint exists: `POST /api/health/ingest-token` (session-authed) → `{ token }` once.
- For tier 2, add JWT validation (§12.4.6) alongside the hash lookup; the existing dedupe
  unique index on `(UserId, RecordedAt)` becomes `(UserId, Metric, RecordedAt)` once more
  than one metric is stored in the same table.

---

## 13. Roadmap / effort (stop when it covers your devices)

| Step | Scope | Effort |
|------|-------|--------|
| 1 | Generalize BeatDash ingest → envelope endpoint | few hours |
| 2 | `core` + phone app: `HealthConnectSource` (incremental changes-token read + continuous-HR onboarding nudge, §4.2), `StaticTokenAuthenticator`, one sink, QR provisioning, WorkManager upload | 2–4 days (MVP) |
| 3 | `BleHeartRateSource` (chest straps + broadcast watches, incl. Garmin) | +1–2 days |
| 4 | `wear` module: `WearHealthServicesSource` — live/dense HR from the Galaxy Watch | +2–3 days |
| 5 | `OAuthDeviceAuthenticator` + BeatDash OIDC/JWT resource-server path (tier 2) | +2–3 days |
| — | (Deferred) vendor-cloud broker service | separate project |

For *your* Galaxy Watch FE the shortest path to dense live HR is steps 1 + 4 (or 1 + 2 if
post-session batch upload via Health Connect is acceptable).

---

## 14. Security notes

- App holds no client secret; it's a public OAuth client (PKCE).
- Tokens (static or refresh) stored in Keystore-backed encrypted storage, never plaintext.
- All sinks HTTPS-only; reject cleartext.
- Static tokens are long-lived — offer rotation on the backend and re-provision via QR.
- Backends should scope tokens to `ingest:write` only and validate `aud` so a token for
  one sink can't be replayed against another.

---

## 15. Open questions to confirm

1. Wear module egress: push **directly from the watch**, or **relay to the phone**? (Direct
   is simpler and phone-free; relay centralizes egress + retries.)
2. Metrics beyond HR you actually want v1 (calories, steps, SpO2)? Drives adapter scope.
3. Tier 1 forever for personal use, or is tier 2 (OAuth) a real requirement because you'll
   share it / open it up?
4. Local outbox store: Room vs DataStore vs a flat file — how much durability do you want
   for offline sessions?

# corvid-jvm

The Kotlin-first JVM binding for
[corvid](https://github.com/corvid-db/corvid) — an embedded database
with a typed C ABI. Java users consume the same artifact: the API is
plain Kotlin/JVM classes (`Db`, `Collection`, `Query`, …) usable from
Java unchanged. The binding is a thin **C JNI shim**
(`native/corvid_jni.c`, one file) compiled per-platform against the
engine's **published FFI artifacts** (the platform cdylib and
`corvid.h`, fetched + sha256-verified from the pinned release), with
the idiomatic Kotlin API on top — and it proves, continuously and
outside the engine repo, that the published artifacts drive a real JVM
consumer to the same verdicts the engine's own suite produces: the
golden-suite port in `GoldenTest.kt` replays the engine's 267-line
fixture suite through this binding.

**Documentation:** the [corvid docs site](https://corvid-db.github.io/docs/)
is canonical — the [C ABI section](https://corvid-db.github.io/docs/ffi/)
documents every symbol this binding links (handles, ownership, errors,
threading), and [docs/PLAN.md](docs/PLAN.md) records this binding's
architecture ruling, the JNI discipline, and the lifetime mapping.

**Installing:** Maven-Central-pending — consume from source (below)
until the publish rides an engine release cadence. A first Android
consumer request (or the publish) triggers the AAR packaging follow-up
ruled in [docs/PLAN.md](docs/PLAN.md); the API does not change for it.

## The architecture ruling: Kotlin/JVM via JNI, release artifacts only

Deliberately NOT JNA (per-call libffi/boxing overhead against the
engine's crossing-cost posture) and NOT Kotlin Multiplatform (one
platform here — the JVM). A hand-written JNI shim crosses with native
argument marshaling and decodes each document in ONE crossing per row.
Full reasoning, plus the nine-rule JNI discipline (local/global
references, no raw pointers in the public API, exception-pending
callback checks, the same-thread last-error guarantee), lives in
[docs/PLAN.md](docs/PLAN.md).

- **No Rust toolchain, ever.** `fetch.sh` / `fetch.ps1` download the
  pinned engine release archive for the host platform, sha256-verify it
  against the release's `checksums.txt`, byte-compare the release's
  golden fixtures against the ones vendored here, and normalize
  `corvid.h` + the cdylib into gitignored `deps/current/`.
  `scripts/build-native.sh` / `build-native.ps1` compile the shim with
  the platform C compiler (clang/gcc/MSVC). Requirements stop at "a JDK
  17+ and a C compiler".
- **One exact engine pin** — `v0.3.0`, living in one variable per fetch
  script (`CORVID_VERSION` in `fetch.sh`, `$CorvidVersion` in
  `fetch.ps1`), stamped into `deps/version.txt`.
- **No vendored binaries in git** (`deps/` is gitignored) and **no
  network at build time** beyond Gradle's own dependency resolution.
- **Published-artifact defects are findings**, never local patches.

## Quick start

Requirements: JDK 17+ (CI exercises 21 and 17), Kotlin 2.2.x /
Gradle 8.14+ (the wrapper is pinned), a C compiler, `curl` (or
PowerShell 5+ on Windows).

```sh
./fetch.sh                    # download + verify corvid v0.3.0
./scripts/build-native.sh     # compile the JNI shim into build/native
./gradlew test                # the golden suite (267 lines, 8 fixtures)
./gradlew examples            # the six-example tour
```

On Windows (PowerShell): `./fetch.ps1` then
`./scripts/build-native.ps1`, then the same Gradle tasks.

A taste of the API:

```kotlin
import corvid.*

fun main() {
    openMemory().use { db ->
        val docs = db.collection("docs")

        // Map<String, Any?> / List<Any?> / FloatArray / ByteArray /
        // String / Long / Double / Boolean / null — NaN and ±inf cross
        // bit-exactly (NaN payloads preserved; documented + pinned).
        docs.insert("p1".toByteArray(), mapOf(
            "name" to "ada",
            "v" to floatArrayOf(1f, 0f, 0f),
        ))

        docs.createVectorIndex("v", Metric.COSINE)

        // hybrid: filter + vector + text, RRF-fused, MMR-reranked
        val rows = docs.query()
            .filter(field("name").eq("ada"))
            .vector("v", floatArrayOf(1f, 0f, 0f), 3, Metric.COSINE)
            .select("name")
            .run()
        for (r in rows) println("${r.key.decodeToString()} ${r.doc} ${r.score}")

        val n = docs.query().filter(field("name").startsWith("a")).count()
        println("matched: $n")

        docs.close()
    }
}
```

Errors are `CorvidException` (a `RuntimeException` carrying the frozen
engine `ErrCode`) — thrown at the failing call site, including from
inside `update`/`scan` callbacks (a throwing callback aborts the engine
call, leaves the store untouched, and the exception surfaces at the
call site; PLAN ruling 6). `Db` and `Collection` are safe for
concurrent use from multiple threads (FFI §6: close only after every
concurrent operation on the handle has completed); `Query` and
`Predicate` are single-thread, build-once, consumed-by-the-terminal
objects. Every handle is `AutoCloseable`; `close()` is idempotent, and
by design there are no finalizer/Cleaner backstops making corvid calls
(PLAN ruling 7) — close deterministically (`use` does).

## Documents and maps

Every decode enumerates map keys through the engine's v0.3.0 map-key
iterator (`corvid_value_map_keys`, ascending key-byte order), so
`get` / `scan` / `page` / query rows decode documents COMPLETE on any
database, whatever wrote the data — no candidate-key oracle, ever
(PLAN "Map-key enumeration"). Decoded maps are `LinkedHashMap`s in
that engine order; `Float32Array` values decode to `FloatArray`,
bytes to `ByteArray`. The `VMAP_KEYS`/`GET_KEYS` golden lines pin the
order and the inert shapes op by op.

## What's inside

| Path | What it is |
| --- | --- |
| `fetch.sh` / `fetch.ps1` | Download the pinned release archive, verify (sha256) against `checksums.txt`, byte-check the golden fixtures, normalize into `deps/current/` |
| `native/corvid_jni.c` | The JNI shim — every line of C interop: the corvid.h link, the recursive UTF-8-safe document encoder/decoder, the scan/update callback bridges, the exception-pending checks |
| `src/main/kotlin/corvid/` | The Kotlin API (`Db`/`Collection`/`Query`/`Predicate`, the value mapping, `CorvidException` + `ErrCode`) over an `internal` long-handle JNI layer (`jni/Natives.kt`) |
| `src/test/kotlin/corvid/GoldenTest.kt` | The golden-suite port — 267 fixture lines through the binding, no softened asserts |
| `golden/` | The engine's golden fixtures, vendored byte-identical (verified against each release) |
| `examples/{Quickstart,Hybrid,VectorIndex,TextSearch,Graph,Geo}.kt` | The examples tour — one runnable `main` per concept, `./gradlew examples` on every CI leg: the README quickstart, hybrid RRF+MMR, the three vector-index families vs exact, BM25 incl. CJK + phrase search, graph + delete cascade, geo radius/bbox/nearest |
| `docs/PLAN.md` | The binding's plan: architecture ruling, JNI discipline, lifetime mapping, toolchain policy, the AAR follow-up |

## CI

A linux/macos/windows × JDK {21, 17} matrix
(`.github/workflows/ci.yml`): fetch + verify the pinned artifacts,
build the shim, `gradlew test` (the golden suite) + the examples tour
on every leg, plus the surface-gate job.

## Surface manifest (docs/SURFACE.tsv)

Every construct of the engine's public surface (the radar-enforced list
the engine publishes as `scripts/bindings/surface.tsv` at each release
tag) is resolved in `docs/SURFACE.tsv`: the Kotlin API exposing it plus
the test that proves it (golden fixture line references), or `N/A` +
reason where the v1 binding deliberately does not expose it.
`scripts/surface-gate.sh` fails CI when a line is unresolved, a cell is
empty, or the N/A count drifts from the committed baseline — so an
engine pin bump that changes the surface lands in this gate, not in a
user's bug report.

## Versioning

The engine pin lives in one variable in the fetch scripts
(`CORVID_VERSION=v0.3.0`). Artifacts always come from that exact tag's
GitHub release and are sha256-verified; `deps/` is never committed.

## License

MIT — see [LICENSE](LICENSE).

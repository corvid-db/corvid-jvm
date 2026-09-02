# corvid-jvm — the binding's plan

corvid-jvm is the **Kotlin-first JVM binding** for the `corvid` embedded
database (Java users consume the same artifact — the API is plain
Kotlin/JVM classes usable from Java unchanged). Like its siblings
`corvid-c`/`corvid-go`, it exists to prove, continuously and outside the
engine repo, that corvid's **published FFI artifacts** — the platform
cdylib, `corvid.h`, and the golden fixtures shipped in each release
archive — drive a real consumer to the same verdicts the engine's own
suite produces; on top of that proof it carries the idiomatic Kotlin API.

Engine repo: `corvid-db/corvid` (read-only upstream; never a submodule,
never vendored). Canonical docs: the corvid docs site's FFI section (the
`docs/FFI.md` contract — 124 symbols, frozen enums, §8 idiom gate).

## The architecture ruling: a thin C JNI shim over the typed C ABI, release artifacts only

**Kotlin/JVM library via JNI**: one C file (`native/corvid_jni.c`)
compiled per-platform against the fetched `libcorvid`, loaded from the
release artifacts; Kotlin API classes on top; Java users consume the
same artifact. Why this and not the alternatives:

- **NOT JNA.** JNA's per-call marshaling overhead (reflection-driven
  libffi dispatch, boxing, no per-call JNI fast paths) is exactly the
  crossing-cost posture the engine's BENCHES discipline exists to
  police; a hand-written JNI shim crosses with native argument
  marshaling and does document decode in ONE crossing per row, keeping
  the JVM binding honest against the same bar the Rust/C consumers set.
- **NOT Kotlin Multiplatform.** The engine is an in-process C ABI; there
  is exactly one platform here (the JVM). KMP's expect/actual machinery,
  common source sets, and metadata compilation would be ceremony without
  a second target — plain Kotlin/JVM + Gradle. (Kotlin/Native could talk
  to the C ABI directly, but then the artifact is not consumable by Java
  users and the Kotlin/C interop toolchain replaces one C file with a
  much larger klib story — overkill for this bootstrap.)
- **The C ABI is the engine's locked, stability-governed surface**
  (FFI.md §8): enum values frozen, symbols append-only, breaks are loud
  version bumps. Binding to it binds to the contract, not to Rust crate
  internals.
- **Consuming the release artifacts keeps this repo an independent
  verifier**: if a published dylib/header/fixture set disagrees with the
  spec, the golden suite here catches it (that is exactly how corvid-c
  found the v0.2.0 install-name defect, finding F1).

Consequences, all locked:

- **No Rust toolchain, ever.** `fetch.sh` / `fetch.ps1` download the
  pinned release archive for the host platform, sha256-verify it against
  the release's `checksums.txt`, extract into gitignored `deps/`, and
  normalize `corvid.h` + the cdylib into `deps/current/` (stable name so
  the native build flags stay platform-independent). `scripts/
  build-native.sh` / `build-native.ps1` compile the shim with the
  platform C compiler (clang/gcc/MSVC `cl`) — a toolchain every JVM
  developer already has alongside the JDK.
- **Pin EXACT engine tags.** One engine version at a time; today
  `v0.3.0`. The pin lives in exactly one variable per fetch script
  (`CORVID_VERSION` / `$CorvidVersion`) and is stamped into
  `deps/version.txt`.
- **No vendored binaries in git.** `deps/` and `build/` are gitignored.
- **No network at build time** beyond the toolchain's own dependency
  resolution (Gradle/Maven Central): the shim build consumes `deps/`
  only.
- **Published-artifact defects are findings, not patches.** Divergence
  is reported upstream (`corvid-db/corvid`), never worked around
  locally. The fetch scripts byte-compare the release's `golden/*.txt`
  against the fixtures vendored in this repo — a mismatch is a hard
  fetch failure.

### Follow-up (deliberately out of scope for this bootstrap)

**Android AAR bundling.** The artifact today is a JVM library plus a
per-platform JNI shim the consumer builds (or, post-publish, downloads).
An Android AAR that pre-bundles the `.so` files for `arm64-v8a`/
`x86_64` behind the same Kotlin API is a packaging change only — no API
or lifetime semantics move. Trigger: a first Android consumer request,
or the Maven Central publish (whichever comes first). Tracked here so
the decision is not re-litigated.

## The locked rule: golden port BEFORE ergonomic sugar

Inherited from the bindings program's master plan and non-negotiable:

> **A binding opens with the golden-suite port.** The engine's golden
> fixtures (267 executable lines across 8 files at v0.3.0) are the
> contract; a binding that wraps the ABI before it can replay the
> contract is building on unverified ground.

corvid-jvm's first substantive deliverable is `GoldenTest.kt` — a port
of the engine's harness (`c/smoke.c`, as ported standalone by
`corvid-c/test/golden.c` and `corvid-go/golden_test.go`) — replaying
every fixture line **through this binding** (the Kotlin API wherever it
can express the op, the JNI raw-handle layer where the op is inherently
raw — the value-family exercises). The fixtures are vendored
byte-identical under `golden/`. No softened asserts: the same
expectation checks, the same `executed == counted` dispatch rule, first
failure naming file:line + OP + expected-vs-got.

Only with the port green does the ergonomic surface count.

## The JNI discipline (this binding's core liability, ruled here)

JNI is the JVM's cgo, with the same two hazards the go binding already
solved once (its PLAN's cgo pointer-discipline and callback rulings,
adapted):

1. **Local/global reference lifetime.** Every JNI local reference a
   shim call creates is either returned to Kotlin (ownership transfers
   with the return), deleted before the call returns
   (`DeleteLocalRef` in loops; `PushLocalFrame`/`PopLocalFrame` around
   the recursive document decoder so unbounded documents cannot exhaust
   the per-thread local-ref table), or — for the two callback objects —
   legitimately outlives the C frame only because the callback runs
   synchronously inside that frame (the engine invokes §1.6 callbacks
   on the caller's thread, so the `jobject` argument's local reference
   is valid for the whole native call; no global refs are retained
   anywhere, so there is nothing to leak across calls).
2. **No raw pointers in the public API.** C pointers live only in
   `native/corvid_jni.c` and cross into Kotlin as `long` handles inside
   `corvid.internal.Natives` (an `internal` object). Every public API
   type (`Db`, `Collection`, `Query`, `Row`, …) wraps or converts; a
   Kotlin user never touches a handle. Handles returned as `long` are
   walked to exhaustion inside the single wrapper call (rows, geohits,
   group, schema, strs cursors) or owned by an API object with an
   explicit `close()` (`Db`, `Collection`, an abandoned `Query`, a
   never-consumed `Predicate`).
3. **Consumption is honored unconditionally (§8).** `pred and/or/not`,
   `query.filter`, `run`, aggregates, `array push`, `map put` consume
   their handles even when they fail. The Kotlin layer marks its side
   consumed regardless of the C result and never frees twice (a failed
   combine still took the children — mirrors go's ruling verbatim).
4. **Borrowed data is copied at the boundary (§5).** `rows_next` /
   `geohits_next` / strs keys / `_ref` views / callback arguments are
   BORROWED until the next `next`/`free` (or the parent's mutation).
   The shim copies every borrowed buffer into JVM-owned memory
   (`NewByteArray` + `SetByteArrayRegion`, `NewFloatArray`, …) inside
   the same native call that observed it; documents decode recursively
   inside the borrow window, in C, as ONE crossing per row. Nothing
   borrowed is ever retained past the call. Values crossing INTO the
   engine are built as fresh C values (the ABI clones what it keeps)
   and freed inside the same call; the caller's JVM objects are only
   ever read, never retained.
5. **Strings cross as UTF-8 bytes, never as JNI `jstring`s, on the
   engine side.** JNI's `GetStringUTFChars`/`NewStringUTF` speak
   *modified* UTF-8 (CESU-8 supplements, `C0 80` NUL) — not the real
   UTF-8 of FFI.md §1.5. The shim therefore converts `String` ↔ real
   UTF-8 via `String.getBytes(StandardCharsets.UTF_8)` and
   `new String(bytes, StandardCharsets.UTF_8)` (cached `jmethodID`s /
   charset object), and `jbyteArray` is the wire type for every path,
   name, phrase, relation, key, and error message. Map keys and group
   keys decode through the same helper.
6. **Exceptions in callbacks abort and then surface (go's
   recover-and-repanic ruling, JNI-shaped).** The §1.6 callbacks
   (`scan`'s sink, `update`'s closure) are Kotlin lambdas invoked from
   C through cached `invoke` method IDs. After every callback
   invocation the shim checks `ExceptionCheck`:
   - a pending exception ABORTS the engine call (the bridge returns
     the §1.6 abort value: `0` stops the scan, `CORVID_ERR` aborts the
     update — the engine then records `CORVID_E_ARGUMENT` with its
     abort message, leaving the store untouched), and
   - the native method then returns **without disturbing the pending
     exception**, so the JVM rethrows the user's own exception at the
     Kotlin call site. A Kotlin exception in a callback surfaces at the
     call site; it never crosses C frames as an unwind (which would be
     UB), and it is never swallowed.
   The golden port's `UPDATE_ABORT` line pins BOTH halves: the thrown
   marker exception must surface, AND the engine's abort must be
   readable afterwards via the same-thread `lastError` slot (code 12 +
   non-empty message).
7. **The same-call error guarantee (§3).** The engine's last-error slot
   is THREAD-LOCAL; a native method runs on the calling Java thread, so
   the failing call and the `nLastErrorCode`/`nLastErrorMessage` read
   made by the wrapper immediately afterwards are on the same thread by
   construction — the exposure go had to argue down to "theoretical
   minimum" (goroutine migration) does not exist here. One
   precondition keeps it absolute: **no finalizer/Cleaner on the JVM
   side ever issues corvid calls** (there are none — handles are
   `close()`d explicitly, idempotently; the docs say why), so nothing
   can interpose a corvid call between the failure and the read. This
   is documented as the binding's same-call guarantee.
8. **Threading (§6).** `Db` and `Collection` are safe for concurrent
   use from multiple threads (engine-backed `Arc`; reads concurrent,
   writes serialized); `Query`, `Predicate`, and cursors are
   single-thread objects (concurrent use of one handle is UB per the
   ABI) — documented, not policed, exactly like the engine. The API is
   synchronous and blocking (the engine is); coroutines/`suspend`
   wrappers are out of scope for v1.
9. **Handles close explicitly and idempotently.** Every owner exposes
   `close()` (a second `close()` is a no-op; use after `close()` throws
   `IllegalStateException`). No `Cleaner`/finalizer backstops issue
   corvid calls (ruling 7) — determinism is the user's job and the
   golden harness exercises every free path.

## C-handle lifetime mapping (FFI.md §2 → Kotlin)

| C handle | Kotlin owner | Explicit release | Backstop |
|---|---|---|---|
| `corvid_db` | `Db` | `close()` (idempotent) | none (by ruling 7/9) |
| `corvid_coll` | `Collection` | `close()` (idempotent) | none |
| `corvid_value` (owned) | transient inside a call, or decoded-then-freed | freed deterministically at end of the wrapper call | not needed |
| `corvid_pred` | `Predicate` | consumed by `and`/`or`/`not`/`filter`/`deleteWhere`; `close()` frees a never-consumed root | none |
| `corvid_query` | `Query` | consumed by `run`/every aggregate; `close()` frees an abandoned builder | none |
| cursors (`rows`, `strs`, `geohits`, `groupiter`, `schemaiter`) | walked to exhaustion inside the single wrapper call | freed before the wrapper returns | not needed |
| buffers (`insert_auto` key, `page` next-after) | copied to JVM memory (`ByteArray`) | `corvid_free`'d in the shim | not needed |

## Value mapping (FFI.md §1.4 → Kotlin)

| C value | Kotlin type (decode) | accepted on encode |
|---|---|---|
| Null | `null` | `null` |
| Bool | `Boolean` | `Boolean` |
| Int (i64) | `Long` | `Long`, `Int`, `Short`, `Byte` (widened) |
| Float (f64) | `Double` — **NaN/±inf/-0.0 cross bit-exact; NaN payloads preserved** (documented, golden-pinned) | `Double`, `Float` |
| Text | `String` (real UTF-8 both ways — ruling 5) | `String` |
| Bytes | `ByteArray` | `ByteArray` |
| Vector | `FloatArray` (f32 elements bit-exact) | `FloatArray` |
| Array | `List<Any?>` | any `List` |
| Map | `LinkedHashMap<String, Any?>` (insertion order = the engine's ascending key-byte iteration order, via `corvid_value_map_keys`) | any `Map` with `String` keys |

Documents are Maps; query rows decode the full document (this binding
does not mirror go's `Doc == nil`-without-`select` optimization — the
JNI decode is one crossing per row, so the saving would be smaller than
the surprise; noted as a possible later divergence with its own
reasoning). `phraseSearch` rows always carry documents.

**Encode depth cap**: both encode paths (the C `encode_value` for
whole documents, `Values.encode` for the handle/putMany path) cap
container nesting at the engine's decode bound,
`corvid::value::MAX_NESTING` (128) — mirrored as
`CORVID_JNI_MAX_NESTING` (native/corvid_jni.c) and `Values
.MAX_NESTING`. Converter-accepted == decodable: a deeper graph could be
BUILT through the constructor ABI but the engine could never decode it
back (dump/load), so it is rejected up front with
`CorvidException(ErrCode.ARGUMENT)` — which, on the JNI path, also
stops the C recursion before an uncapped Kotlin list can smash the
native stack. The boundary is the engine decoder's own (top-level =
depth 0, boundary inclusive): 128 nested containers round-trip, 129
throws (DepthCapTest).

## Map-key enumeration

No oracle, ever: v0.3.0's `corvid_value_map_keys` (OWNED strs cursor,
ascending key-BYTE order; non-maps an EMPTY cursor, inert) is the only
key source. The C decoder enumerates keys through the real iterator, so
every read path (`get`, `scan` callbacks, `update` callbacks, query
rows, geo docs) decodes COMPLETE on any database, whatever wrote the
data. The `VMAP_KEYS`/`GET_KEYS` golden lines pin the order and inert
shapes op by op.

## Toolchain policy (scripts/bindings/README.md, engine repo)

Modern minimums, CI rides current lines:

- **Kotlin**: 2.2.x (current stable line at bootstrap; `kotlin("jvm")`
  Gradle plugin).
- **JVM**: LTS matrix **21 + 17** — bytecode target 17 (the floor;
  still-LTS), CI legs on 21 (primary) and 17 (floor). A 25/26 leg can
  be added when 25 is the LTS-of-record and the matrix needs it; no EOL
  toolchains.
- **Gradle**: 8.14+ (wrapper-pinned; runs on 17 and 21).
- **C compiler**: the platform default (`cc`/`clang` on macOS, `cc` on
  Linux via gcc or clang, MSVC `cl` on Windows) — plus a JDK whose
  `include/` the shim builds against.
- No nightly/beta toolchains anywhere.

## Phase JVM1 (this bootstrap) — scope

1. **Plan doc** (this file) — architecture ruling, the JNI discipline,
   lifetime table, value mapping, toolchain policy, the AAR follow-up.
2. **Repo scaffold** — Gradle Kotlin/JVM project (`corvid-jvm`), MIT
   LICENSE (engine's copyright line), `.gitignore` (`deps/`, `build/`),
   `.gitattributes` (`* -text` — the fixture byte-compare requires it),
   README (usage + requirements: artifacts NOT vendored, install
   Maven-Central-pending).
3. **Fetch + verify** — `fetch.sh` / `fetch.ps1` (the corvid-c/go
   pattern) with the vendored `golden/` byte-verified against the
   release.
4. **The JNI shim** — ONE C file (`native/corvid_jni.c`) plus per-OS
   build scripts: every corvid.h symbol the API needs, the recursive
   UTF-8-safe document encoder/decoder, the two §1.6 callback bridges,
   the §7 no-op/null-free exercise hooks. No business logic.
5. **The Kotlin API** — `corvid` package: `open`/`openMemory`,
   `Db.collection(s)`/dump/load(+renames)/backup/compact/close,
   `Collection` full surface (mutations, reads, TTL, graph, geo,
   indexes, schema, phraseSearch), fluent `Query` (filters, sources,
   fusion, terminals), `Field(path)` predicate DSL, `CorvidException`
   with `ErrCode`, value mapping per the table above.
6. **The golden port** — `GoldenTest.kt`: 267 executable fixture lines
   through the binding, first failure named per file:line, dispatch
   count enforced, `SMOKE <file> lines=<n> executed=<n>` protocol.
7. **6 examples, CI-run** — quickstart / hybrid / vector-index /
   text-search (incl. phraseSearch) / graph / geo, deterministic
   output.
8. **CI** — linux/macos/windows matrix: fetch+verify, build shim,
   Gradle test (golden suite) + examples tour; a JVM 17 floor leg; the
   surface-gate job. Under ~8 minutes.

   **The deferred sanitizer leg, and why** (recorded here, not only in
   the engine-side process report): the engine's own CI runs the
   cdylib + its C smoke suite under ASan/UBSan/LSan on Linux, so the
   engine side of the seam — where the memory liability concentrates —
   is sanitizer-covered; the shim's marshal surface is exercised line
   by line by the golden suite on every leg. A binding-side sanitizer
   leg on `gradlew test` would mean running the JVM itself under
   ASan/LSan: the JVM runtime's own native allocations drown LSan, and
   the suppression files that silence them are broad enough to also
   mask genuine shim leaks — a leg that cannot fail for the right
   reasons. Deferred until there is a shim-only harness (C-level unit
   tests of the marshal layer, no JVM in the process) or a suppression
   story that provably cannot hide shim defects; until then the
   compile is `-Wall -Wextra` clean and every free path is golden-
   exercised.

Out of scope for JVM1: coroutine wrappers, Android AAR (documented
follow-up above), Maven Central publish (prepared posture only —
publish rides the engine's release cadence like the other non-npm/PyPI
bindings until the maintainer pulls the trigger).

## Verdict protocol

Same as corvid-c/go's: the golden suite logs one
`SMOKE <file> lines=<n> executed=<n>` line per fixture; green means
every expectation of every executable line passed and the dispatch
count matches the pre-scan count. Divergence from the engine-side
suite's verdicts is a defect here; divergence of the artifacts from the
engine repo is a finding for the engine repo.

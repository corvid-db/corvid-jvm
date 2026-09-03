# Maven Central setup — the one-time USER checklist

This binding's publish pipeline is **ready**: the tag-driven workflow
(`.github/workflows/release.yml`) assembles, signs, and uploads the
bundle. What it cannot do for you is the one-time identity setup —
Central Portal has **no OIDC/trusted publishing** (verified, 2025), so
publishing irreducibly needs a **portal user token** and a **GPG-signed
artifact set**. This page is the exact checklist; do it once and every
`vX.Y.Z` tag afterwards publishes on its own.

Everything below happens in two places: the
[Central Portal](https://central.sonatype.com) (account, namespace,
token) and this repository's settings (four secrets). Nothing touches
the build files.

## 1. Central Portal account

Sign up at <https://central.sonatype.com> — "Sign in with GitHub" using
an account that owns/admins the `corvid-db` organization.
(Official docs: [Registering a new account](https://central.sonatype.org/register/central-portal/).)

## 2. Verify the namespace `io.github.corvid-db`

The published coordinates are `io.github.corvid-db:corvid-jvm`, and a
namespace must be **verified** before Central accepts uploads to it.
GitHub sign-up auto-verifies only `io.github.<username>` — an
**organization** namespace like ours is added and verified manually:

1. Portal → your username (top right) → **View Namespaces** →
   **Add Namespace** → enter `io.github.corvid-db` → **Submit**.
2. Click **Verify Namespace** → **Confirm**. The request moves to
   "Verification Pending" and a **Verification Key** is assigned.
3. Prove org ownership the code-hosting way: create a **public**
   repository named exactly the Verification Key under the org —
   `github.com/corvid-db/<verification-key>` (an empty repo is fine).
4. Refresh the Namespace page until the status reads **Verified**
   (minutes). The temporary repo can be deleted afterwards.

(Official docs: [Namespace verification](https://central.sonatype.org/register/namespace/).
If the portal refuses the org-namespace verification path, email
central-support@sonatype.com — they resolve these routinely.)

## 3. Generate a user token

Portal → your username → **View Account** → **Generate User Token**.
You get a token **name** and a token **value** (both shown once — save
them). These become the first two repo secrets below.
(Official docs: [Generating a portal token](https://central.sonatype.org/publish/generate-portal-token/).)

## 4. Create + distribute the GPG signing key

Central requires every artifact to carry a GPG **detached signature**
made with a key whose public half is on a keyserver.
(Official docs: [GPG requirements](https://central.sonatype.org/publish/requirements/gpg/).)

```sh
gpg --full-generate-key              # RSA and RSA, 4096 bits, your identity
gpg --list-secret-keys --keyid-format long   # note the key id (e.g. 3AA7C5C1...)
gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>
gpg --export-secret-keys --armor <KEYID>    # the ARMORED PRIVATE KEY block
```

The exported block (from `-----BEGIN PGP PRIVATE KEY BLOCK-----`
through `-----END PGP PRIVATE KEY BLOCK-----`, trailing newline
included) and the key's passphrase become the last two secrets.

## 5. Add the four repository secrets

GitHub → `corvid-db/corvid-jvm` → **Settings → Secrets and variables →
Actions → New repository secret** — create these four, names verbatim
(these are the exact names `.github/workflows/release.yml` reads):

| Secret name | Value |
|---|---|
| `CENTRAL_PORTAL_TOKEN_NAME` | the user token's **name** (step 3) |
| `CENTRAL_PORTAL_TOKEN` | the user token's **value** (step 3) |
| `ORG_GRADLE_PROJECT_signingKey` | the armored GPG **private** key block (step 4) |
| `ORG_GRADLE_PROJECT_signingPassword` | the GPG key's passphrase (step 4) |

The GPG pair uses the standard Gradle env wiring — the workflow exports
them as `ORG_GRADLE_PROJECT_signingKey` / `ORG_GRADLE_PROJECT_signingPassword`,
Gradle surfaces them as project properties, and the build signs with an
in-memory key (`signing { useInMemoryPgpKeys(...) }` in
`build.gradle.kts`). No key ever lands on disk in CI.

Until these exist, a tag push still runs the native builds but the
publish job **skips with a loud green notice** pointing at this page —
an unconfigured repo is never a red X.

## 6. Releasing

The binding version rides the engine's **release cascade**: engine tag
`vX.Y.Z` → `io.github.corvid-db:corvid-jvm:X.Y.Z`.

1. Bump the engine pin (PR changing `CORVID_VERSION` in `fetch.sh`,
   `$CorvidVersion` in `fetch.ps1`, **and** the `<version>` coordinates
   in README.md's Installing section) and merge — the normal pin-bump
   PRs (#2–#5) are the template. The release gate verifies all three
   against the tag and fails loudly naming whichever lags. The cascade
   tooling (`scripts/bindings/bump.sh` in the engine repo) rewrites
   all three automatically — including the bare README coordinates
   (Maven `<version>` literals and Gradle `:X.Y.Z` tails) — so the
   standard `bump.sh vX.Y.Z → --merge-when-green →
   --release-after-merge` flow publishes corvid-jvm with zero manual
   steps.
2. Tag and push: `git tag vX.Y.Z && git push origin vX.Y.Z` (the tag
   must **equal** the pin; the workflow verifies this and fails with an
   explanation otherwise). `-Pversion` is derived from the tag, which
   the workflow passes to Gradle.
3. What the workflow does: five platform legs fetch (sha256-verified)
   and compile the C-only JNI shim (macos x64 via `cc -arch`, linux
   arm64 via the distro cross-gcc — compile-only legs); the publish job
   downloads all five pairs, Gradle assembles + signs the bundle into
   `build/staging`, and `spring-io/central-publish-action` uploads it
   as a deployment.
4. The upload uses `publishing-type: automatic`: Central validates the
   bundle (checksums, signatures, POM, javadoc/sources presence) and
   publishes it — no portal click needed. (The first release used
   `user_managed` for a human-confirmed publish; flip the workflow
   input back if you want that gate again.)
5. After release, the artifacts serve from
   `repo.maven.apache.org` within ~10–30 minutes; the search index
   lags a bit longer.

**First release published 2026-09-03** (`0.4.0`). The three failures
on the way there are now permanently encoded in the pipeline: the gate
shape-diagnoses the signing-key secret (a mangled paste failed at
Gradle after the matrix), the upload runs with
`fail-on-existing-checksums: false` (Gradle's staging checksums vs the
action's — semantics verified from the action's ChecksumPolicy
source), and Gradle's `maven-metadata.xml` is stripped from the
staging tree before upload (Central rejects content at the no-version
level). Consumer-verified end to end from a scratch Gradle project
resolving only from Central.

## What was deliberately NOT automated

- **No credentials in the repo.** The workflow reads only the four
  secret names above; their values never appear in code, logs, or the
  build.
- **No snapshot publishing.** Only real `vX.Y.Z` tags publish (the
  engine's own cadence; `publishToMavenLocal` covers local dry runs).
- **The consumer story needs nothing else.** Published jars are
  self-contained: the platform classifier jar bundles the JNI shim and
  the engine cdylib, and the loader extracts + `System.load()`s them
  from the classpath (see README "Installing"). Since the Android
  program, the SAME bundle/upload also carries
  `io.github.corvid-db:corvid-android` — one AAR with both ABI pairs
  as jniLibs; the namespace/verification setup above covers it
  unchanged (same `io.github.corvid-db` group).

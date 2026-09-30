# Suwayomi-ext-runtime

Extension runtime: a **shared source tree** (Mihon/Tachiyomi extension API implementation + sandbox routing/driver)
plus a **desktop JVM sandbox host**. Split out of
[`576576/Suwayomi-next`](https://github.com/576576/Suwayomi-next); it talks to the Rust server
**only** through an **HTTP + JSON contract**.

> English counterpart of the root [`README.md`](../../README.md). The Chinese version is authoritative —
> if the two diverge, follow the Chinese one.

Current AOSP public API baseline: **API 36** (pinned in
[`ext-runtime/android-stub/android-stub.properties`](../../ext-runtime/android-stub/android-stub.properties);
changing the baseline means editing only that file).

## Build

```bash
./gradlew build   # compile + test
./gradlew jar     # fat jar -> build/libs/ext-runtime.jar
```

Requires **JDK 25**. The first build downloads the public AOSP API package (~52 MB) to generate
`android-stub`; use `-PaospPackageUrl=<mirror>` to switch sources.

To trim a JRE (for `+jre` desktop packages / Docker):

```bash
bash scripts/make-jre.sh windows x64 /tmp/jre   # <windows|linux|mac> <x64|aarch64> <output dir>
```

## Versioning

`<AOSP API level>.{commit count / 100}.{commit count % 100}`;
`versionCode = commit count of this repo + 1000`.

The major version follows the API baseline of `android-stub` — `release.yml` takes
`aospApiLevel` straight from the pin as the major version, so bumping the pin moves it automatically.
The last two segments are this repo's commit counter.

## Artifacts and consumption

Pushes to `main` or manual dispatch publish to **GitHub Release assets** (no auth needed) and
**GitHub Packages**.

| Channel | tag | Trigger |
| --- | --- | --- |
| release | `v<version>` | manual dispatch |
| beta | `v<version>-beta.<run_id>` | manual dispatch |
| alpha | `<version>-alpha.<run_id>` | automatic on push to `main` (jar and two JREs only, no Packages) / manual dispatch |

| Artifact | Purpose |
| --- | --- |
| `ext-runtime-<V>.jar` | desktop / server / Docker, deployed as `<release root>/bin/ext-runtime.jar` |
| `ext-runtime-<V>-shared-sources.jar` | unpacked by the Android `extension-host` and compiled as an extra source root |
| `ext-runtime-jre-<V>-<os>-<arch>.tar.gz` ×6 | `+jre` desktop package / Docker image |

Consumers use **Release assets**. Packages coordinates (needs a PAT with `read:packages`):

```
https://maven.pkg.github.com/576576/Suwayomi-ext-runtime
com.github.576576.suwayomi-ext-runtime:ext-runtime:<version>
```

The Android side can only consume sources: this repo builds with Kotlin 2.4.0, and the Kotlin 2.3.20
bundled with AGP cannot read 2.4 metadata.

## Relationship with Suwayomi-next

The runtime contract is stable (routes, JSON shapes, main class `sandbox.MainKt`, file name
`bin/ext-runtime.jar`). For local debugging, point `SUWAYOMI_SANDBOX_JAR` at
`build/libs/ext-runtime.jar` — no release needed.
Changes under `src/shared/` → tag and release in this repo → downstream resolves the new version
automatically; if a new JVM module dependency is added, update the module allowlist in
`scripts/make-jre.sh` accordingly.

## License

MPL-2.0 (see [`LICENSE`](../../LICENSE)). Third-party origins: AndroidCompat stubs (MPL-2.0),
Mihon/Tachiyomi extension API shapes (Apache-2.0, Copyright 2015 Javier Tomás),
public AOSP API (Apache-2.0).

## Documentation

`docs/agent/` holds maintainer-oriented working notes (including for AI agents), not public docs.
Contract docs (what things are, what to watch out for) live in `docs/agent/`; plans and research in
`docs/agent/plans/`. Each level has its own index (`docs/agent/README.md`, `docs/agent/plans/README.md`):

- [`docs/agent/sandbox.md`](../agent/sandbox.md) — sandbox loading / translation / driving: current
  behaviour and pitfalls (Chinese)
- [`docs/agent/reference-implementations.md`](../agent/reference-implementations.md) — where this repo
  deviates from the two reference implementations (Suwayomi-Server / Mihon), and why `org/json` /
  `quickjs` have no swap-in Kotlin replacement (Chinese)
- [`docs/agent/plans/extraction-plan.md`](../agent/plans/extraction-plan.md) — extraction work plan (Chinese)
- [`docs/agent/plans/extraction-record.md`](../agent/plans/extraction-record.md) — what the extraction
  actually did: directory layout, JRE ownership, acceptance matrix (Chinese)
- [`docs/agent/plans/api-baseline-upgrade.md`](../agent/plans/api-baseline-upgrade.md) — one-off record
  of the Android public API baseline bump (Chinese)
- [`docs/agent/plans/java-kotlin-survey.md`](../agent/plans/java-kotlin-survey.md) — Java → Kotlin
  migration and Rust-side extraction survey (Chinese)
- [`docs/agent/plans/rust-handoff.md`](../agent/plans/rust-handoff.md) — config handed off to Rust (done)
  + ownership evaluation for SQLite and preference persistence (Chinese)
- [`docs/agent/plans/stub-slimming.md`](../agent/plans/stub-slimming.md) — keeping unused libraries out
  of the final jar: exclusion sets, savings, verification gate (Chinese)

# Suwayomi-ext-runtime

Extension runtime: a **shared source tree** (Mihon/Tachiyomi extension API implementation + sandbox routing/driver)
plus a **desktop JVM sandbox host**. Split out of
[`576576/Suwayomi-next`](https://github.com/576576/Suwayomi-next); it talks to the Rust server
**only** through an **HTTP + JSON contract**.

> English counterpart of the root [`README.md`](../../README.md). The Chinese version is authoritative —
> if the two diverge, follow the Chinese one.

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

`<AOSP API level>.{commit count / 100}.{commit count % 100}`, e.g. `30.0.47`;
`versionCode = commit count of this repo + 1000`.

The major version still follows the API baseline of `android-stub` — `release.yml` takes
`aospApiLevel` straight from the pin as the major version, so bumping the pin moves it automatically.
The last two segments are this repo's commit counter.

## Artifacts and consumption

Pushes to `main` or manual dispatch publish to **GitHub Release assets** (no auth needed) and
**GitHub Packages**.

| Channel | tag | Trigger |
| --- | --- | --- |
| release | `v30.0.47` | manual dispatch |
| beta | `v30.0.47-beta.<run_id>` | manual dispatch |
| alpha | `30.0.47-alpha.<run_id>` | automatic on push to `main` (jar and two JREs only, no Packages) / manual dispatch |

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

`docs/agent/` holds maintainer-oriented working notes (including for AI agents), not public docs:

- [`docs/agent/EXTRACTION_PLAN.md`](../agent/EXTRACTION_PLAN.md) — extraction work plan (Chinese)
- [`docs/agent/EXTRACTION_RECORD.md`](../agent/EXTRACTION_RECORD.md) — decisions and trial-and-error log
  (directory layout, JRE ownership, etc.) (Chinese)
- [`docs/agent/SANDBOX_DETAILS.md`](../agent/SANDBOX_DETAILS.md) — measured details and pitfalls of
  sandbox loading / translation / driving (Chinese)
- [`docs/agent/API36_UPGRADE.md`](../agent/API36_UPGRADE.md) — Android public API baseline 30 → 36:
  measured results and upgrade notes (Chinese)
- [`docs/agent/JAVA_KOTLIN_SURVEY.md`](../agent/JAVA_KOTLIN_SURVEY.md) — Java → Kotlin migration and
  Rust-side extraction survey (Chinese)

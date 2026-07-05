# Mango app template

Android app template, builds with Gradle on GitHub.

## Using this template for a new app

Edit `app.properties` at the repo root.

## No source, black screen on launch — no custom Activity needed

There is no `MainActivity.kt` in this template, and none is required for it to launch cleanly. The manifest's launcher `<activity>` points directly at `androidx.activity.ComponentActivity` — the base class every Compose Activity ultimately extends, already a dependency here, concrete and instantiable as-is. Its default `onCreate()` does nothing beyond standard lifecycle setup, so you get a blank screen (black, via `Theme.App`'s `windowBackground`) with no crash and no placeholder file to write, delete, or relocate.

When you're ready for real UI:
1. Create your own Activity under `app/src/main/kotlin/<your app.package path>/` (or `java/`, either works)
2. In `AndroidManifest.xml`, swap `android:name="androidx.activity.ComponentActivity"` for your own, package-relative form, e.g. `android:name=".MainActivity"`

## Versioning

`versionCode`/`versionName` are computed in CI, not hardcoded:
- **Release** (GitHub release created): `versionName` = the release tag name, `versionCode` = the CI run number
- **Push** (branch, e.g. `master`): `versionName` = the branch name (slashes replaced with `-`)
- **Pull request**: `versionName` = the PR's source branch name
- Every build artifact also gets a short commit SHA appended to its filename for disambiguation

Locally (`gradle assembleDebug` with no CI flags), it falls back to `app.version` from `app.properties` and `versionCode = 1` — fine since local builds are never uploaded anywhere.

## Building

```
gradle assembleDebug
```

Requires JDK 17 and Android SDK. CI via GitHub Actions on every push, PR, and release; the Gradle wrapper is generated fresh in CI rather than committed.

# GenericFitness-Customer

Generic Fitness Customer Android application (source snapshot v1.0.31).

**Repository repurposed from XGrid. The previous XGrid files were cleared from the default branch.**

## Import needed before builds

The actual Generic Fitness Android source and CI workflows are prepared in the Store Library package **GenericFitness-Customer-GITHUB-READY.zip**. The GitHub connector cannot transfer this binary source ZIP into the repository, so this commit is only the clean replacement repository shell. **No working Generic Fitness APK has been compiled in this GitHub repository yet.**

Extract GenericFitness-Customer-GITHUB-READY.zip and upload its entire contents at the repository root. The package contains `app/`, `tools/`, `build.gradle.kts`, `settings.gradle.kts`, `gradlew`, `gradle/` and `.github/workflows/` for debugging and signed releases.

**Important:** The existing repository remains public unless you change Visibility in GitHub Settings. Do not upload release signing keys, passwords, production tokens or customer data.

To rename the repository, in GitHub open **Settings → General → Repository name** and enter **GenericFitness-Customer**. Renaming cannot be performed by this connector.

## Release checklist

1. Import source code without secrets.
2. Run GitHub Actions Android CI; investigate any build failures.
3. Verify real phone, UI, backend permissions, workout saves and payment-to-access integration.
4. Configure Android signing secrets for a stable release keystore.
5. Build APK/AAB; perform real release tests. No zero-error guarantee can be made before those tests.

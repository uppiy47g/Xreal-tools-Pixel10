# Contributing to Hand Mouse

Thanks for your interest! This is a hobby project, but contributions — bug reports, fixes, and features — are very welcome.

## How contributions work

- The `main` branch is protected: everything lands through **pull requests**, and only the maintainer merges them.
- To contribute code: **fork** the repo, create a branch, and open a **pull request** against `main`.
- For anything non-trivial, please **open an issue first** to discuss the approach — it saves everyone time.

## Building & testing

See the [README](README.md#building-from-source). In short:

```bash
cd xreal-hand-mouse
./gradlew assembleDebug   # build the APK
./gradlew test            # run the unit tests
```

Please make sure the build and the tests pass before opening a PR. Much of the logic (gesture detectors, filters, protocol codecs, frame assembly) is covered by unit tests — if you change that logic, add or update tests.

## ⚠️ No proprietary or decompiled code

This is the most important rule, and it is non-negotiable. This project is deliberately **free of any proprietary XREAL code**, and it must stay that way:

- **Do not** add proprietary XREAL/Nreal native libraries (`.so`), firmware blobs, or their assets.
- **Do not** add decompiled, disassembled, or copied code from XREAL's apps, SDKs, or any other third party's closed source.
- Work on the glasses USB protocol must come from **your own black-box observation of your own hardware** (interoperability / clean-room), not from reverse-engineering someone else's binary.
- If you adapt code from another open-source project, it must be under a compatible permissive license, and you must add proper attribution to [`NOTICE`](NOTICE).

PRs that introduce proprietary or decompiled material will be rejected — it would put the whole project's license at risk.

## Coding style

- **Kotlin** for app code; keep changes consistent with the surrounding file.
- **User-facing UI strings are in English** (see `strings.xml`). New UI text should be English.
- Code comments in the existing files are largely in Portuguese (project history); new comments in English are perfectly fine — just keep them clear and match the local file where it helps readability.
- Prefer small, focused PRs with a clear description of the change and why.

## Reporting bugs

Open an issue with:

- What you did, what you expected, and what happened.
- Your hardware (glasses model, phone model, Android/One UI version).
- Relevant logcat output if you have it — the app's LOG panel and the [Troubleshooting](README.md#troubleshooting--qa) section are good starting points.

## Licensing of contributions

By submitting a contribution, you agree that it is licensed under the project's [Apache License 2.0](LICENSE) — the same terms as the rest of the project (as described in Section 5 of the license).

---

This project is an independent, unofficial work and is **not affiliated with XREAL**. "XREAL" and "Nreal" are trademarks of their respective owner.

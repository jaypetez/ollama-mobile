<!--
Delete sections that genuinely do not apply, but do not delete the checklist.
Small PRs get reviewed quickly; a 2000-line PR will sit.
-->

## Summary

<!-- What this change does, in one or two sentences. -->

## Motivation / context

<!--
Why this change exists. Link the issue if there is one (`Fixes #123`).
If the change is not obviously correct, explain the alternative you rejected
and why — that is usually the part a reviewer cannot reconstruct.
-->

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Performance
- [ ] Refactor / internal cleanup (no behaviour change)
- [ ] Build, CI, or tooling
- [ ] Documentation
- [ ] Native / llama.cpp integration (`core-llm`, `third_party`)

## How it was tested

- [ ] `./gradlew test` (unit tests) pass locally
- [ ] Exercised on the x86_64 emulator
- [ ] Exercised on a physical arm64 device — *none available to this project; leave unchecked*
- [ ] Tested against a real remote Ollama server (state the server version and host below)
- [ ] Tested with `-Pollama.nativeSource=none` (the default: stub engine, remote-only)
- [ ] Tested with `-Pollama.nativeSource=build` or `=prebuilt` (requires an NDK and the llama.cpp submodule)
- [ ] Not testable in this repo's current setup — explain below

<!--
Detail what you actually ran: emulator API level, model + quant, server version,
which `ollama.nativeSource` mode. Do not quote tokens/sec as a verified figure —
there is no reference device, so numbers from an emulator or a dev laptop are
indicative only and must be labelled as such.
-->

## Screenshots / recordings

<!--
Required for any user-visible UI change; a before/after pair is ideal.
Omit this section entirely for non-UI changes rather than writing "N/A".
-->

## Breaking changes

<!--
Anything a user or an integrator would notice: changed persisted schema
(Room migration needed?), changed settings keys, changed HTTP surface of the
on-device Ollama-compatible server, changed Gradle properties or module
boundaries. Write "None" if there are none — that is a claim a reviewer checks.
-->

## Checklist

- [ ] `./gradlew spotlessCheck lintDebug test checkModuleGraph` is green locally
      (`./gradlew spotlessApply` fixes formatting; `detekt` is advisory and may fail)
- [ ] Every commit is signed off (`git commit -s`) — the DCO is required by
      [CONTRIBUTING.md](../CONTRIBUTING.md); `git rebase --signoff main` fixes a branch you already wrote
- [ ] New/changed behaviour is covered by tests, or I have said why it is not
- [ ] Docs updated where the change makes the existing text wrong
- [ ] No telemetry, analytics, crash-reporting SDK, or new phone-home network call
      was introduced — this is a hard project rule, including transitive dependencies
- [ ] No new dependency added without a reason in the description, and its licence is compatible with MIT
- [ ] Attribution preserved for any vendored code touched (llama.cpp is MIT, "Copyright (c) 2023-2024 The ggml authors")

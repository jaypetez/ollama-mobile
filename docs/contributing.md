# Contributing

The contribution guide lives in the repository root, not here:

**[CONTRIBUTING.md](https://github.com/jaypetez/ollama-mobile/blob/main/CONTRIBUTING.md)**

It is the single source of truth for the pull-request workflow, commit
conventions, code style and review expectations. It is not duplicated on this
site, because two copies of a process document means one of them is wrong and
nobody knows which.

GitHub also surfaces the root file automatically when someone opens an issue or
a pull request, which is where a contributor actually is when they need it.

## What is here instead

The pages on this site cover the things a contributor needs that are too long
for a contribution guide:

- **[Installation](getting-started/installation.md#build-from-source)** —
  toolchain versions and how to build. The short version: JDK 21, an Android SDK
  with `platforms;android-37.0`, and `./gradlew assembleDebug`. No NDK.
- **[Module map](architecture/module-map.md)** — the thirteen modules, the three
  layering rules `checkModuleGraph` enforces, and how to add a module.
- **[CI](ci.md)** — what gates a merge and what does not.
- **[Architecture overview](architecture/overview.md)** — read before a change
  that crosses a module boundary.
- **[Verification status](verification-status.md)** — read before adding a claim
  to the documentation. The standard for this project is that unproven things
  are marked unproven.

## Enable the git hooks first

Once per clone, before your first commit:

```bash
git config core.hooksPath scripts/hooks
```

This points git at the version-controlled hooks in `scripts/hooks` instead of
the per-clone `.git/hooks`. `scripts/hooks/pre-commit` runs
`scripts/check-repo-size.sh` on every commit, and that is the gate that stops a
multi-gigabyte GGUF, a `.so`, an APK or a signing keystore from entering the
object database. It matters more than the other gates because the damage is not
undoable: a bad commit is fixed by another commit, but a blob in the history is
paid for by every future clone, and removing it means rewriting history for
everyone. A committed key has to be rotated regardless.

The hook additionally runs `spotlessCheck` when Kotlin or Gradle scripts are
staged. `git config --unset core.hooksPath` undoes it.

The full inventory of `scripts/` — what each of the thirteen files is for, and
which are bash versus PowerShell and why — is the **Scripts** section of
[CONTRIBUTING.md](https://github.com/jaypetez/ollama-mobile/blob/main/CONTRIBUTING.md).

## Before you push

```bash
./gradlew spotlessCheck lintDebug test checkModuleGraph assembleDebug
```

Those five are the merge gate. `./gradlew spotlessApply` fixes formatting.
`./gradlew detekt` is advisory and never blocks — see [CI](ci.md) for why.

If you changed documentation, build the site the way CI does:

```bash
python -m pip install -r docs/requirements.txt
mkdocs build --strict
```

Strict mode fails on a nav entry with no page and on a link that does not
resolve, so a broken cross-reference is caught before review rather than after
deploy.

## Two rules worth repeating here

**Never apply `org.jetbrains.kotlin.android`.** AGP 9.3.1 has Kotlin support
built in. Use the convention plugins in `build-logic/`.

**Never add a performance number you did not measure.** There is no physical
arm64 test device for this project, so there are no measurements to cite. A
plausible-sounding figure is worse than no figure, because a reader cannot tell
the difference. Where behaviour is unproven, mark it unproven inline.

## Security

Do not report vulnerabilities through issues or pull requests. Email
<jayson@shoe4africa.org>. See
[`SECURITY.md`](https://github.com/jaypetez/ollama-mobile/blob/main/SECURITY.md)
and [Security model](security-model.md).

## Licence

Contributions are accepted under the MIT licence — Copyright (c) 2026 Jayson
Petersen. There is no CLA.

---
name: merge-dependabot-prs
description: "Triage, verify and merge the open Dependabot pull requests, then report whether a release can be cut. Use whenever asked to handle, sweep, review or merge dependency updates or Dependabot PRs, to clear the dependency backlog, or to check whether a release is ready to go out."
allowed-tools:
  - Bash
  - Read
  - Glob
  - Grep
  - WebFetch
---

# Sweep the open Dependabot pull requests

Start with the audit. It is read-only and merges nothing:

```bash
bash scripts/dependabot-triage.sh --audit
```

It lists every open pull request with a verdict, verifies that each one is
genuinely Dependabot's, and refuses anything that is not. Read
`scripts/dependabot-triage.sh` for the detail — its header block is the
reference, this page is the map. The script owns the mechanical checks; the
judgement below is yours.

## Verdicts

| Verdict | What to do |
| --- | --- |
| `MERGE` | Green, Dependabot's, not a major. Merge it. |
| `REVIEW` | A major. Read the release notes, then merge with `--notes-reviewed`. |
| `COUPLED` | Shares an action family and target version with another open PR, so neither can be green alone. See below — usually the fix is one combined change, not two merges. |
| `BLOCKED` | A check failed for a reason the script could not attribute to coupling. Find out why before doing anything. |
| `PENDING` | Checks still running. A full turnaround is about ten minutes, dominated by CodeQL and the connected tests. |
| `REFUSE` | Failed a security or shape check. The reason is printed. Do not work around it. |

## Reading a major

Majors are deliberately kept out of every group in `.github/dependabot.yml` so
that each one arrives alone and gets its release notes read. `--merge` will
refuse a major until you pass `--notes-reviewed`, because no script can tell
whether reading happened.

```bash
gh api repos/<owner>/<action>/releases/tags/v<version> --jq '.body'
```

**Read every major boundary the bump crosses, not just the target tag.** A
4.4.3 → 6.2.0 bump crosses v5 and v6; the v6.2.0 notes describe neither. Fetch
`v5.0.0` and `v6.0.0` as well.

**A tag that was never released returns 404, which is not the same as "nothing
to read".** `github/codeql-action` publishes per-patch releases and has no
`v4.0.0` at all. Fall back to the action's `CHANGELOG.md` or to
`gh api repos/<owner>/<action>/compare/v3...v4`.

Halt and ask rather than merging when the notes show any of:

- a changed input schema, or a changed default for an input this repo sets
- **a licence or terms-of-use change** — `gradle/actions@v6` moved caching into
  a non-open-source component whose Terms of Use you accept by upgrading. That
  is a policy decision for a project with this repo's constraints, and CI going
  green says nothing about it
- a newly required secret (check `gh secret list` — this repo has none at all)
- a runner or toolchain requirement the workflows cannot meet

A Node 20 → 24 runtime bump is not a halt: hosted runners are well past the
minimum, and it is why most of these majors landed at once.

**A bump that should not land gets closed with a comment** saying what was read
and why, so the reason survives where the next person will look. Add
`@dependabot ignore this major version` in that comment if it should stay closed
until the following release.

## Run order

1. `--audit`, and read every `REFUSE` reason before anything else.
2. Merge the `MERGE` rows.
3. Read notes for each `REVIEW`, then merge or close each one.
4. Resolve each `COUPLED` set. Check whether the shared failure is a required
   context first, because that decides the whole approach:

   ```bash
   gh api repos/{owner}/{repo}/rules/branches/main \
       --jq '.[] | select(.type=="required_status_checks")
                 | .parameters.required_status_checks[].context'
   ```

   If it is **not** required, the halves can land back-to-back, each naming the
   failure that only the other half fixes:

   ```bash
   bash scripts/dependabot-triage.sh --merge <pr> --notes-reviewed \
       --accept-check-failure '<check>'
   ```

   If it **is** required, they cannot land that way at all — see the trap below.
   Replace the set with one change that moves every reference together, which is
   green on its own, and close the bot's halves with a comment saying so.

5. Confirm the tree is actually green afterwards:

   ```bash
   gh run list --branch main --limit 5
   ```

6. Fix any hygiene warnings the audit reported, in a separate pull request.
7. Report release readiness (below).

## Traps

- **`gh pr view --json author` reports `app/dependabot`.** The string
  `dependabot[bot]` never appears in that field, so a check written against it
  compares the wrong name and passes for the wrong reasons. The REST API
  (`.user.login`, `.user.id`, `.user.type`) is authoritative, which is what the
  script uses.
- **`jq` is not installed here.** Every JSON filter must go through `gh`'s
  built-in `--jq`. Piping to a standalone `jq` fails with `command not found`.
- **Read the protection every run; do not assume it.** A ruleset lives in
  repository settings, not in the repository, so it can be switched on
  underneath you between one run and the next. `main` currently carries one
  requiring five contexts and an approving review, and the script reads
  `repos/{owner}/{repo}/rules/branches/main` each time so its gate and
  GitHub's cannot disagree. `scripts/apply-branch-protection.sh` is the
  version-controlled copy.
- **A required context cannot be overridden with `--accept-check-failure`.**
  GitHub rejects the merge on its own side whatever this script decides, so the
  script refuses rather than letting you find out from a failed API call. The
  way through is to make the check pass, not to bypass it — an admin bypass
  exists, but spending it on a dependency bump is not what it is for.
- **`--auto` works, so a patch or minor bump may land without you.**
  `allow_auto_merge` is on, which is what `dependabot-auto-merge.yml:94`
  (`gh pr merge --auto --squash`) needs. That workflow approves and queues
  patch and minor bumps by itself; GitHub then merges them once the required
  checks pass. So a `MERGE` row can disappear between one audit and the next
  without anybody touching it, and an audit is evidence about the moment it ran.
  Majors and the submodule ecosystem are never auto-merged, which is why
  anything needing judgement still reaches you.
- **`CI OK` being green is not sufficient.** It aggregates only the six jobs in
  `ci.yml`, so a failing CodeQL, gitleaks or dependency-review leaves it green
  — and all three of those are required contexts in their own right. Check the
  whole rollup, which is what the script does.
- **The approval is the last step before the merge, and that ordering is the
  point.** The script approves only after every gate has passed, so the review
  it leaves means the verification ran rather than that something rubber-stamped
  the pull request on sight. Approving is allowed because Dependabot is the
  author; GitHub only forbids approving your own.
- **Three checks are `SKIPPED` by design on every Dependabot PR** — `title`
  (semantic-pr exempts the bot), `CodeQL (c-cpp, weekly)` and
  `OSV scan (Gradle graph)`. Treating a by-design skip as failure refuses all
  of them.
- **A coupled set's second half does not re-run its checks when the first
  lands.** The stale red persists, so waiting for it to go green waits forever.
  Either accept the named failure (when it is not a required context) or replace
  the set with one combined change.
- **Never push to a Dependabot branch.** The script verifies that *every*
  commit on the branch is Dependabot-authored and signed, because a collaborator
  can push to a bot branch while the pull request stays authored by the bot.
  Pushing breaks that check for good reason. Clicking "Update branch" also fires
  `synchronize` as you rather than as the bot — `semantic-pr.yml:34-37` records
  how that was first got wrong.
- **A pull request you author is not exempt from what the bot is exempt from.**
  Dependabot skips both semantic-pr and DCO. A follow-up fix PR needs a
  Conventional Commit title with a lowercase subject and no trailing period,
  and `git commit -s`.
- **The `# vX.Y.Z` comment after each pinned SHA is load-bearing** — Dependabot
  reads it to know the current version. The script warns when a merged pin
  would leave it lying; fix it in a follow-up PR rather than by amending the
  bot's branch.
- **Merging some of these releases more of them.** The github-actions ecosystem
  is capped at five open pull requests and is currently saturated, so bumps are
  being withheld. Re-run the audit a while after a sweep.

## The release phase

Diagnose; never publish. Merging a release pull request pushes a tag and starts a
real build, so it stays a human action.

```bash
gh secret list                                     # what is configured
gh pr list --search 'chore: release' --state open  # is there a release PR
gh run list --workflow release-please.yml --limit 3
```

`release-please.yml` opens the release pull request and pushes the tag, but only
if `RELEASE_PLEASE_TOKEN` is set; without it the job exits **green with a
warning** and nothing happens, which is a silence that looks like success. A tag
pushed with the automatic `GITHUB_TOKEN` raises no `push` event, so `release.yml`
would never fire — that is why the PAT exists. `release.yml` additionally needs
the four `OLLAMA_KEYSTORE_*` secrets and refuses rather than falling back to the
debug key. There is no `workflow_dispatch` on it; a `v*` tag is the only entry.

Report which of those five secrets are missing and stop there.

## When it fails

| Symptom | Cause |
| --- | --- |
| `gh is not authenticated` | Run `gh auth login`. |
| `author is 'someone', not 'dependabot[bot]'` | Working as intended — that pull request is not Dependabot's and this skill does not touch it. |
| `commit <sha> is authored by ...` | Someone pushed to the bot's branch. Read that commit before going further; do not override. |
| `changes '<path>', which no configured ecosystem should rewrite` | Either a poisoned branch or a Dependabot behaviour change. Both want a human. |
| `matches neither tag ... nor v...` | The pinned SHA is not a released tag of that action. Stop. |
| `this is a major bump and --notes-reviewed was not passed` | Read the release notes, then pass the flag. |
| `Refusing to merge -- failing checks` | Find out why it is red. Only name it with `--accept-check-failure` when another open PR is the cause and the check is not required. |
| `is failing and is a REQUIRED status check` | Two PRs are half-upgrading one thing. Replace them with a single change that moves every reference together. |
| `required check '<name>' has not reported` | The context never ran, so the PR would park on *Expected — waiting for status*. Often a renamed job that `apply-branch-protection.sh` still names. |
| `gh pr merge failed` | Usually a branch that moved under you, or a rule the ruleset added since the audit. Re-run the audit; nothing was merged. |

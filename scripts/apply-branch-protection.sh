#!/usr/bin/env bash
#
# Apply — or verify — the protection settings on `main`.
#
# GitHub stores this configuration in repository settings rather than in the
# repository, which means it has no history, no review, no diff, and nothing to
# restore it from after a mis-click. CONTRIBUTING.md and docs/ci.md both assert
# that `main` is protected; before this script existed, the only thing making
# that true was somebody's memory. This file is the source of truth and the
# settings are the deployed copy.
#
#   scripts/apply-branch-protection.sh           # apply, then verify
#   scripts/apply-branch-protection.sh --check   # verify only; exit 1 on drift
#   scripts/apply-branch-protection.sh --repo owner/name
#
# Requires `gh` authenticated as a repository admin. The `repo` scope is
# enough. Deliberately does not shell out to `jq`: `gh api --jq` carries its own
# copy, and requiring a second binary is how a script stops being run.
set -euo pipefail

MODE=apply
REPO=""

while [ $# -gt 0 ]; do
  case "$1" in
    --check) MODE=check ;;
    --repo)
      REPO="${2:-}"
      [ -n "$REPO" ] || { echo "--repo needs a value" >&2; exit 2; }
      shift
      ;;
    -h | --help)
      sed -n '3,18p' "$0" | sed 's/^#\{1,\} \{0,1\}//'
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 2
      ;;
  esac
  shift
done

command -v gh >/dev/null || { echo "gh is not installed: https://cli.github.com" >&2; exit 1; }

if [ -z "$REPO" ]; then
  REPO="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
fi

# ---------------------------------------------------------------------------
# The desired state
# ---------------------------------------------------------------------------

# The GitHub Actions app. Required checks are pinned to it by id so that no
# other app can satisfy a gate by publishing a check run with a colliding name.
# That is not hypothetical here: `github-advanced-security` already reports a
# check called "CodeQL" on this repository, distinct from the "CodeQL
# (java-kotlin)" job in security.yml.
readonly ACTIONS_APP_ID=15368

# Every context below must be produced by a job that runs on *every* pull
# request. A required check that never reports leaves the pull request stuck on
# "Expected — waiting for status" with no way forward short of an admin bypass.
#
#   CI OK                 ci.yml's aggregating job. It `needs:` every blocking
#                         Gradle gate, so jobs can be added, split or renamed
#                         inside ci.yml without ever touching this list. That
#                         indirection is the whole reason it exists — see the
#                         comment above the job and docs/ci.md.
#   title                 semantic-pr.yml. The Conventional Commits check on the
#                         pull request title, which squash-merge turns into the
#                         commit on `main` and release-please then reads to pick
#                         the next version. The context is the bare job id
#                         because that job has no `name:`.
#   CodeQL (java-kotlin)  security.yml. Runs on pull_request.
#   gitleaks              security.yml. Runs unconditionally.
#   Dependency review     security.yml. Guarded to pull_request, which is
#                         exactly when it is required.
#
# Deliberately NOT required:
#
#   OSV scan (Gradle graph)         `if: github.event_name != 'pull_request'`,
#                                   so on a pull request it can only ever report
#                                   "skipped" — and GitHub counts a skipped check
#                                   as a pass. Requiring it would look like a
#                                   gate while gating nothing.
#   CodeQL (c-cpp, weekly)          schedule/dispatch only. Same reasoning.
#   Connected tests (API 34)        emulator job behind a guard that can skip it.
#   docs build                      its workflow is path-filtered, so on a pull
#                                   request touching no docs the check never
#                                   appears at all — the "Expected" deadlock.
readonly REQUIRED_CHECKS='"CI OK" "title" "CodeQL (java-kotlin)" "gitleaks" "Dependency review"'

# Repository-admin bypass, granted deliberately. There is one maintainer, and
# GitHub does not let anyone approve their own pull request, so without this the
# review requirement below would be a lock rather than a gate. Everyone else —
# including every bot — is held to the full rule set.
readonly BYPASS='[{"actor_id":5,"actor_type":"RepositoryRole","bypass_mode":"always"}]'

main_ruleset_json() {
  local contexts="" ctx
  eval "set -- $REQUIRED_CHECKS"
  for ctx in "$@"; do
    [ -z "$contexts" ] || contexts="$contexts,"
    contexts="$contexts{\"context\":\"$ctx\",\"integration_id\":$ACTIONS_APP_ID}"
  done

  cat <<JSON
{
  "name": "main",
  "target": "branch",
  "enforcement": "active",
  "bypass_actors": $BYPASS,
  "conditions": { "ref_name": { "include": ["~DEFAULT_BRANCH"], "exclude": [] } },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "required_linear_history" },
    { "type": "required_signatures" },
    {
      "type": "pull_request",
      "parameters": {
        "required_approving_review_count": 1,
        "dismiss_stale_reviews_on_push": true,
        "require_code_owner_review": false,
        "require_last_push_approval": true,
        "required_review_thread_resolution": true,
        "allowed_merge_methods": ["squash"]
      }
    },
    {
      "type": "required_status_checks",
      "parameters": {
        "strict_required_status_checks_policy": false,
        "do_not_enforce_on_create": false,
        "required_status_checks": [$contexts]
      }
    }
  ]
}
JSON
}

# Tags are the release trigger: release.yml fires on `v*`, so a tag that can be
# moved or deleted is a released version that can be silently rewritten after
# the fact. Creation stays open — release-please has to be able to push the tag.
tag_ruleset_json() {
  cat <<JSON
{
  "name": "release tags",
  "target": "tag",
  "enforcement": "active",
  "bypass_actors": $BYPASS,
  "conditions": { "ref_name": { "include": ["refs/tags/v*"], "exclude": [] } },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "update" }
  ]
}
JSON
}

# ---------------------------------------------------------------------------
# Apply
# ---------------------------------------------------------------------------

ruleset_id() {
  gh api "repos/$REPO/rulesets" --jq "map(select(.name == \"$1\")) | .[0].id // empty"
}

upsert_ruleset() {
  local name="$1" body="$2" id file
  file="$(mktemp)"
  # shellcheck disable=SC2064 # expand $file now, while it is still in scope
  trap "rm -f '$file'" RETURN
  printf '%s' "$body" >"$file"

  id="$(ruleset_id "$name")"
  if [ -n "$id" ]; then
    gh api -X PUT "repos/$REPO/rulesets/$id" --input "$file" --silent
    echo "  updated ruleset '$name' (id $id)"
  else
    gh api -X POST "repos/$REPO/rulesets" --input "$file" --silent
    echo "  created ruleset '$name'"
  fi
}

apply() {
  echo "Applying protection to $REPO"

  upsert_ruleset main "$(main_ruleset_json)"
  upsert_ruleset "release tags" "$(tag_ruleset_json)"

  # Squash only. The pull request title becomes the commit on `main`, which is
  # what semantic-pr validates and what release-please parses. A merge commit
  # would put an unvalidated subject on `main`; a rebase merge would push the
  # branch's own commits, which are unsigned and would then be refused by the
  # required_signatures rule above.
  #
  # squash_merge_commit_title / _message are left alone on purpose. The default
  # COMMIT_MESSAGES body is what carries every `Signed-off-by` trailer into
  # `main`'s history; switching it to PR_BODY would erase the DCO record that
  # ci.yml spends a whole job enforcing.
  #
  # allow_auto_merge is not cosmetic: dependabot-auto-merge.yml runs
  # `gh pr merge --auto`, which fails outright when the repository forbids it.
  gh api -X PATCH "repos/$REPO" --silent \
    -F allow_squash_merge=true \
    -F allow_merge_commit=false \
    -F allow_rebase_merge=false \
    -F allow_auto_merge=true \
    -F delete_branch_on_merge=true \
    -F web_commit_signoff_required=true
  echo "  merge strategy, auto-merge, branch cleanup, web sign-off"

  # A read-only default token. Every workflow in .github/workflows declares its
  # own `permissions:` block, so nothing relies on the default being write —
  # this only removes the blast radius of a future workflow that forgets to.
  #
  # can_approve_pull_request_reviews stays on: it is how the bot approval in
  # dependabot-auto-merge.yml satisfies the review requirement.
  gh api -X PUT "repos/$REPO/actions/permissions/workflow" --silent \
    -F default_workflow_permissions=read \
    -F can_approve_pull_request_reviews=true
  echo "  default workflow token: read"

  # Enforce what SECURITY.md already claims: every third-party action pinned to
  # a full commit SHA, so a moved tag cannot change what runs in CI. Local
  # `./.github/actions/*` references are exempt.
  gh api -X PUT "repos/$REPO/actions/permissions" --silent \
    -F enabled=true \
    -f allowed_actions=all \
    -F sha_pinning_required=true
  echo "  actions must be SHA-pinned"
}

# ---------------------------------------------------------------------------
# Verify
# ---------------------------------------------------------------------------

failures=0

expect() {
  local label="$1" want="$2" got="$3"
  if [ "$want" = "$got" ]; then
    printf 'ok    %s\n' "$label"
  else
    printf 'DRIFT %s\n        want: %s\n        got:  %s\n' "$label" "$want" "$got"
    failures=$((failures + 1))
  fi
}

verify() {
  echo "Verifying $REPO"

  local id
  id="$(ruleset_id main)"
  if [ -z "$id" ]; then
    echo "DRIFT no ruleset named 'main' exists"
    failures=$((failures + 1))
  else
    local rs="repos/$REPO/rulesets/$id"
    expect "main: enforced" active "$(gh api "$rs" --jq .enforcement)"
    expect "main: applies to the default branch" '~DEFAULT_BRANCH' \
      "$(gh api "$rs" --jq '.conditions.ref_name.include | join(",")')"
    expect "main: bypass is repository admin only" 'RepositoryRole:5:always' \
      "$(gh api "$rs" --jq '[.bypass_actors[] | "\(.actor_type):\(.actor_id):\(.bypass_mode)"] | sort | join(",")')"
    expect "main: rules" \
      'deletion,non_fast_forward,pull_request,required_linear_history,required_signatures,required_status_checks' \
      "$(gh api "$rs" --jq '[.rules[].type] | sort | join(",")')"
    # approvals / dismiss-stale / code-owner / last-push / thread-resolution / methods
    expect "main: pull request parameters" '1 true false true true squash' \
      "$(gh api "$rs" --jq '.rules[] | select(.type == "pull_request") | .parameters
          | "\(.required_approving_review_count) \(.dismiss_stale_reviews_on_push) \(.require_code_owner_review) \(.require_last_push_approval) \(.required_review_thread_resolution) \(.allowed_merge_methods | join(","))"')"

    # LC_ALL=C so the shell sorts by codepoint, the way jq's `sort` does.
    local want_contexts
    want_contexts="$(eval "set -- $REQUIRED_CHECKS"; printf '%s\n' "$@" | LC_ALL=C sort | paste -sd'|' -)"
    expect "main: required checks" "$want_contexts" \
      "$(gh api "$rs" --jq '[.rules[] | select(.type == "required_status_checks") | .parameters.required_status_checks[].context] | sort | join("|")')"
    expect "main: required checks pinned to the Actions app" "$ACTIONS_APP_ID" \
      "$(gh api "$rs" --jq '[.rules[] | select(.type == "required_status_checks") | .parameters.required_status_checks[].integration_id] | unique | join(",")')"
  fi

  id="$(ruleset_id "release tags")"
  if [ -z "$id" ]; then
    echo "DRIFT no ruleset named 'release tags' exists"
    failures=$((failures + 1))
  else
    local ts="repos/$REPO/rulesets/$id"
    expect "tags: enforced" active "$(gh api "$ts" --jq .enforcement)"
    expect "tags: pattern" 'refs/tags/v*' "$(gh api "$ts" --jq '.conditions.ref_name.include | join(",")')"
    expect "tags: rules" 'deletion,non_fast_forward,update' \
      "$(gh api "$ts" --jq '[.rules[].type] | sort | join(",")')"
  fi

  expect "repository: squash-only merges, auto-merge on, branches deleted, web sign-off" \
    'squash=true merge=false rebase=false auto=true delete=true signoff=true' \
    "$(gh api "repos/$REPO" --jq '"squash=\(.allow_squash_merge) merge=\(.allow_merge_commit) rebase=\(.allow_rebase_merge) auto=\(.allow_auto_merge) delete=\(.delete_branch_on_merge) signoff=\(.web_commit_signoff_required)"')"

  # Guards the DCO trailer's survival into `main`'s history.
  expect "repository: squash body keeps commit messages" 'COMMIT_MESSAGES' \
    "$(gh api "repos/$REPO" --jq .squash_merge_commit_message)"

  expect "actions: default token read-only, bots may approve" 'read true' \
    "$(gh api "repos/$REPO/actions/permissions/workflow" --jq '"\(.default_workflow_permissions) \(.can_approve_pull_request_reviews)"')"

  expect "actions: SHA pinning required" true \
    "$(gh api "repos/$REPO/actions/permissions" --jq .sha_pinning_required)"

  if [ "$failures" -ne 0 ]; then
    printf '\n%s setting(s) drifted from this script. Re-run without --check to fix.\n' "$failures" >&2
    exit 1
  fi
  printf '\nProtection matches this script.\n'
}

case "$MODE" in
  apply)
    apply
    echo
    verify
    ;;
  check) verify ;;
esac

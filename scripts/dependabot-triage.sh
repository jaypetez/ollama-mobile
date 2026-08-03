#!/usr/bin/env bash
#
# dependabot-triage.sh -- verify, triage and merge open Dependabot pull requests.
#
# Audits every open pull request, decides which are genuinely Dependabot's and
# safe to land, and merges the ones you name. Read-only unless --merge is passed.
#
# This exists because `main` has no branch protection: `gh api
# repos/OWNER/REPO/branches/main/protection` returns 404 and /rulesets is empty,
# so there are no required status checks and `gh pr merge --squash` will happily
# merge a pull request whose CodeQL is red. The `ci-ok` indirection in ci.yml is
# built for protection that is not configured yet. Until it is, this script is
# the only gate there is, which is why the checks run again inside --merge
# immediately before the merge rather than being left to whoever read the audit.
#
# The identity check deliberately does not use `gh pr view --json author`. That
# field reports {"is_bot":true,"login":"app/dependabot"} -- the string
# "dependabot[bot]" never appears in it, so a check written against it compares
# against the wrong name and passes for the wrong reasons. The REST API is
# authoritative: .user.login, .user.id and .user.type.
#
# Verifying the pull request author is not enough on its own. A collaborator can
# push to a Dependabot branch, and the pull request would still be authored by
# the bot. So every commit on the branch is checked for authorship and for a
# valid signature, and the changed paths are checked against an allowlist of the
# files the three configured ecosystems can legitimately touch.
#
# Language choice: bash. Everything here is `gh` and text, which behaves
# identically under Git Bash on Windows and on a Linux runner. Note that jq is
# NOT assumed to be installed -- all JSON is filtered with gh's built-in --jq
# engine, never by piping to a standalone jq.
#
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

# Dependabot's own account. The numeric id is pinned as well as the login
# because it is the one identifier that cannot be recycled or spoofed by a
# renamed account; the login is checked because it is what the policy is written
# in terms of (dependabot-auto-merge.yml:29 keys on the same string).
DEPENDABOT_LOGIN='dependabot[bot]'
DEPENDABOT_USER_ID='49699333'

# The only files the gradle, github-actions and pip ecosystems configured in
# .github/dependabot.yml can legitimately rewrite. Anything else on a Dependabot
# branch is either a poisoned branch or a Dependabot behaviour change; both want
# a human, so both are refused rather than ignored.
ALLOWED_PATHS=(
    '^\.github/workflows/[^/]+\.ya?ml$'
    '^\.github/actions/[^/]+/action\.ya?ml$'
    '^gradle/libs\.versions\.toml$'
    '^docs/requirements\.txt$'
)

# Belt and braces against the one bump this project never automates, mirroring
# dependabot-auto-merge.yml:67-73. The gitsubmodule ecosystem is switched off in
# dependabot.yml, so a hit here means something is wrong upstream of us.
DENIED_PATHS=(
    '^third_party/'
    '^\.gitmodules$'
)

# Checks that are SKIPPED by design on every Dependabot pull request. Treating a
# by-design skip as a failure would refuse all of them:
#   title                     semantic-pr exempts dependabot[bot] (semantic-pr.yml:38)
#   CodeQL (c-cpp, weekly)    schedule/dispatch only (security.yml:144)
#   OSV scan (Gradle graph)   non-pull_request only (security.yml:296)
EXPECTED_SKIPS=(
    'title'
    'CodeQL (c-cpp, weekly)'
    'OSV scan (Gradle graph)'
)

# Used only when the repository has no ruleset to read. `CI OK` aggregates the
# six jobs in ci.yml (ci.yml:335-341), so it is the minimum meaningful gate --
# and note that it is not sufficient on its own, because a failing CodeQL,
# gitleaks or dependency-review leaves it green.
FALLBACK_REQUIRED_CHECK='CI OK'

# Filled by discover_protection() from the ruleset that actually applies to the
# default branch, so this script gates on the same contexts GitHub does rather
# than on a list baked in here that drifts the moment a job is renamed.
REQUIRED_CONTEXTS=''
REQUIRED_REVIEWS=0

usage() {
    cat <<'EOF'
Usage: scripts/dependabot-triage.sh [--audit] [--json]
       scripts/dependabot-triage.sh --merge <pr> [--notes-reviewed]
                                    [--accept-check-failure <name>]

Audit mode (the default) is read-only. It prints one row per open pull request
with a verdict:

  MERGE     Dependabot's, green, and not a major. Safe to land.
  REVIEW    As MERGE, but a major version bump. Read the release notes for every
            major boundary it crosses, then merge with --notes-reviewed.
  COUPLED   Shares an action family and target version with another open pull
            request, so neither can be green alone. If the shared failure is a
            required context, one combined change is the only way through.
  BLOCKED   A check is failing.
  PENDING   Checks are still running. Nothing to decide yet.
  REFUSE    Failed a security or hygiene check. The reason is printed.

Options:
  --audit                       Read-only report. The default.
  --json                        Emit the audit as JSON instead of a table.
  --merge <pr>                  Re-verify everything, then squash-merge.
  --notes-reviewed              Assert the release notes were read. Required to
                                merge a major; the script cannot verify reading.
  --accept-check-failure <name> Tolerate one named failing check. Intended for a
                                COUPLED set, where the failure is the version
                                handshake and is fixed by the other half landing.
                                Refused if the named check is a required status
                                check on the default branch: GitHub would reject
                                the merge anyway, and bypassing it is a decision
                                about a deliberate protection, not a flag.
  -h, --help                    This text.

Exit status:
  0  clean (audit printed, or merge completed)
  2  usage problem, or gh/network/repository problem
  3  refused -- a check did not pass, and nothing was merged
EOF
}

die() {
    printf '\nERROR: %s\n' "$1" >&2
    exit 2
}

# --- assessment ------------------------------------------------------------

# assess() fills these. Bash has no return values worth the name, so the
# assessment writes to globals and the callers read them straight afterwards.
A_NUM='' A_TITLE='' A_LOGIN='' A_HEAD='' A_BASE='' A_DRAFT=''
A_DEP='' A_FROM='' A_TO='' A_UPDATE_TYPE='' A_FAMILY=''
A_REFUSALS='' A_WARNINGS='' A_FAILING='' A_PENDING='' A_VERDICT=''

# Read the rules that actually apply to the default branch. A ruleset lives in
# repository settings rather than in the repository, so it can be turned on or
# off underneath you between one run and the next -- which is exactly what
# happened while this script was being written. Reading it every run means the
# gate here and the gate GitHub enforces cannot disagree.
#
# An empty result is a legitimate answer meaning "nothing is protecting main",
# not an error; that is the state this script was originally written for.
discover_protection() {
    REQUIRED_CONTEXTS="$(gh api "repos/${SLUG}/rules/branches/${DEFAULT_BRANCH}" \
        --jq '.[] | select(.type=="required_status_checks")
                  | .parameters.required_status_checks[].context' 2>/dev/null || true)"
    REQUIRED_REVIEWS="$(gh api "repos/${SLUG}/rules/branches/${DEFAULT_BRANCH}" \
        --jq 'first(.[] | select(.type=="pull_request")
                  | .parameters.required_approving_review_count) // 0' 2>/dev/null || true)"
    case "${REQUIRED_REVIEWS}" in
        ''|*[!0-9]*) REQUIRED_REVIEWS=0 ;;
    esac
}

is_required_context() {
    local name="$1" context
    [ -n "${REQUIRED_CONTEXTS}" ] || return 1
    while IFS= read -r context; do
        [ -n "${context}" ] || continue
        if [ "${name}" = "${context}" ]; then return 0; fi
    done <<<"${REQUIRED_CONTEXTS}"
    return 1
}

# Resolve a tag to the commit it points at, dereferencing an annotated tag.
# Prints nothing and returns 1 when the tag does not exist.
resolve_tag() {
    local repo="$1" tag="$2" line sha type
    line="$(gh api "repos/${repo}/git/ref/tags/${tag}" \
        --jq '[.object.sha, .object.type] | @tsv' 2>/dev/null)" || return 1
    [ -n "${line}" ] || return 1
    IFS=$'\t' read -r sha type <<<"${line}"
    if [ "${type}" = 'tag' ]; then
        sha="$(gh api "repos/${repo}/git/tags/${sha}" --jq '.object.sha' 2>/dev/null)" || return 1
    fi
    [ -n "${sha}" ] || return 1
    printf '%s' "${sha}"
}

add_refusal() { A_REFUSALS="${A_REFUSALS}${1}"$'\n'; }
add_warning() { A_WARNINGS="${A_WARNINGS}${1}"$'\n'; }

is_expected_skip() {
    local name="$1" expected
    for expected in "${EXPECTED_SKIPS[@]}"; do
        if [ "${name}" = "${expected}" ]; then return 0; fi
    done
    return 1
}

# 1. Identity, branch and draft state, from the REST API.
assess_identity() {
    local line login id type
    line="$(gh api "repos/${SLUG}/pulls/${A_NUM}" \
        --jq '[.user.login, (.user.id|tostring), .user.type, .head.ref, .base.ref, (.draft|tostring), .title] | @tsv')" \
        || die "Could not read pull request ${A_NUM}."
    IFS=$'\t' read -r login id type A_HEAD A_BASE A_DRAFT A_TITLE <<<"${line}"
    A_LOGIN="${login}"

    if [ "${login}" != "${DEPENDABOT_LOGIN}" ]; then
        add_refusal "author is '${login}', not '${DEPENDABOT_LOGIN}'"
    fi
    if [ "${id}" != "${DEPENDABOT_USER_ID}" ]; then
        add_refusal "author user id is ${id}, not ${DEPENDABOT_USER_ID}"
    fi
    if [ "${type}" != 'Bot' ]; then
        add_refusal "author account type is '${type}', not 'Bot'"
    fi
    case "${A_HEAD}" in
        dependabot/*) ;;
        *) add_refusal "head branch '${A_HEAD}' is not under dependabot/" ;;
    esac
    if [ "${A_BASE}" != "${DEFAULT_BRANCH}" ]; then
        add_refusal "base branch is '${A_BASE}', not '${DEFAULT_BRANCH}'"
    fi
    if [ "${A_DRAFT}" = 'true' ]; then
        add_refusal "pull request is a draft"
    fi
}

# 2. Every commit on the branch, not just the pull request author. A
#    collaborator can push to a Dependabot branch and the pull request stays
#    authored by the bot, so this is the check that actually holds the line.
assess_commits() {
    local commits count=0 author verified short
    commits="$(gh api "repos/${SLUG}/pulls/${A_NUM}/commits" --paginate \
        --jq '.[] | [(.author.login // "-"), (.commit.verification.verified|tostring), .sha[0:8]] | @tsv')" \
        || die "Could not read commits for pull request ${A_NUM}."
    while IFS=$'\t' read -r author verified short; do
        [ -n "${short}" ] || continue
        count=$((count + 1))
        if [ "${author}" != "${DEPENDABOT_LOGIN}" ]; then
            add_refusal "commit ${short} is authored by '${author}', not '${DEPENDABOT_LOGIN}'"
        fi
        if [ "${verified}" != 'true' ]; then
            add_refusal "commit ${short} has no valid signature"
        fi
    done <<<"${commits}"
    if [ "${count}" -eq 0 ]; then
        add_refusal "pull request has no commits"
    fi
}

# 3. Changed paths against the allowlist, and the llama.cpp denylist.
assess_paths() {
    local files path pattern matched
    files="$(gh api "repos/${SLUG}/pulls/${A_NUM}/files" --paginate --jq '.[].filename')" \
        || die "Could not read changed files for pull request ${A_NUM}."
    if [ -z "${files}" ]; then
        add_refusal "pull request changes no files"
        return
    fi
    while IFS= read -r path; do
        [ -n "${path}" ] || continue
        for pattern in "${DENIED_PATHS[@]}"; do
            if [[ "${path}" =~ ${pattern} ]]; then
                add_refusal "touches '${path}', which only llamacpp-bump.yml or scripts/update-llamacpp.sh may move"
                continue 2
            fi
        done
        matched=0
        for pattern in "${ALLOWED_PATHS[@]}"; do
            if [[ "${path}" =~ ${pattern} ]]; then matched=1; break; fi
        done
        if [ "${matched}" -eq 0 ]; then
            add_refusal "changes '${path}', which no configured ecosystem should rewrite"
        fi
    done <<<"${files}"
}

# 4. Every action pin the diff adds must be a SHA that really is a released tag
#    of that action.
#
#    Two different concerns are separated here on purpose. Whether the SHA is a
#    genuine release of the action is a security property and refuses. Whether
#    the trailing `# vX.Y.Z` comment names the right tag is hygiene: it warns,
#    because dependabot.yml:82-88 relies on that comment to read the current
#    version, but a stale comment cannot execute anything. Dependabot gets this
#    wrong often enough to matter -- and note that a moving `vX` tag does not
#    exist for every action, so failing to resolve the comment's tag is not by
#    itself evidence of anything.
assess_pins() {
    local patches added ref sha comment repo resolved
    patches="$(gh api "repos/${SLUG}/pulls/${A_NUM}/files" --paginate --jq '.[].patch // ""')" \
        || die "Could not read the diff for pull request ${A_NUM}."
    added="$(printf '%s\n' "${patches}" | grep -E '^\+.*uses:' || true)"
    [ -n "${added}" ] || return 0

    while IFS= read -r line; do
        [ -n "${line}" ] || continue
        if [[ ! "${line}" =~ uses:[[:space:]]*([^@[:space:]]+)@([0-9a-f]{40})[[:space:]]*#[[:space:]]*(v[^[:space:]]+) ]]; then
            continue
        fi
        ref="${BASH_REMATCH[1]}"
        sha="${BASH_REMATCH[2]}"
        comment="${BASH_REMATCH[3]}"
        repo="$(printf '%s' "${ref}" | cut -d/ -f1-2)"

        resolved="$(resolve_tag "${repo}" "${comment}" || true)"
        if [ "${resolved}" = "${sha}" ]; then
            continue
        fi
        # The comment does not resolve to the pinned SHA. If the version the
        # title claims to move to does, the SHA is legitimate and only the
        # comment is stale.
        if [ -n "${A_TO}" ]; then
            resolved="$(resolve_tag "${repo}" "v${A_TO}" || true)"
            if [ "${resolved}" = "${sha}" ]; then
                add_warning "${ref} is pinned to v${A_TO} but its comment still says ${comment}"
                continue
            fi
        fi
        add_refusal "${ref}@${sha:0:8} matches neither tag ${comment} nor v${A_TO:-?} in ${repo}"
    done <<<"${added}"
}

# 5. The green gate. `CI OK` alone is not sufficient: it aggregates only the six
#    jobs in ci.yml (ci.yml:335-341), so a red CodeQL leaves it green.
assess_checks() {
    local rollup name status conclusion
    rollup="$(gh pr view "${A_NUM}" --json statusCheckRollup \
        --jq '.statusCheckRollup[] | [(.name // .context // "?"), (.status // "-"), (.conclusion // .state // "-")] | @tsv')" \
        || die "Could not read checks for pull request ${A_NUM}."
    local seen=''
    while IFS=$'\t' read -r name status conclusion; do
        [ -n "${name}" ] || continue
        seen="${seen}${name}"$'\n'
        case "${conclusion}" in
            FAILURE|ERROR|CANCELLED|TIMED_OUT|ACTION_REQUIRED|STARTUP_FAILURE)
                A_FAILING="${A_FAILING}${name}"$'\n' ;;
            SKIPPED|NEUTRAL)
                # GitHub counts a skipped required check as satisfied, so this is
                # reported rather than fatal. Only the three that skip by design
                # pass without comment.
                if ! is_expected_skip "${name}"; then
                    add_warning "check '${name}' reported ${conclusion}"
                fi ;;
        esac
        case "${status}" in
            QUEUED|IN_PROGRESS|WAITING|PENDING|REQUESTED)
                A_PENDING="${A_PENDING}${name}"$'\n' ;;
        esac
    done <<<"${rollup}"

    # Every context GitHub requires must at least have reported. A required check
    # that never appears is what parks a pull request on "Expected -- waiting for
    # status" with no way forward, so an absent one refuses rather than reading as
    # green.
    local contexts="${REQUIRED_CONTEXTS}" context
    [ -n "${contexts}" ] || contexts="${FALLBACK_REQUIRED_CHECK}"
    while IFS= read -r context; do
        [ -n "${context}" ] || continue
        if ! printf '%s' "${seen}" | grep -qxF "${context}"; then
            add_refusal "required check '${context}' has not reported"
        fi
    done <<<"${contexts}"
}

# Dependabot titles read "Bump <dep> from <a> to <b>". A grouped pull request
# instead reads "Bump the <name> group ...", which has no single version pair;
# every group in dependabot.yml carries update-types: [minor, patch], so a group
# is never a major.
assess_update_type() {
    if [[ "${A_TITLE}" =~ Bump\ (.+)\ from\ ([0-9][^ ]*)\ to\ ([0-9][^ ]*) ]]; then
        A_DEP="${BASH_REMATCH[1]}"
        A_FROM="${BASH_REMATCH[2]}"
        A_TO="${BASH_REMATCH[3]}"
        if [ "${A_FROM%%.*}" != "${A_TO%%.*}" ]; then
            A_UPDATE_TYPE='major'
        else
            A_UPDATE_TYPE='minor-or-patch'
        fi
    elif [[ "${A_TITLE}" =~ Bump\ the\ ([^ ]+)\ group ]]; then
        A_DEP="the ${BASH_REMATCH[1]} group"
        A_UPDATE_TYPE='group'
    else
        A_DEP='?'
        A_UPDATE_TYPE='unknown'
    fi
    # Action families: github/codeql-action/init and .../analyze are separate
    # dependencies to Dependabot but one action to the workflow that uses them.
    A_FAMILY="$(printf '%s' "${A_DEP}" | cut -d/ -f1-2)"
}

reset_assessment() {
    A_TITLE='' A_LOGIN='' A_HEAD='' A_BASE='' A_DRAFT=''
    A_DEP='' A_FROM='' A_TO='' A_UPDATE_TYPE='' A_FAMILY=''
    A_REFUSALS='' A_WARNINGS='' A_FAILING='' A_PENDING='' A_VERDICT=''
}

assess() {
    A_NUM="$1"
    reset_assessment
    assess_identity
    assess_update_type
    # A pull request that is not Dependabot's gets no further API calls: the
    # remaining checks are about the shape of a Dependabot change and would only
    # produce confusing secondary refusals.
    if [ -n "${A_REFUSALS}" ] && [ "${A_LOGIN}" != "${DEPENDABOT_LOGIN}" ]; then
        A_VERDICT='REFUSE'
        return
    fi
    assess_commits
    assess_paths
    assess_pins
    assess_checks

    if [ -n "${A_REFUSALS}" ]; then
        A_VERDICT='REFUSE'
    elif [ -n "${A_FAILING}" ]; then
        A_VERDICT='BLOCKED'
    elif [ -n "${A_PENDING}" ]; then
        A_VERDICT='PENDING'
    elif [ "${A_UPDATE_TYPE}" = 'major' ]; then
        A_VERDICT='REVIEW'
    else
        A_VERDICT='MERGE'
    fi
}

# --- output ----------------------------------------------------------------

first_line() { printf '%s' "${1%%$'\n'*}"; }

# Records are delimited by US (0x1f), not by a tab. Tab is an IFS *whitespace*
# character, so `IFS=$'\t' read` collapses consecutive tabs and silently shifts
# every field left when an optional one (a version, a note) is empty. US is not
# whitespace, so empty fields survive.
readonly US=$'\x1f'

# --- modes -----------------------------------------------------------------

open_prs() {
    gh pr list --state open --limit 100 --json number --jq '.[].number' \
        || die "Could not list open pull requests."
}

cmd_audit() {
    local prs n note families='' fam dup
    prs="$(open_prs)"
    if [ -z "${prs}" ]; then
        printf 'No open pull requests.\n'
        return 0
    fi

    # First pass: assess everything and remember the family/version of each
    # candidate so coupled sets can be spotted in the second pass.
    local records=''
    while IFS= read -r n; do
        [ -n "${n}" ] || continue
        assess "${n}"
        records="${records}${n}${US}${A_VERDICT}${US}${A_UPDATE_TYPE}${US}${A_FAMILY}${US}${A_TO}${US}${A_DEP}${US}$(first_line "${A_REFUSALS}${A_FAILING}${A_WARNINGS}")"$'\n'
        if [ "${A_VERDICT}" != 'REFUSE' ] && [ -n "${A_FAMILY}" ] && [ "${A_FAMILY}" != '?' ]; then
            families="${families}${A_FAMILY}@${A_TO}"$'\n'
        fi
    done <<<"${prs}"

    if [ "${JSON_OUTPUT}" -eq 1 ]; then
        emit_json "${records}" "${families}"
        return 0
    fi

    printf '\n%-5s  %-8s  %-16s  %s\n' 'PR' 'VERDICT' 'UPDATE' 'DEPENDENCY / NOTE'
    printf '%-5s  %-8s  %-16s  %s\n' '-----' '--------' '----------------' '-----------------'

    local num verdict utype family to dep first
    while IFS="${US}" read -r num verdict utype family to dep first; do
        [ -n "${num}" ] || continue
        # Coupled: another candidate shares this family and target version.
        if [ -n "${family}" ] && [ "${family}" != '?' ]; then
            dup="$(printf '%s' "${families}" | grep -c "^${family}@${to}$" || true)"
            if [ "${dup}" -gt 1 ] && { [ "${verdict}" = 'BLOCKED' ] || [ "${verdict}" = 'REVIEW' ] || [ "${verdict}" = 'MERGE' ]; }; then
                verdict='COUPLED'
            fi
        fi
        note="${dep}"
        [ -n "${first}" ] && note="${dep} -- ${first}"
        printf '%-5s  %-8s  %-16s  %s\n' "#${num}" "${verdict}" "${utype}" "${note}"
    done <<<"${records}"

    cat <<'EOF'

REVIEW and COUPLED need a decision before anything is merged. Read the release
notes for every major boundary the bump crosses, then:

    scripts/dependabot-triage.sh --merge <pr> --notes-reviewed

A COUPLED set lands back-to-back, each half naming the check that only the other
half can fix, for example:

    scripts/dependabot-triage.sh --merge <pr> --notes-reviewed \
        --accept-check-failure 'CodeQL (java-kotlin)'
EOF
    printf '\n'
}

json_escape() {
    local s="$1"
    s="${s//\\/\\\\}"
    s="${s//\"/\\\"}"
    s="${s//$'\n'/\\n}"
    s="${s//$'\t'/\\t}"
    printf '%s' "${s}"
}

emit_json() {
    local records="$1" families="$2" num verdict utype family to dep first dup sep=''
    printf '[\n'
    while IFS="${US}" read -r num verdict utype family to dep first; do
        [ -n "${num}" ] || continue
        if [ -n "${family}" ] && [ "${family}" != '?' ]; then
            dup="$(printf '%s' "${families}" | grep -c "^${family}@${to}$" || true)"
            if [ "${dup}" -gt 1 ] && { [ "${verdict}" = 'BLOCKED' ] || [ "${verdict}" = 'REVIEW' ] || [ "${verdict}" = 'MERGE' ]; }; then
                verdict='COUPLED'
            fi
        fi
        printf '%s  {"pr": %s, "verdict": "%s", "update_type": "%s", "family": "%s", "to": "%s", "dependency": "%s", "note": "%s"}' \
            "${sep}" "${num}" "${verdict}" "${utype}" \
            "$(json_escape "${family}")" "$(json_escape "${to}")" \
            "$(json_escape "${dep}")" "$(json_escape "${first}")"
        sep=$',\n'
    done <<<"${records}"
    printf '\n]\n'
}

cmd_merge() {
    local n="$1" accepted="$2" notes_reviewed="$3"
    assess "${n}"

    printf '\nPull request #%s: %s\n' "${n}" "${A_TITLE}"
    printf '  author        %s\n' "${A_LOGIN}"
    printf '  branch        %s -> %s\n' "${A_HEAD}" "${A_BASE}"
    printf '  update type   %s\n' "${A_UPDATE_TYPE}"

    if [ -n "${A_WARNINGS}" ]; then
        printf '\nWarnings (recorded, not blocking):\n'
        printf '%s' "${A_WARNINGS}" | sed 's/^/  - /'
    fi

    if [ -n "${A_REFUSALS}" ]; then
        printf '\nRefusing to merge #%s:\n' "${n}"
        printf '%s' "${A_REFUSALS}" | sed 's/^/  - /'
        printf '\nNothing was merged.\n'
        exit 3
    fi

    # A failing check is fatal unless it is the one the caller named. Naming it
    # is the point: the override lands in the transcript instead of being a
    # blanket bypass.
    local name unexpected=''
    if [ -n "${A_FAILING}" ]; then
        while IFS= read -r name; do
            [ -n "${name}" ] || continue
            if [ "${name}" != "${accepted}" ]; then
                unexpected="${unexpected}${name}"$'\n'
            fi
        done <<<"${A_FAILING}"
    fi
    if [ -n "${unexpected}" ]; then
        printf '\nRefusing to merge #%s -- failing checks:\n' "${n}"
        printf '%s' "${unexpected}" | sed 's/^/  - /'
        if [ -n "${accepted}" ]; then
            printf '\nOnly '\''%s'\'' was accepted.\n' "${accepted}"
        fi
        printf '\nNothing was merged.\n'
        exit 3
    fi
    # Accepting a failure locally is not the same as GitHub accepting it. If the
    # named check is a required context, the merge would be rejected on the far
    # side no matter what this script decides, and the only way through is an
    # admin bypass -- which is a decision about a protection someone installed on
    # purpose, not a flag this script should quietly set.
    if [ -n "${accepted}" ] && [ -n "${A_FAILING}" ]; then
        if is_required_context "${accepted}"; then
            cat >&2 <<EOF

Refusing to merge #${n} -- '${accepted}' is failing and is a REQUIRED status
check on '${DEFAULT_BRANCH}', so GitHub will reject the merge regardless of
--accept-check-failure. Overriding it needs an admin bypass of a protection that
was installed deliberately.

Fix the cause instead. When two pull requests each half-upgrade one action, the
fix is a single change that moves every reference together, which is green on its
own and needs no bypass.

Nothing was merged.
EOF
            exit 3
        fi
        printf '\nAccepting the failure of '\''%s'\'', which is not a required context.\n' "${accepted}"
    fi

    if [ -n "${A_PENDING}" ]; then
        printf '\nRefusing to merge #%s -- checks still running:\n' "${n}"
        printf '%s' "${A_PENDING}" | sed 's/^/  - /'
        printf '\nNothing was merged.\n'
        exit 3
    fi

    if [ "${A_UPDATE_TYPE}" = 'major' ] && [ "${notes_reviewed}" -ne 1 ]; then
        cat >&2 <<EOF

Refusing to merge #${n} -- this is a major bump and --notes-reviewed was not
passed. Majors are kept out of every group in .github/dependabot.yml precisely
so each one gets its release notes read; this script cannot tell whether that
happened, so it asks you to say so.

Read every major boundary the bump crosses, not just the target tag:

    gh api repos/<owner>/<action>/releases/tags/v<version> --jq '.body'

Nothing was merged.
EOF
        exit 3
    fi

    # The approval is deliberately the last thing before the merge, and it only
    # happens once every check above has passed. That ordering is the whole point:
    # the review on the pull request then means "this was verified", not "a script
    # rubber-stamped it on sight". Dependabot is the author, so approving is
    # allowed -- GitHub only forbids approving your own pull request.
    if [ "${REQUIRED_REVIEWS}" -gt 0 ]; then
        local approvals
        approvals="$(gh api "repos/${SLUG}/pulls/${n}/reviews" --paginate \
            --jq '[.[] | select(.state=="APPROVED")] | length' 2>/dev/null || printf '0')"
        [ -n "${approvals}" ] || approvals=0
        if [ "${approvals}" -lt "${REQUIRED_REVIEWS}" ]; then
            printf '\n%s requires %s approving review(s); found %s. Approving.\n' \
                "${DEFAULT_BRANCH}" "${REQUIRED_REVIEWS}" "${approvals}"
            gh pr review "${n}" --approve \
                --body "Verified by scripts/dependabot-triage.sh: author, every commit's authorship and signature, changed paths, action pin provenance and all required checks." \
                || die "Could not approve #${n}."
        fi
    fi

    printf '\nAll checks passed. Squash-merging #%s.\n' "${n}"
    gh pr merge "${n}" --squash || die "gh pr merge failed for #${n}."
    printf 'Merged #%s.\n' "${n}"
}

# --- arguments -------------------------------------------------------------

MODE='audit'
JSON_OUTPUT=0
MERGE_PR=''
ACCEPTED_FAILURE=''
NOTES_REVIEWED=0

while [ $# -gt 0 ]; do
    case "$1" in
        --audit) MODE='audit'; shift ;;
        --json) JSON_OUTPUT=1; shift ;;
        --merge) [ $# -ge 2 ] || die "--merge needs a pull request number"; MODE='merge'; MERGE_PR="$2"; shift 2 ;;
        --merge=*) MODE='merge'; MERGE_PR="${1#--merge=}"; shift ;;
        --accept-check-failure) [ $# -ge 2 ] || die "--accept-check-failure needs a check name"; ACCEPTED_FAILURE="$2"; shift 2 ;;
        --accept-check-failure=*) ACCEPTED_FAILURE="${1#--accept-check-failure=}"; shift ;;
        --notes-reviewed) NOTES_REVIEWED=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; die "Unknown argument: $1" ;;
    esac
done

if [ "${MODE}" = 'merge' ]; then
    case "${MERGE_PR}" in
        ''|*[!0-9]*) die "--merge needs a pull request number, got '${MERGE_PR}'." ;;
    esac
fi

cd "${REPO_ROOT}"
git rev-parse --git-dir >/dev/null 2>&1 || die "${REPO_ROOT} is not a git repository."
command -v gh >/dev/null 2>&1 || die "The GitHub CLI (gh) is not on PATH. See https://cli.github.com."
gh auth status >/dev/null 2>&1 || die "gh is not authenticated. Run: gh auth login"

SLUG="$(gh repo view --json nameWithOwner --jq '.nameWithOwner')" \
    || die "Could not determine the repository from this checkout."
DEFAULT_BRANCH="$(gh repo view --json defaultBranchRef --jq '.defaultBranchRef.name')" \
    || die "Could not determine the default branch."
discover_protection

case "${MODE}" in
    audit) cmd_audit ;;
    merge) cmd_merge "${MERGE_PR}" "${ACCEPTED_FAILURE}" "${NOTES_REVIEWED}" ;;
esac

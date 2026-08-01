#!/usr/bin/env bash
#
# check-repo-size.sh -- repository hygiene gate.
#
# Fails if a tracked file is larger than the limit, or if a file that must never
# be tracked (model weights, native binaries, app packages, signing keys) has
# been staged or committed anyway.
#
# This exists because the damage is not undoable by a normal revert: once a
# 4 GB GGUF or a keystore is in the object database, every future clone pays for
# it, and removing it means rewriting history for everyone. The cheap moment to
# catch it is before the commit, which is why scripts/hooks/pre-commit calls
# this too.
#
# Sizes are read from the git *index*, not the working tree, so staged content
# is what gets judged. That is the right thing for a pre-commit hook and is
# still correct on a clean checkout.
#
# Language choice: bash. It is pure git plumbing, it runs in CI (Linux) and in
# the pre-commit hook (Git Bash on Windows), and both must agree exactly.
#
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

MAX_BYTES=$((10 * 1024 * 1024))

usage() {
    cat <<'EOF'
Usage: scripts/check-repo-size.sh [options]

Fails when:
  * any tracked file exceeds the size limit (default 10 MB), or
  * any tracked file matches a banned pattern:
        *.gguf  *.so  *.apk  *.aab  *.jks  *.keystore  keystore.properties

Options:
  --max-bytes <n>   Size limit in bytes. Default 10485760 (10 MB).
  --max-mb <n>      Size limit in megabytes.
  -h, --help        This text.

Exit status:
  0  clean
  1  at least one violation (each is listed with how to fix it)
  2  usage problem or not a git repository
EOF
}

die() {
    printf '\nERROR: %s\n' "$1" >&2
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --max-bytes) [ $# -ge 2 ] || die "--max-bytes needs a value"; MAX_BYTES="$2"; shift 2 ;;
        --max-bytes=*) MAX_BYTES="${1#--max-bytes=}"; shift ;;
        --max-mb) [ $# -ge 2 ] || die "--max-mb needs a value"; MAX_BYTES=$(( $2 * 1024 * 1024 )); shift 2 ;;
        --max-mb=*) MAX_BYTES=$(( ${1#--max-mb=} * 1024 * 1024 )); shift ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; die "Unknown argument: $1" ;;
    esac
done

case "${MAX_BYTES}" in
    ''|*[!0-9]*) die "The size limit must be a positive integer number of bytes." ;;
esac

cd "${REPO_ROOT}"
git rev-parse --git-dir >/dev/null 2>&1 || die "${REPO_ROOT} is not a git repository."

# The wrapper jar is the one binary this project accepts. It is how the Gradle
# wrapper works and it is verified by distributionSha256Sum in
# gradle/wrapper/gradle-wrapper.properties.
ALLOWLIST_EXACT=(
    "gradle/wrapper/gradle-wrapper.jar"
)

is_allowlisted() {
    local path="$1" allowed
    for allowed in "${ALLOWLIST_EXACT[@]}"; do
        if [ "${path}" = "${allowed}" ]; then return 0; fi
    done
    return 1
}

violations=0

# --- banned file types -----------------------------------------------------

BANNED_PATTERNS=(
    '*.gguf'
    '*.gguf.part'
    '*.so'
    '*.apk'
    '*.aab'
    '*.jks'
    '*.keystore'
    '*.p12'
    'keystore.properties'
)

banned_hits="$(git ls-files -- "${BANNED_PATTERNS[@]}" || true)"

if [ -n "${banned_hits}" ]; then
    printf 'Tracked files that must never be committed:\n\n'
    while IFS= read -r path; do
        [ -n "${path}" ] || continue
        printf '  %s\n' "${path}"
        violations=$((violations + 1))
    done <<<"${banned_hits}"
    cat <<'EOF'

Why each is banned:
  *.gguf   Model weights are gigabytes and are downloaded at runtime. Nothing
           in this repository should ever contain one, not even a truncated
           test fixture -- see scripts/fetch-models.sh.
  *.so     Native libraries are build output. Prebuilt binaries live in
           core-llm/prebuilt/ (gitignored) or are attached to a GitHub Release,
           never tracked.
  *.apk    Distribution is GitHub Releases only.
  *.aab    Same, and there is no Play Store target at all.
  keys     A committed signing key is a compromised signing key. Rotate it.

Untrack them (this keeps the file on disk):
  git rm --cached <path>

Then make sure .gitignore covers the pattern and commit that too. If the file
is already in a previous commit, untracking is not enough -- the blob is still
in history and needs git-filter-repo plus a force push, so raise it before
doing anything else.
EOF
    printf '\n'
fi

# --- oversized files -------------------------------------------------------

# One `git cat-file --batch-check` for the whole index rather than one process
# per file. Paths containing a newline would desynchronise the two lists, so the
# line counts are compared and the script refuses to guess.
index_listing="$(git ls-files --stage)"

if [ -n "${index_listing}" ]; then
    object_ids="$(printf '%s\n' "${index_listing}" | awk '{ print $2 }')"
    paths="$(printf '%s\n' "${index_listing}" | cut -f2-)"
    sizes="$(printf '%s\n' "${object_ids}" | git cat-file --batch-check='%(objectsize)')"

    path_count="$(printf '%s\n' "${paths}" | wc -l | tr -d '[:space:]')"
    size_count="$(printf '%s\n' "${sizes}" | wc -l | tr -d '[:space:]')"

    if [ "${path_count}" != "${size_count}" ]; then
        die "Could not match ${path_count} paths to ${size_count} object sizes. A tracked path probably contains a newline; fix that first."
    fi

    oversized="$(paste -d '\t' <(printf '%s\n' "${sizes}") <(printf '%s\n' "${paths}") \
        | awk -F'\t' -v limit="${MAX_BYTES}" '$1 + 0 > limit { printf "%s\t%s\n", $1, $2 }' \
        | sort -rn)"

    reported=""
    if [ -n "${oversized}" ]; then
        while IFS=$'\t' read -r size path; do
            [ -n "${path}" ] || continue
            if is_allowlisted "${path}"; then
                continue
            fi
            reported="${reported}$(printf '  %8.2f MB  %s' "$(awk -v s="${size}" 'BEGIN { print s / 1048576 }')" "${path}")"$'\n'
            violations=$((violations + 1))
        done <<<"${oversized}"
    fi

    if [ -n "${reported}" ]; then
        printf 'Tracked files over %s bytes (%s MB):\n\n' "${MAX_BYTES}" "$((MAX_BYTES / 1024 / 1024))"
        printf '%s' "${reported}"
        cat <<'EOF'

A git repository never forgets. Before committing a large file, ask whether it
should instead be:
  * downloaded at build time (see scripts/fetch-models.sh for the pattern)
  * attached to a GitHub Release
  * generated by the build
  * simply smaller (compress the asset, crop the image, trim the fixture)

Untrack it:
  git rm --cached <path>
and add a matching .gitignore rule in the same commit.
EOF
        printf '\n'
    fi
fi

# --- verdict ---------------------------------------------------------------

if [ "${violations}" -gt 0 ]; then
    printf 'FAILED: %s repository hygiene violation(s).\n' "${violations}" >&2
    exit 1
fi

printf 'check-repo-size: clean (limit %s MB)\n' "$((MAX_BYTES / 1024 / 1024))"
exit 0

# Security Policy

OllamaMobile runs untrusted model files through native code, speaks HTTP to servers on the local
network, and can serve an HTTP API from the phone. Each of those is a real attack surface and each
is described below rather than glossed over.

This project is maintained by one person in their own time. The commitments here are deliberately
modest so that they are ones I can actually keep.

## Supported versions

Pre-1.0, only the latest `0.x` minor is supported. There are no long-term-support branches and no
backports: if `0.4.x` is current, a fix lands in `0.4.x` and nothing older is patched.

| Version | Supported |
| ------- | --------- |
| Latest `0.x` minor (currently the `0.1.x` line) | Yes |
| Any earlier `0.x` minor | No — upgrade |

Once 1.0 exists this table will be replaced with something less brutal. Until then, running an old
pre-release build means running unpatched code.

## Reporting a vulnerability

**Do not open a public issue for a security problem.**

Use GitHub's private vulnerability reporting:
[Report a vulnerability](https://github.com/jaypetez/ollama-mobile/security/advisories/new). It
creates a private advisory that only the maintainer can see, and it is the preferred route because
the fix, the CVE and the disclosure all live in one place.

If that is not available to you, email **jayson@shoe4africa.org**. Please put "OllamaMobile
security" in the subject. Unencrypted email is acceptable — say so if you would rather arrange
another channel first.

Useful reports include: the affected version or commit, the module, what an attacker can achieve,
and the smallest reproduction you can manage. A crashing GGUF file, a `curl` invocation or a failing
test is worth more than a paragraph of description.

### What to expect

| Stage | Target |
| ----- | ------ |
| Acknowledgement that a human has read it | 5 working days |
| Initial assessment: valid, severity, likely fix shape | 14 days |
| Fix released for a high-severity issue | 30 days where practical |
| Fix released for anything else | Next release; no date promised |
| Public advisory | After a fix ships, or 90 days after the report, whichever comes first |

These are targets from a solo maintainer, not an SLA. If a deadline is going to slip you will be
told before it slips rather than after. Credit in the advisory is offered by default; say if you
would rather not be named.

There is no bug bounty. There is no money involved in this project in any direction.

## Automated scanning

Running in CI from `.github/workflows/security.yml` and `.github/workflows/scorecard.yml`, results
visible in the repository's Security tab:

* **CodeQL (java-kotlin)** — static analysis of Kotlin and Java on pull requests, on pushes to
  `main`, and weekly on a schedule. This is the one that gates day-to-day work.
* **CodeQL (c-cpp)** — **weekly only, never on a pull request**, because a c-cpp database would
  cover the whole of upstream llama.cpp and add tens of minutes to every review. It **currently
  does nothing at all**: there is no C or C++ in this repository — no `core-llm/src/main/cpp/`, no
  `third_party/llama.cpp` — so the job detects their absence and exits successfully without
  analysing anything. It starts producing real results when the native code lands.
* **gitleaks** — secret scanning. On a pull request it scans the commits in the PR; on the weekly
  schedule it scans the entire history, which is the run that catches a credential committed before
  this workflow existed.
* **Dependency review** — runs on pull requests. Known gap: it diffs GitHub's dependency graph, and
  GitHub does not parse Gradle builds, so the graph for this repository is empty and the check
  passes trivially. Making it meaningful requires submitting the resolved graph from a Gradle job,
  which is not wired up yet. Until it is, OSV below is the real dependency coverage.
* **OSV scanner** — vulnerability matching against the resolved Gradle dependency set. Not run on
  pull requests; runs on pushes to `main`, weekly, and on demand.
* **OpenSSF Scorecard** — supply-chain posture: pinned actions, branch protection, token
  permissions.

None of these prove the absence of the bugs described below; they catch the classes of problem that
are cheap to catch automatically. Note also that they can only analyse code that exists — see the
next section for how little of the design below is currently code.

## Threat model

**Status: almost nothing in this section is code at `0.1.0`.** `:server`,
`:core-common`, `:core-remote`, `:core-download`, `:core-storage`, `:core-ml`, `:core-data` and
`:core-llm` contain no Kotlin sources, and there is no C or C++ in the repository at all. So there
is no HTTP server to expose, no `LanOnlyGuard` to enforce, no GGUF loader to attack and no secret
store to break into.

That is not a reason to leave this section out. It is the specification the implementation will be
held to, with the enforcement point named for each control, written down before the code so that the
code can be reviewed against it. Verbs below are future tense wherever the present tense would state
something false — the same convention as the Features section of [README.md](README.md), and as the
`Status` admonitions on the documentation site. When a control lands, its verb changes and a row
moves in [docs/verification-status.md](docs/verification-status.md); a control described here that
turns out not to be enforced in shipped code is a bug worth reporting, and will be treated as one.

The two claims in this document that *are* true today are the "no telemetry" property (verifiable
from the dependency inventory — nothing of the sort is declared anywhere) and the backup-exclusion
rules, which are in the manifest resources.

Overall posture: the app has no server-side component, no account system and no telemetry, so there
is no backend to breach and no data to leak from one. Everything that matters is intended to happen
on the device or on a server the user chose.

### The embedded HTTP server

`:server` is to run a Ktor CIO server on the phone speaking the Ollama protocol. The module is empty
at 0.1.0; the design is:

* **It will bind to loopback by default.** In that configuration only apps on the same device can
  reach it. That is not zero risk — another local app can talk to it — but the exposure is bounded
  by the device.
* **LAN exposure will be opt-in, per session, and token-gated.** Turning it on generates a random
  bearer token; requests without it are rejected. The token is generated, not user-chosen, so there
  is no weak-password path, and it must not persist silently across an off/on cycle.
* **A Host guard will reject non-private hosts.** Requests whose `Host` header does not resolve to a
  private address are refused. This is what defeats DNS rebinding and the browser-as-confused-deputy
  attack, where a page on some remote site points a script at the phone's address; the origin
  check alone would not be enough, since a rebound name looks like the attacker's own host.
* CORS, request-size limits and structured error pages are to be configured centrally in the server
  module rather than per route, so a new endpoint inherits them.
* **What is not claimed:** the server is HTTP, not HTTPS. A LAN-exposed server would send and
  receive in cleartext on the local network, and the bearer token travels with it. Exposing it on an
  untrusted network — a café, a hotel, a conference — will not be a supported configuration.

### Cleartext HTTP to LAN Ollama servers

An Ollama server on a Raspberry Pi is plain HTTP on port 11434, and no amount of documentation will
change that. The app therefore permits cleartext, deliberately. The manifest side of this is real
and present; the code side described below is not written.

`android:usesCleartextTraffic` / the network security config could restrict this only by hostname or
IP literal. **A network security config cannot express a CIDR range** — `<domain>` accepts names and
literal addresses, and the hosts a user will add are not known at build time. Writing
`cleartextTrafficPermitted="false"` with a hand-listed exception set is not achievable here.

So the restriction is to live in code, in `:core-common`'s `LanOnlyGuard`, applied at three layers of
the single shared OkHttp client. **Status: `:core-common` is empty at 0.1.0 — `LanOnlyGuard` does
not exist.** The intended design:

* **`Dns`** — resolution is checked, so a name that resolves off-LAN never becomes a connection.
* **`Interceptor`** — the request URL is checked against the live network policy (offline, LAN-only,
  unrestricted) before the call proceeds.
* **`EventListener.connectStart`** — the actual socket address is checked at connect time, which
  catches a DNS response that changed between resolution and connection (rebinding) and any path
  that bypassed the earlier layers.

This is strictly more expressive than the manifest could be: it can consult the current policy, work
out the device's actual subnet, and reject after resolution. A Konsist architecture test in
`:core-common` is to fail the build if a second `OkHttpClient`, a custom `TrustManager` or a bare
`Socket` appears anywhere in the codebase, because any of those would route around the guard — that
test is not written either. In debug builds user-installed CAs will be trusted so a developer can
proxy with mitmproxy; release builds will trust the system store only.

Residual risk, stated plainly: traffic to a LAN Ollama server is unauthenticated cleartext and
anyone on that network can read and modify it. The guard limits *where* the app will send cleartext.
It does not make cleartext safe.

### Untrusted GGUF model files

**Status: not reachable at 0.1.0** — there is no native code and no downloader. This will be the
highest-severity surface in the project once there is.

A GGUF file is an attacker-controlled binary that will be parsed and memory-mapped by C++ inside the
app's process. A malformed tensor header, a hostile metadata key-value block or an inconsistent
offset table is a memory-safety bug away from code execution with the app's permissions. Model files
routinely come from third-party hosting and are shared casually, and users do not think of them as
executables — but for this purpose they are.

Planned mitigations:

* Downloads will be checksum-verified against the catalogue entry before a file is ever opened by
  native code.
* Structural validation will happen in Kotlin before the file reaches the native loader, so
  obviously malformed inputs are rejected outside the C++ parser.
* Backend failures will be recorded in a quarantine ledger in `:core-ml` so a file that crashes the
  loader is not retried into a crash loop.
* Native loading will happen in the app's own sandbox with no permissions beyond what the app holds;
  the app requests no storage, camera, location or contacts permissions, which bounds what a
  successful exploit reaches. The permission set is real today and is the one item in this list that
  can be checked from the manifest.

Not claimed: no fuzzing corpus exists, no native code exists, and nothing has been executed on arm64
hardware. When this app can load a model, sideloading a GGUF from an untrusted source will be the
riskiest thing a user can do with it, and the UI must say so. Reports in this area will be the most
valuable ones you can send.

### Secrets at rest

**Status: not implemented at 0.1.0.** There is no credential store, because there is nothing to
store credentials for; `:core-storage` and `:core-remote` are empty. The design:

Per-server credentials — bearer tokens, API keys — and the generated LAN token are to be encrypted
with an AES-GCM key held in the **Android Keystore**, so the key material is non-exportable and, on
hardware that supports it, lives in the TEE or a secure element. Ciphertext will live in DataStore;
the key never leaves the Keystore.

Backup exclusion, by contrast, **is** in place already, and is enforced in both places Android needs
it. `backup_rules.xml`
(`<full-backup-content>`) and `data_extraction_rules.xml` (both `<cloud-backup>` *and*
`<device-transfer>`, which are evaluated independently) exclude the models directory, the download
scratch area, RAG vectors, logs, crash dumps, all external storage and the secrets preferences file.
Two reasons: Android's backup quota is 25 MB and one un-excluded multi-gigabyte GGUF silently
disables backup for the entire app, and Keystore-wrapped secrets are device-bound, so restoring them
onto another device produces undecryptable garbage rather than working credentials.

### Explicitly out of scope

These are real risks. They are not ones this app can defend against, and pretending otherwise would
be dishonest:

* **A rooted or compromised device.** Root defeats the app sandbox and the Keystore's software
  guarantees. If the platform is owned, nothing here helps.
* **A malicious Ollama server the user chose to add.** A configured server can return whatever it
  likes, including hostile model output. The app validates and bounds what it parses, but choosing
  which server to trust is the user's decision and the app cannot second-guess it.
* **Physical access to an unlocked device.** Anyone holding an unlocked phone has the app's data.
  Device lock and disk encryption are the platform's job.
* **Attacks on llama.cpp itself.** Upstream vulnerabilities are upstream's to fix; this project's
  responsibility is to update the submodule promptly and to say which version is bundled. There is
  no submodule yet. When there is, the only mechanism permitted to move it is
  `.github/workflows/llamacpp-bump.yml` (locally, `scripts/update-llamacpp.sh`) — Dependabot's
  `gitsubmodule` ecosystem is deliberately switched off so the two cannot race.
* **The user's own network.** If someone hostile is on the LAN, cleartext traffic to a LAN server is
  readable by them. That is a property of the network, not of the app.

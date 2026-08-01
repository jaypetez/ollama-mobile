# Privacy

**OllamaMobile collects nothing.**

Not anonymised usage statistics. Not crash reports. Not a device identifier. Not
an installation ping. There is no analytics SDK in the binary, no
crash-reporting service, no A/B framework, no advertising identifier, and no
first-party endpoint the app reports to — because there is no OllamaMobile
server anywhere. Nobody, including the author, learns that you installed this
app, ran a model, or asked it anything.

This is not a policy that could be relaxed by flipping a configuration flag.
There is no such code to flip.

Last updated: **2026-07-31**, for version **0.1.0**.

## What leaves the device, and exactly when

Two categories, both initiated by you. Nothing else.

### 1. Requests to servers you added

When you add a remote server and send a message, the app sends that
conversation to that server. That is the feature.

| What is sent | To whom | When |
| --- | --- | --- |
| Your prompt and the conversation context the model needs | The server you configured | When you send a message routed to that server |
| The model name and generation parameters | Same | Same |
| Whatever RAG context you attached | Same | Same, if RAG is enabled for that conversation |
| A model-list request, a health probe | Same | When you open the server or model screen |
| A discovery probe (Ollama's default port) | Addresses on your local subnet | Only while you are running a network scan |

The app adds nothing to these requests: no identifier, no user agent
fingerprint, no side-channel report. It sends what the Ollama or OpenAI-compatible
API requires and nothing more.

!!! warning "A remote server sees your prompts"
    This is inherent to using one, not a property of this app. If the server is
    your own Raspberry Pi, your prompts stay in your house. If you point the app
    at a hosted endpoint, that provider sees your conversations under their
    policy, not this one. The app shows you which server a conversation is
    routed to. See [Routing](remote/routing.md).

### 2. Model downloads you started

When you download a model, the app fetches it from Hugging Face or from a URL
you supplied. That request goes to that host and includes what an HTTP download
requires — the URL, range headers for resumption, and standard protocol
headers. Nothing about you or your conversations is attached.

Browsing the model catalogue may fetch metadata from the same source; that
request also happens only when you open the relevant screen.

### That is the complete list

There is no third category. No periodic check-in, no update check, no remote
configuration fetch, no certificate reporting, no font or icon fetched from a
CDN at runtime. The app makes no request at rest.

## What stays on the device

Everything else. All of it in app-private storage:

| Data | Where | Leaves the device? |
| --- | --- | --- |
| Conversations, messages, generation metadata | Room database | Never |
| Settings, server list, credentials for servers you added | DataStore | Never |
| Downloaded and imported GGUF model files | App-private files | Never |
| RAG documents, chunks and embeddings | Room and files | Only as prompt context, to the server you routed that conversation to |
| Logs | In memory, and on disk only in debug builds | Never automatically. See below. |

Local inference is exactly what it sounds like: when a conversation is routed to
the on-device engine, the prompt is handled by a native library in this
process and **no network request occurs at all**. You can verify this the
obvious way — turn on airplane mode and keep chatting.

## Logs and diagnostics

The app keeps a local log for troubleshooting, and debug builds include an API
inspector that shows the requests the app made. Both exist so *you* can see what
the app is doing.

Neither is uploaded. There is no "send diagnostics" button that transmits
anything, because there is nowhere for it to go. If you want to attach a log to
a GitHub issue you have to export and post it deliberately — and you should read
it first, since a log may contain server addresses and, in verbose modes,
message content.

## Permissions and why each one exists

| Permission | Why |
| --- | --- |
| `INTERNET` | Talk to servers you added; download models you chose. |
| `ACCESS_NETWORK_STATE` | Know whether there is a network, and whether it is metered, before starting a large download. |
| `ACCESS_WIFI_STATE` | Determine the local subnet for discovery and for the LAN-only guard. |
| `POST_NOTIFICATIONS` | Show progress for downloads, generation and the embedded server. |
| `WAKE_LOCK` | Keep generating while the screen is off — held for the generation window only, never longer. |
| `FOREGROUND_SERVICE` and its typed variants | Downloads, generation and the server are user-visible ongoing work; Android requires a typed foreground service for each. |

There is no location permission, no contacts permission, no storage permission
beyond the app's own sandbox, and no camera or microphone access.
`ACCESS_LOCAL_NETWORK` is declared for forward compatibility with Android's
runtime local-network permission and is inert on current targets; see
[Requirements](getting-started/requirements.md) for why `targetSdk` stays at 36.

## Backups

The conversation database and any stored credentials are excluded from Android's
cloud backup. Your chat history should not end up in a Google account because a
platform default was left on. Model files are excluded too — they are large and
re-downloadable.

## Children

The app is not directed at children and collects no data from anyone,
irrespective of age.

## The embedded server

If you enable the HTTP server, the phone starts listening on your local network.
That is an inbound surface, and it is the one feature that can expose the
device to other machines. It is off by default, never starts on its own, and
[Enabling LAN access](server/enabling-lan.md) explains what turning it on means.
Requests it receives are handled locally; enabling it does not send anything
anywhere.

## Changes to this document

If a future version ever added data collection, it would require a code change
visible in a public commit, a new dependency visible in the version catalogue,
and an update to this page in the same release. The repository history is the
audit trail. The project's position is that no such change will be made.

## Verification

You do not have to take this on trust. In rough order of effort:

- **Airplane mode.** Local inference keeps working. Nothing else does.
- **The in-app API inspector** (debug builds) lists every request the app made.
- **Proxy the traffic.** Debug builds trust user-installed CAs precisely so you
  can point the app at mitmproxy and watch.
- **Read the dependency list.** `gradle/libs.versions.toml` is the complete
  inventory. There is no analytics library in it.
- **Read the manifest.** No exported components beyond the launcher activity.

[Verification status](verification-status.md) is honest about which of these
claims have been checked by hand and which are verified by inspection.

## Contact

<jayson@shoe4africa.org>

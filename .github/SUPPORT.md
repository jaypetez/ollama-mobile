# Support

OllamaMobile is maintained by one person in spare time. Answers are best-effort;
a well-formed report gets one much faster than a vague one.

Version 0.1.0 is an early scaffold. Chat, on-device inference, the remote Ollama
client, model downloads, settings, and the built-in server are not implemented
yet, so questions about them are answered as "how it is intended to work", not
"how it behaves on your phone". See
[docs/verification-status.md](../docs/verification-status.md) for what is actually
verified.

## Ask a question — use Discussions

[GitHub Discussions](https://github.com/jaypetez/ollama-mobile/discussions) is the
right place when you are not yet sure something is broken:

- setup and installation help (the app is distributed through
  [GitHub Releases](https://github.com/jaypetez/ollama-mobile/releases) only — there is
  no Play Store or F-Droid build);
- "will this model run on my phone?" and other capability questions;
- connecting to a remote Ollama server on a Pi, a NAS, or over Tailscale;
- open-ended feature ideas that are not yet a concrete proposal;
- anything where the honest first sentence is "I might be holding it wrong".

If a discussion turns out to be a real defect, an issue gets opened from it with
the details already gathered.

## File an issue — when you can describe a defect

Use the [issue forms](https://github.com/jaypetez/ollama-mobile/issues/new/choose).
Each one asks for the specific facts that type of problem needs:

| Form | Use it for |
| --- | --- |
| Bug report | Wrong behaviour, crash, or hang you can describe steps for |
| Feature request | A concrete proposed change, not an open question |
| Model compatibility | One GGUF model fails to load, produces garbage, or crashes |
| Performance regression | Inference measurably slower between two versions |
| Documentation | Docs that are wrong, missing, or misleading |
| Remote Ollama server | The app cannot reach or correctly talk to your server |

Blank issues are turned off. Every form maps to something the maintainer can act
on, and the fields are the minimum needed to avoid a round trip.

## Why you will always be asked for logs

The app has **no telemetry**: no analytics SDK, no crash reporting service, no
usage pings. That is a deliberate, permanent constraint, and the direct cost is
that nothing about your crash ever reaches the maintainer on its own. A pasted
logcat is the entire evidence base.

```
adb logcat -c                      # clear, then reproduce the problem
adb logcat -d > ollama-mobile.log  # dump immediately afterwards
```

Skim it before posting — logs can contain server hostnames, Tailscale addresses,
local file paths, and prompt text.

## Two things worth knowing before you report

**There is no physical arm64 test device.** Development and CI run on the x86_64
emulator. Anything specific to real hardware — a particular SoC, thermal
throttling, vendor OpenCL or NPU drivers, actual on-device inference speed —
cannot be reproduced locally, so your report may be the only data that exists.
Detail matters correspondingly more, and please do not expect a same-day fix for
hardware-specific behaviour.

**No performance numbers are published.** For the same reason, the project does
not claim tokens-per-second figures. There is no in-app benchmark yet either — one
is planned, and until it ships any figure you report should say how you measured
it. Measurements from your own device are welcome and are the only numbers anyone
should trust.

## Security

Do not report vulnerabilities in issues or discussions. Follow
[SECURITY.md](../SECURITY.md); the contact address is <jayson@shoe4africa.org>.

## Contributing

If you want to fix it yourself, see
[CONTRIBUTING.md](../CONTRIBUTING.md). The short version: the gates that must
pass are `./gradlew spotlessCheck lintDebug test checkModuleGraph`.

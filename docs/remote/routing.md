# Local vs remote routing

OllamaMobile can answer a prompt with the on-device engine or with a remote
Ollama server. Deciding which, per request, is a policy — and the policy has to
be predictable, because a user who cannot tell where their conversation is being
processed cannot make an informed decision about privacy or about battery.

!!! note "Status"
    Not implemented. `:core-data` is the module this will live in; it is
    registered but empty. Multi-server load balancing is explicitly future work
    and is flagged as such below.

## The five modes

The mode is a user setting with a sensible default, overridable per conversation.

**`LOCAL_ONLY`** — never leave the device. If no local model is loaded or the
memory verdict is Refuse, the request fails with an explanation rather than
falling back. This is the mode for anyone whose reason for using the app is that
nothing leaves the phone, and its value depends entirely on it having no
exceptions. A "local only, except when it can't" mode is not local only.

**`REMOTE_ONLY`** — always use a configured server. Fails if none is reachable.
This is the mode for a phone that cannot realistically run a model — 4 GB of RAM,
`isLowRamDevice()` true — and for anyone who simply wants their home server's
larger model.

**`PREFER_LOCAL`** — try local, fall back to remote. The privacy-leaning default:
requests stay on the device unless the device cannot serve them.

**`PREFER_REMOTE`** — try remote, fall back to local. The battery- and
quality-leaning default: use the machine with a power supply and a bigger model
when it is available, and degrade to the phone when it is not.

**`AUTO`** — score both and pick. Described below.

The fallback in `PREFER_*` must be **visible**. A message answered by the
fallback path is labelled with where it was answered, in the message itself and
not only in a settings screen. Silent fallback from local to remote is a privacy
failure regardless of intent: the user believed the text stayed on the phone and
it did not.

Fallback must also be bounded. One attempt at the preferred target, then one at
the other, then fail. A retry loop that alternates between a loading local model
and a flaky server produces a request that never completes and a phone that gets
hot.

## What `AUTO` scores

`AUTO` is the only mode that needs a model of "better". The inputs, roughly in
order of how much they should matter:

**Can each side serve this request at all?** This is a filter, not a score. A
local answer requires a compatible model present on the device and a memory
verdict of Fits or Tight (see [memory](../local-inference/memory.md)). A remote
answer requires a configured, reachable, non-circuit-broken server that has the
requested model. Anything failing its filter scores nothing and is not compared.

**Model capability.** If the conversation needs a tool-calling model, or vision,
or a context window longer than the local model supports, the side that can
actually do it wins outright. This dominates every performance consideration —
a fast wrong answer is not a better answer.

**Prompt size.** Prompt processing is the compute-heavy phase, and it is where
a server with real hardware most outclasses a phone. A long prompt (a RAG
context, a pasted document) shifts the balance towards remote much more than a
short one does.

**Device state.** Battery level and whether the device is charging; thermal
status from `PowerManager` — at `THERMAL_STATUS_SEVERE` the local option should
score close to zero, because running inference on a throttling SoC is slow *and*
makes the phone unpleasant to hold. Whether a local model is already loaded
matters too: a cold load is expensive enough to change the answer for a
single short request.

**Network state.** Metered vs unmetered, from `NetworkCapabilities`. Sending a
long RAG context over a metered cellular link to save battery is a poor trade.
Measured round-trip latency to the server, from the health probe, is a better
signal than "connected or not".

**Observed server performance.** The native API returns real timing statistics on
every response (see [the API page](ollama-api.md)), so tokens/sec and
time-to-first-token for a given server and model are *measured*, not guessed. A
decaying average of recent observations is a far better input than any static
configuration.

Two design rules for the scorer. First, it must be **explainable**: whatever it
decides, a diagnostic view should be able to say "remote, because the prompt is
4200 tokens and the device is at THERMAL_STATUS_MODERATE". An unexplainable
router is one nobody trusts and everybody turns off. Second, it must be
**stable**: hysteresis, so that a marginal score difference does not flip the
target between consecutive turns of the same conversation. Switching engines
mid-conversation changes the KV cache, the template rendering and the sampling
behaviour, and the user perceives it as the model becoming inconsistent.

The weights are not yet chosen, and choosing them honestly requires measurement
the project cannot currently do. Shipping `AUTO` with invented weights would be
worse than shipping only the four explicit modes.

## Circuit breaking

A server that is down must not be retried on every request. Three states:

**Closed** — normal. Requests go through. Consecutive failures are counted.

**Open** — after a threshold of consecutive failures, requests to this server
fail immediately without a network attempt, for a cooldown period. This is what
stops a dead server from adding its full connect timeout to every single request,
which is the difference between "remote is unavailable" and "the app is broken".

**Half-open** — after the cooldown, one probe request is allowed through. Success
closes the breaker; failure reopens it with a longer cooldown (exponential
backoff, capped).

Details that matter:

* **Only some failures should trip it.** Connection refused, timeout, TLS
  failure, 5xx — yes. A 404 for a model that does not exist, or a 400 for a
  malformed request, is a problem with the request, not the server; tripping the
  breaker on those makes one bad prompt disable the server.
* **A 401 should not trip it either.** It should surface as an auth problem and
  route the user to [auth settings](auth-tls.md), because retrying will never
  help.
* **Per server, not global.** One dead server must not disable a working one.
* **Reset on network change.** When the phone moves from cellular to the home
  Wi-Fi, every "unreachable" verdict formed on the previous network is stale.
  Listen for `ConnectivityManager` callbacks and close the breakers.
* **Surface the state.** A server shown as unavailable with a "retry now" action
  is honest. One that silently is not being used is confusing.

Complementing the breaker, a lightweight periodic health probe (`GET /api/version`
— the same one [discovery](discovery.md) uses) keeps the reachability picture
warm so the router is not making decisions from stale information. It should back
off aggressively when the app is backgrounded; a health check every few seconds
from a backgrounded app is a battery bug.

## Multi-server load balancing

Not implemented, and not scheduled. Recorded here so the shape is known when it
is.

The data model already accommodates it: servers are a list, not a single entry,
and each carries its own health state, breaker and observed performance history.
What is missing is a selection step between "which servers can serve this" and
"send it" — currently that step picks the single configured or highest-priority
server.

When it is added, the plausible policies are least-outstanding-requests (better
than round-robin when servers differ in speed, which they will — a Pi and a
desktop are not interchangeable), and model-affinity routing, which matters more
than raw balancing: sending a request to a server that already has that model
loaded avoids a cold load, and `/api/ps` tells you exactly which models are
resident where. `keep_alive` interacts with this — spraying requests across
servers keeps a model warm on none of them.

Until then, a user with several servers picks one, and the router only decides
between it and the local engine.

## Related

* [Memory budgeting](../local-inference/memory.md) — the local-side feasibility
  filter.
* [The Ollama native API](ollama-api.md) — where the performance statistics come
  from.
* [Discovery](discovery.md) and [auth](auth-tls.md) — how servers get into the
  list in the first place.

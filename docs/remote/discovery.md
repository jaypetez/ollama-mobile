# Finding servers on the network

!!! warning "Ollama does not advertise itself"
    Ollama publishes **no mDNS/DNS-SD service record**. There is no
    `_ollama._tcp.local`, no Bonjour registration, nothing for
    `NsdManager.discoverServices()` to find. If you write mDNS discovery code,
    it will work perfectly and find zero servers, every time.

    Anything that claims otherwise is describing a reverse proxy, a Home
    Assistant add-on, or a wrapper that someone added the advertisement to — not
    Ollama itself.

So discovery has to be active: probe addresses and see which ones answer. That
is a port scan of the user's own network, which is a thing to do carefully and
narrowly, not enthusiastically.

!!! note "Status"
    Not implemented yet. `:core-remote` has the dependencies in place; the
    sweep described here is a specification.

## The sweep

**Read the real prefix length.** Get the active network from
`ConnectivityManager`, then its `LinkProperties`, then the `LinkAddress` for the
IPv4 interface, and use `LinkAddress.getPrefixLength()`. Do not assume /24.
Plenty of home routers hand out /24, but corporate and university networks
routinely use /16, guest networks use /20 or wider, and some ISP-supplied
routers use /22. Assuming /24 on a /16 finds nothing outside your own third
octet and quietly fails; assuming /24 when the real prefix is /28 wastes 240
probes on addresses that cannot exist.

**Refuse anything wider than /22.** A /22 is 1022 usable hosts, which is already
a lot of connection attempts. A /16 is 65534, which is not a discovery sweep — it
is a network scan, it will take minutes, it will exhaust the socket table, and on
a managed network it will get the device flagged by whatever is watching. So:

```kotlin
if (prefixLength < 22) {
    return SweepRefused(
        reason = "Subnet /$prefixLength has ${hostCount(prefixLength)} addresses. " +
            "Automatic discovery is limited to /22 or narrower. Add the server manually.",
    )
}
```

Refuse loudly and route the user to manual entry. Silently scanning a /16
because the user tapped a button is not a defensible default, and neither is
silently doing nothing.

**Bound the concurrency.** Fire a fixed number of probes at a time — a few dozen,
not a thousand. Unbounded parallelism on Android will hit the per-process file
descriptor limit, and even below that limit a burst of hundreds of simultaneous
SYNs will be dropped by the Wi-Fi stack or treated as hostile by the AP. A
`Semaphore` around the probe coroutine, sized in the tens, is the whole
mechanism.

**Use a short connect timeout.** On a local subnet, a host that exists answers a
TCP SYN in single-digit milliseconds. A host that does not exist either produces
an immediate `ECONNREFUSED`/`EHOSTUNREACH` or nothing at all — and "nothing at
all" is the case that determines how long the sweep takes, because the timeout is
the only thing that ends it. A few hundred milliseconds is generous for a LAN.
Multiplied across 254 addresses with 32-way concurrency, that is a sweep that
finishes in a couple of seconds rather than a couple of minutes.

The probe itself should be a raw TCP connect to the candidate port, not an HTTP
request. Connecting is cheap and tells you whether anything is listening;
building an HTTP client per candidate is not.

**Confirm with `/api/version`.** A successful TCP connect on port 11434 means
*something* is listening, not that it is Ollama. Only after the connect succeeds
should the client issue:

```
GET http://<candidate>:11434/api/version
```

and require a 200 with a JSON body containing a `version` field. Anything else —
a timeout, an HTML page, a TLS handshake failure, a JSON body of the wrong shape
— is not an Ollama server and must not be offered as one. Presenting a random
device that happens to have 11434 open as a discovered server is how a user ends
up sending their conversation to something unexpected.

Skip the device's own address, the network address and the broadcast address.
Probing yourself finds [the embedded server](../server/endpoints.md), which is
correct but confusing to present as a "discovered remote server".

## Ports

11434 is Ollama's default and should be probed first and, by default, only.
Sweeping a second port doubles the cost of the sweep for a case that manual entry
handles perfectly well. If a "also try these ports" setting is added later it
should be an explicit list the user types, not a guessed range.

## VPN peers will not be found

!!! warning "Tailscale, WireGuard and friends are invisible to a subnet sweep"
    This is the most common real-world case and the sweep cannot help with it.

    A Tailscale peer sits on `100.64.0.0/10` (CGNAT space), reachable through the
    tunnel interface, not on the Wi-Fi link's subnet. The sweep enumerates
    addresses derived from the local `LinkAddress`, so a peer at `100.x.y.z` is
    not in the address set and is never probed. The same is true of a WireGuard
    peer on `10.x`, a machine reachable only through a corporate VPN, and
    anything behind a router the phone is not directly attached to.

    Worse, on Android a VPN typically becomes the *default* network. So the
    active network's `LinkProperties` may describe the tunnel rather than the
    Wi-Fi link — a `/32` or a `/10` — at which point the sweep either has nothing
    to enumerate or is refused for being too wide. Both are correct behaviours
    and both mean the same thing: discovery cannot find your server.

Because of this, **manual entry is a first-class path, not a fallback.** It gets
equal billing in the UI, not a "advanced" disclosure triangle. It must accept:

* a bare host or IP — `192.168.1.40`, `pi.local`, `pi.tailnet-name.ts.net`
* an explicit port — `192.168.1.40:11434`
* a full URL with scheme and path prefix — `https://ollama.example.org/ollama`

and it must normalise sensibly: default the scheme to `http` for an RFC 1918
literal and `https` otherwise, default the port to 11434 when the scheme is
`http` and none is given, preserve any path prefix so reverse-proxied
deployments work, and strip a trailing slash before appending `/api/…`.

Validation is the same `/api/version` probe the sweep uses, so a manually entered
server is confirmed by exactly the same criterion as a discovered one — and the
error when it fails should distinguish "nothing is listening" from "something is
listening but it is not Ollama" from "it is Ollama but it rejected us", because
the fixes are entirely different. The third case is
[an auth problem](auth-tls.md).

Names ending in `.local` are mDNS names and resolve through the platform
resolver; that works, and is not the same thing as Ollama advertising itself.

## Permissions and platform behaviour

The manifest declares `INTERNET`, `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE`.
`ACCESS_LOCAL_NETWORK` is not declared today.

This is why `targetSdk` is deliberately held at 36. Targeting 37 makes the
runtime local-network permission mandatory, which would put a system permission
prompt in front of the user before any onboarding has explained why the app wants
to look at their network. When the target is raised, discovery must ask for the
permission at the moment the user taps "scan", with an explanation, and must
degrade to manual entry on denial rather than becoming unusable.

Cleartext HTTP is permitted at the platform layer (see
`app/src/main/res/xml/network_security_config.xml`) because an Ollama server on a
Raspberry Pi is plain HTTP and the hosts are not known at build time. A network
security config cannot express "RFC 1918 only" — `<domain>` accepts hostnames and
IP literals, never CIDR ranges — so the real restriction is enforced in code.

## Presenting results

Show the address, the version string from `/api/version`, and the model count
from `/api/tags` if it is cheap to fetch. A list of bare IP addresses is not
useful; a list saying "192.168.1.40 — Ollama 0.x, 6 models" is.

Cache confirmed servers and re-probe them on reconnect rather than re-sweeping.
A sweep is a deliberate user action, not something to run on a timer.

## Related

* [The Ollama native API](ollama-api.md) — the `/api/version` probe.
* [Auth and TLS](auth-tls.md) — servers that answer but reject you.
* [Routing](routing.md) — what happens once you have more than one.

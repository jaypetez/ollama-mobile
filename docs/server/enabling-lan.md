# Enabling LAN access

The embedded server binds loopback by default. Making it reachable from other
machines is an explicit opt-in, because it changes the security posture of the
device in ways that are worth stating plainly rather than burying.

!!! warning "Status"
    Not implemented. This page specifies the intended behaviour of `:server`
    and the settings around it.

## Loopback by default

Out of the box the server binds `127.0.0.1` and nothing else. Only processes on
the phone can connect. There is no network exposure, so nothing on the Wi-Fi can
reach it and nothing needs a password.

This is not a crippled default to be worked around — it is genuinely useful.
Through `adb forward` a desktop can reach a loopback-bound server over USB
without a single packet touching the network:

```bash
adb forward tcp:11434 tcp:11434
curl http://127.0.0.1:11434/api/version
```

That covers development, scripting and debugging entirely. See
[client examples](client-examples.md).

## The opt-in

Turning on LAN access is a deliberate action with a confirmation step, not a
toggle in a list. The flow:

1. The user opens the server settings and enables "Allow access from other
   devices".
2. A dialog states, in plain language, what changes — the consequences below,
   not a link to them.
3. On confirmation, the app **generates a bearer token** and shows it, with a
   copy action and a QR code for convenience.
4. The server rebinds to `0.0.0.0` and the notification updates to show the
   reachable address.

Requirements that fall out of this:

**The token is generated, never chosen.** 256 bits from `SecureRandom`,
base64url-encoded. A user-chosen password on an unauthenticated-by-design
protocol would be weak, reused, and would create an illusion of a considered
security decision. Generation removes the choice.

**The token is mandatory when not on loopback.** Every route requires
`Authorization: Bearer <token>`, including `/` and `/api/version`. There is no
"LAN access without a token" configuration, because an open inference endpoint on
a network is an open inference endpoint regardless of how trusted the user
believes that network to be. Comparison must be constant-time — a naive `==` on a
token is a timing oracle, and a bearer token is exactly the kind of secret that
oracle recovers.

**The token can be rotated and revoked.** Rotation invalidates every configured
client immediately, which is the point. Turning LAN access off must also stop the
listener, not merely hide it.

**Binding is deliberate.** Prefer binding to the specific Wi-Fi interface address
over `0.0.0.0` where the platform allows it — `0.0.0.0` includes any interface
that appears later, including a VPN tunnel or a USB tether, which is broader than
what the user agreed to.

**The listener stops when the network changes.** If the phone leaves the Wi-Fi
network it was bound to, the server should stop rather than silently rebind on
whatever it lands on next. Consent was given for a specific network.

## The Host guard

!!! danger "Without this, any web page can drive your phone's model"
    A bearer token protects against someone typing your phone's IP into a
    client. It does not, on its own, protect against **DNS rebinding**.

    The attack: the user visits an ordinary web page. That page's JavaScript
    requests a hostname the attacker controls, which resolves first to the
    attacker's server (so the page loads) and then, seconds later and with a
    near-zero TTL, to `192.168.1.55` — the user's phone. The browser now
    considers requests to that hostname same-origin, so the same-origin policy
    does not stop them, and it sends them to the phone. Every request carries a
    `Host` header of `attacker.example` because that is the name the browser
    resolved.

    So: reject any request whose `Host` header is not an expected value. Accept
    `localhost`, `127.0.0.1`, `[::1]`, and the interface addresses the server is
    actually bound to, each with the configured port. Reject everything else with
    403 before routing, before authentication, before anything.

    A rebound request cannot forge the `Host` header — the browser sets it from
    the URL, and the attack depends on the URL carrying the attacker's hostname.
    That is what makes this check effective and why it must apply in loopback
    mode too: a rebinding attack against `127.0.0.1` from a page the user has
    open is exactly as feasible.

CORS is the same class of problem and gets the same treatment: closed by default,
and if opened, an explicit origin allow-list. Never `*`. A wildcard on a server
holding a bearer token means any page the user visits can read the responses.

## The consequences, stated plainly

These belong in the confirmation dialog, in this register:

**Anyone on this network can use your phone's model if they have the token.**
The token is the only thing standing between the server and every device on the
Wi-Fi — including guests, IoT devices, and anything already compromised.

**Traffic is unencrypted HTTP.** Prompts and responses cross the network in
plaintext. Anyone who can observe the network — the router's owner, another
device performing ARP spoofing, an administrator on a corporate or campus
network — can read the entire conversation. The server does not offer TLS,
because a self-signed certificate generated on a phone provides no meaningful
authentication and manufactures a false sense of protection.

**Do not enable this on a public or untrusted network.** Café Wi-Fi, hotels,
airports, conference networks and most corporate guest networks are all
environments where every other client is an unknown. On such networks client
isolation may prevent connections entirely, or may not.

**Inference costs battery and heat.** A remote client can keep the phone
generating for as long as it likes. Sustained load will drain the battery
noticeably and make the device hot enough to throttle — see
[tuning](../local-inference/tuning.md). There is no per-client rate limit.

**Model management is not exposed.** `/api/pull`, `/api/create`, `/api/delete`
and friends return 501, so a network client cannot fill your storage or delete
your models. See [endpoints](endpoints.md).

**Reachability is not the same as security.** A phone reachable from the LAN is
reachable from anything the LAN is bridged to — a badly configured router, a VPN
that joins two networks, a guest network that is not actually isolated.

The dialog should end with the safer alternative rather than only a warning: if
you only need access from your own computer, use `adb forward` over USB and leave
LAN access off.

## What is not offered, and why

**No UPnP or NAT-PMP port forwarding.** The app will never ask a router to expose
the phone to the internet. An inference server on a public IP with a bearer token
and no TLS is an incident waiting to happen, and making it a one-tap action would
be indefensible.

**No TLS.** Explained above. A user who needs encrypted remote access should put
the phone on a WireGuard or Tailscale network, which provides real
authentication and encryption at the network layer — and, incidentally, is also
why [discovery](../remote/discovery.md) cannot find VPN peers by sweeping.

**No anonymous read-only mode.** "Just `/api/tags` without a token" leaks the
model list, which is a fingerprint of the device and its owner's interests, for
no benefit.

## Related

* [Endpoints](endpoints.md) — what is exposed once it is on.
* [Client examples](client-examples.md) — connecting over USB and over the LAN.
* [Auth and TLS](../remote/auth-tls.md) — the same problems from the client side.

package io.github.jaypetez.ollamamobile.model

/**
 * What the app is permitted to talk to.
 *
 * Enforced in one place — `LanOnlyGuard` in `:core-common`, as an OkHttp
 * interceptor on the shared client — rather than checked at call sites. A
 * policy that each feature has to remember to consult is a policy that one
 * feature will forget, and the failure mode of forgetting is a request leaving
 * the device that the user believed could not.
 */
public enum class NetworkPolicy {
    /**
     * No outbound requests at all: no remote inference, no model downloads, no
     * catalogue, no update or discovery probes. On-device inference and the
     * embedded server's loopback interface still work. A blocked request fails
     * with [AppError.Policy.OfflineMode] rather than hanging.
     */
    OFFLINE,

    /**
     * Private-network destinations only — RFC 1918 (`10/8`, `172.16/12`,
     * `192.168/16`), CGNAT `100.64/10` so Tailscale works, link-local
     * `169.254/16`, unique-local IPv6 `fc00::/7`, and loopback. Anything that
     * resolves outside those ranges is refused with
     * [AppError.Policy.LanOnlyViolation].
     *
     * The check must run against the RESOLVED ADDRESS, not the hostname: a
     * public DNS name can resolve into a private range and a private-looking
     * name can resolve out of it, so a string match on the host is not a
     * control. This also means Hugging Face downloads are blocked in this mode,
     * which is the intent.
     */
    LAN_ONLY,

    /** Any destination. Remote servers, catalogue and downloads all work. */
    OPEN,
    ;

    /** Whether any outbound request may be made. */
    public val allowsNetwork: Boolean
        get() = this != OFFLINE

    /** Whether destinations outside the local network are permitted. */
    public val allowsPublicInternet: Boolean
        get() = this == OPEN
}

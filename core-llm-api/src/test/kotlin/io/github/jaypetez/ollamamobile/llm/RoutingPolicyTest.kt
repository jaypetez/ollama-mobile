package io.github.jaypetez.ollamamobile.llm

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class RoutingPolicyTest {
    @Test
    fun `LOCAL_ONLY forbids every remote target`() {
        assertThat(RoutingPolicy.LOCAL_ONLY.allowsRemote).isFalse()
        assertThat(RoutingPolicy.LOCAL_ONLY.allowsLocal).isTrue()
    }

    @Test
    fun `REMOTE_ONLY forbids every local target`() {
        assertThat(RoutingPolicy.REMOTE_ONLY.allowsLocal).isFalse()
        assertThat(RoutingPolicy.REMOTE_ONLY.allowsRemote).isTrue()
    }

    @ParameterizedTest
    @EnumSource(RoutingPolicy::class)
    fun `every policy permits at least one kind of target`(policy: RoutingPolicy) {
        assertThat(policy.allowsLocal || policy.allowsRemote).isTrue()
    }

    @Test
    fun `AUTO expresses no standing preference for local`() {
        // AUTO ranks by score, so "prefers local" would be a claim it cannot
        // keep. PREFER_LOCAL is the value that makes that claim.
        assertThat(RoutingPolicy.AUTO.prefersLocal).isFalse()
        assertThat(RoutingPolicy.PREFER_LOCAL.prefersLocal).isTrue()
        assertThat(RoutingPolicy.LOCAL_ONLY.prefersLocal).isTrue()
        assertThat(RoutingPolicy.PREFER_REMOTE.prefersLocal).isFalse()
    }

    @Test
    fun `the default is AUTO`() {
        assertThat(RoutingPolicy.Default).isEqualTo(RoutingPolicy.AUTO)
    }

    @Test
    fun `a persisted name round-trips, case-insensitively`() {
        RoutingPolicy.entries.forEach { policy ->
            assertThat(RoutingPolicy.fromNameOrNull(policy.name)).isEqualTo(policy)
            assertThat(RoutingPolicy.fromNameOrNull(policy.name.lowercase())).isEqualTo(policy)
        }
    }

    @Test
    fun `an unknown or missing name parses to null rather than to a default`() {
        // Null lets the caller decide between "never set" and "set to garbage";
        // silently substituting the default hides a corrupt settings row.
        assertThat(RoutingPolicy.fromNameOrNull(null)).isNull()
        assertThat(RoutingPolicy.fromNameOrNull("")).isNull()
        assertThat(RoutingPolicy.fromNameOrNull("LOCAL")).isNull()
    }
}

package io.github.jaypetez.ollamamobile.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class RoleTest {
    @Test
    fun `wire names are the lowercase spellings the API uses`() {
        assertThat(Role.entries.map { it.wireName })
            .containsExactly("system", "user", "assistant", "tool")
            .inOrder()
    }

    @ParameterizedTest
    @CsvSource(
        "system, SYSTEM",
        "user, USER",
        "assistant, ASSISTANT",
        "tool, TOOL",
    )
    fun `parses the canonical wire names`(wire: String, expected: Role) {
        assertThat(Role.fromWireOrNull(wire)).isEqualTo(expected)
    }

    @Test
    fun `round-trips every entry through its wire name`() {
        Role.entries.forEach { role ->
            assertThat(Role.fromWireOrNull(role.wireName)).isEqualTo(role)
        }
    }

    @ParameterizedTest
    @CsvSource(
        "ASSISTANT, ASSISTANT",
        "'  user  ', USER",
        "System, SYSTEM",
    )
    fun `is tolerant of case and surrounding whitespace`(wire: String, expected: Role) {
        assertThat(Role.fromWireOrNull(wire)).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        // Other servers speaking the same protocol use these spellings.
        "model, ASSISTANT",
        "ai, ASSISTANT",
        "function, TOOL",
        "developer, SYSTEM",
        "human, USER",
    )
    fun `maps known aliases from other server implementations`(wire: String, expected: Role) {
        assertThat(Role.fromWireOrNull(wire)).isEqualTo(expected)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "wizard", "USER_1", "toolcall"])
    fun `returns null for unknown values rather than throwing`(wire: String) {
        assertThat(Role.fromWireOrNull(wire)).isNull()
    }

    @Test
    fun `returns null for a missing role`() {
        assertThat(Role.fromWireOrNull(null)).isNull()
    }

    @Test
    fun `fromWire falls back instead of failing`() {
        // A chunk with no role at all is normal mid-stream: the role is only
        // sent on the first chunk, and everything after it is the assistant.
        assertThat(Role.fromWire(null)).isEqualTo(Role.ASSISTANT)
        assertThat(Role.fromWire("wizard")).isEqualTo(Role.ASSISTANT)
        assertThat(Role.fromWire("wizard", fallback = Role.USER)).isEqualTo(Role.USER)
        assertThat(Role.fromWire("tool", fallback = Role.USER)).isEqualTo(Role.TOOL)
    }
}

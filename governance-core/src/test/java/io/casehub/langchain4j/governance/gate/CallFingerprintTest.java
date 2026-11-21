package io.casehub.langchain4j.governance.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CallFingerprintTest {

    private static String fp(String tenant, String tool, String json) {
        return CallFingerprint.of(tenant, tool, CallFingerprint.parseArguments(json));
    }

    @Test
    void fingerprint_isLowercaseHexSha256() {
        assertThat(fp("acme", "freezeAccount", "{\"accountId\":\"A1\"}")).matches("[0-9a-f]{64}");
    }

    @Test
    void fingerprint_ignoresKeyOrderAndWhitespace() {
        assertThat(fp("acme", "t", "{\"b\":1,\"a\":{\"y\":2,\"x\":3}}"))
                .isEqualTo(fp("acme", "t", "{ \"a\": {\"x\":3, \"y\":2}, \"b\": 1 }"));
    }

    @Test
    void fingerprint_differsByTenant() {
        assertThat(fp("acme", "t", "{\"a\":1}")).isNotEqualTo(fp("globex", "t", "{\"a\":1}"));
    }

    @Test
    void fingerprint_differsByTool() {
        assertThat(fp("acme", "t1", "{\"a\":1}")).isNotEqualTo(fp("acme", "t2", "{\"a\":1}"));
    }

    @Test
    void fingerprint_differsByArgumentValue() {
        assertThat(fp("acme", "t", "{\"a\":1}")).isNotEqualTo(fp("acme", "t", "{\"a\":2}"));
    }

    @Test
    void fingerprint_tenantAndToolBoundariesCannotBeShifted() {
        assertThat(fp("ac", "met", "{}")).isNotEqualTo(fp("acm", "et", "{}"));
        assertThat(fp("a\nb", "c", "{}")).isNotEqualTo(fp("a", "b\nc", "{}"));
    }

    @Test
    void parseArguments_blank_isEmpty() {
        assertThat(CallFingerprint.parseArguments(null)).isEmpty();
        assertThat(CallFingerprint.parseArguments("  ")).isEmpty();
    }

    @Test
    void parseArguments_keepsValues() {
        assertThat(CallFingerprint.parseArguments("{\"amount\":49.99,\"provider\":\"Netflix\"}"))
                .containsExactly(Map.entry("amount", new java.math.BigDecimal("49.99")), Map.entry("provider", "Netflix"));
    }

    @Test
    void parseArguments_invalid_throws() {
        assertThatThrownBy(() -> CallFingerprint.parseArguments("not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseArguments_array_throws() {
        assertThatThrownBy(() -> CallFingerprint.parseArguments("[1,2]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gatedToolCall_copiesArguments() {
        java.util.HashMap<String, Object> args = new java.util.HashMap<>(Map.of("a", 1));
        GatedToolCall call = new GatedToolCall("agent", "tool", "tool", args, "acme");
        args.put("b", 2);

        assertThat(call.arguments()).containsOnlyKeys("a");
        assertThatThrownBy(() -> call.arguments().put("c", 3)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void parseArguments_duplicateKey_throws() {
        assertThatThrownBy(() -> CallFingerprint.parseArguments("{\"amount\":999,\"amount\":1}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseArguments_trailingContent_throws() {
        assertThatThrownBy(() -> CallFingerprint.parseArguments("{\"amount\":1}{\"amount\":999}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CallFingerprint.parseArguments("{\"amount\":1} trailing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fingerprint_distinguishesLargeAndPreciseNumbers() {
        assertThat(fp("acme", "t", "{\"a\":12345678901234567890.12}"))
                .isNotEqualTo(fp("acme", "t", "{\"a\":12345678901234567890.99}"));
        assertThat(fp("acme", "t", "{\"a\":1e400}")).isNotEqualTo(fp("acme", "t", "{\"a\":1e500}"));
    }
}

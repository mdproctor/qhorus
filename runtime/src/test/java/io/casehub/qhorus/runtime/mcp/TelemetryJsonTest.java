package io.casehub.qhorus.runtime.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelemetryJsonTest {

    static class TestableTools extends QhorusMcpToolsBase {
        TestableTools() {
            this.mapper = new ObjectMapper();
        }
    }

    private final TestableTools tools = new TestableTools();

    @Test
    void produces_valid_json_with_string_and_int_values() throws JsonProcessingException {
        String json = tools.telemetryJson("action", "topic-renamed", "count", 5);

        var mapper = new ObjectMapper();
        var node = mapper.readTree(json);
        assertThat(node.get("action").asText()).isEqualTo("topic-renamed");
        assertThat(node.get("count").asInt()).isEqualTo(5);
    }

    @Test
    void escapes_double_quotes_in_values() throws JsonProcessingException {
        String json = tools.telemetryJson("name", "topic with \"quotes\"");

        var mapper = new ObjectMapper();
        var node = mapper.readTree(json);
        assertThat(node.get("name").asText()).isEqualTo("topic with \"quotes\"");
    }

    @Test
    void escapes_backslashes_in_values() throws JsonProcessingException {
        String json = tools.telemetryJson("path", "C:\\Users\\test");

        var mapper = new ObjectMapper();
        var node = mapper.readTree(json);
        assertThat(node.get("path").asText()).isEqualTo("C:\\Users\\test");
    }

    @Test
    void escapes_mixed_special_characters() throws JsonProcessingException {
        String json = tools.telemetryJson("value", "line1\nline2\ttab \"quoted\" back\\slash");

        var mapper = new ObjectMapper();
        var node = mapper.readTree(json);
        assertThat(node.get("value").asText()).isEqualTo("line1\nline2\ttab \"quoted\" back\\slash");
    }

    @Test
    void handles_boolean_values() throws JsonProcessingException {
        String json = tools.telemetryJson("flag", true);

        var mapper = new ObjectMapper();
        var node = mapper.readTree(json);
        assertThat(node.get("flag").asBoolean()).isTrue();
    }

    @Test
    void handles_null_values() throws JsonProcessingException {
        String json = tools.telemetryJson("key", null);

        var mapper = new ObjectMapper();
        var node = mapper.readTree(json);
        assertThat(node.get("key").isNull()).isTrue();
    }

    @Test
    void preserves_key_order() throws JsonProcessingException {
        String json = tools.telemetryJson("action", "test", "old_name", "a", "new_name", "b", "count", 1);

        var mapper = new ObjectMapper();
        var node = mapper.readTree(json);
        var fields = node.fieldNames();
        assertThat(fields.next()).isEqualTo("action");
        assertThat(fields.next()).isEqualTo("old_name");
        assertThat(fields.next()).isEqualTo("new_name");
        assertThat(fields.next()).isEqualTo("count");
    }

    @Test
    void rejects_odd_argument_count() {
        assertThatThrownBy(() -> tools.telemetryJson("key"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package io.casehub.langchain4j.governance.gate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Identifies one tool call independently of how the model formatted it: the same tenant, tool and
 * arguments always give the same fingerprint, whatever the key order or spacing of the JSON.
 */
public final class CallFingerprint {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

    private CallFingerprint() {}

    /** Lowercase hex SHA-256 of {@code tenant \n tool \n canonical-JSON(arguments)}. */
    public static String of(String tenancyId, String toolName, Map<String, Object> arguments) {
        String material = tenancyId + "\n" + toolName + "\n" + canonicalJson(arguments);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Parses the model's tool-call arguments.
     *
     * @return the arguments by name; empty for null or blank input
     * @throws IllegalArgumentException when the input is not a JSON object
     */
    public static Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        JsonNode tree;
        try {
            tree = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("tool arguments are not valid JSON", e);
        }
        if (tree == null || !tree.isObject()) {
            throw new IllegalArgumentException("tool arguments must be a JSON object");
        }
        return MAPPER.convertValue(tree, MAP);
    }

    private static String canonicalJson(Map<String, Object> arguments) {
        try {
            // Re-reading as a tree and writing with sorted map keys sorts every nesting level.
            Object normalised = MAPPER.convertValue(arguments == null ? Map.of() : arguments, Object.class);
            return MAPPER.writeValueAsString(normalised);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("tool arguments cannot be serialised", e);
        }
    }
}

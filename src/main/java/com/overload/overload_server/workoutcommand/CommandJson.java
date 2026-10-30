package com.overload.overload_server.workoutcommand;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;

/** RFC 8785 serialization for the command schema's JSON subset: numbers are safe integers.
 * Decimal quantities and 64-bit counters must be strings. Not a general floating-point JCS API.
 */
public final class CommandJson {

	public static final int MAX_BYTES = 262_144;
	private static final BigInteger MAX_SAFE_INTEGER = BigInteger.valueOf(9_007_199_254_740_991L);
	private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxNumberLength(32)
					.maxStringLength(MAX_BYTES).build()).build())
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

	private CommandJson() { }

	public static String canonicalize(String input) { return canonicalize(read(input)); }

	static JsonNode read(String input) {
		if (input == null || input.length() > MAX_BYTES || input.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
			throw new IllegalArgumentException("Command JSON exceeds input limit");
		}
		try {
			var node = JSON.readTree(input);
			if (node == null) throw new IllegalArgumentException("Command JSON required");
			// Validate every primitive, including values not yet interpreted by a payload DTO.
			canonicalize(node);
			return node;
		} catch (tools.jackson.core.JacksonException failure) {
			throw new IllegalArgumentException("Invalid command JSON");
		}
	}

	static String canonicalize(JsonNode input) {
		var output = new StringBuilder();
		append(input, output);
		return output.toString();
	}

	private static void append(JsonNode node, StringBuilder output) {
		if (node.isObject()) {
			output.append('{');
			boolean first = true;
			for (var property : node.properties().stream().sorted(Comparator.comparing(java.util.Map.Entry::getKey)).toList()) {
				if (!first) output.append(',');
				first = false;
				string(property.getKey(), output);
				output.append(':');
				append(property.getValue(), output);
			}
			output.append('}');
		} else if (node.isArray()) {
			output.append('[');
			for (int index = 0; index < node.size(); index++) {
				if (index != 0) output.append(',');
				append(node.get(index), output);
			}
			output.append(']');
		} else if (node.isString()) {
			string(node.asString(), output);
		} else if (node.isIntegralNumber() && node.bigIntegerValue().abs().compareTo(MAX_SAFE_INTEGER) <= 0) {
			output.append(node.bigIntegerValue());
		} else if (node.isBoolean()) {
			output.append(node.booleanValue());
		} else if (node.isNull()) {
			output.append("null");
		} else throw new IllegalArgumentException("Command numbers must be JSON safe integers; use decimal strings");
	}

	private static void string(String value, StringBuilder output) {
		output.append('"');
		for (int index = 0; index < value.length(); index++) {
			char current = value.charAt(index);
			if (Character.isHighSurrogate(current)) {
				if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) throw unicode();
				output.append(current).append(value.charAt(++index));
				continue;
			}
			if (Character.isLowSurrogate(current)) throw unicode();
			switch (current) {
				case '"' -> output.append("\\\"");
				case '\\' -> output.append("\\\\");
				case '\b' -> output.append("\\b");
				case '\t' -> output.append("\\t");
				case '\n' -> output.append("\\n");
				case '\f' -> output.append("\\f");
				case '\r' -> output.append("\\r");
				// NUL is not representable in PostgreSQL text/JSONB, even though generic JCS allows it.
				case 0 -> throw new IllegalArgumentException("NUL is not supported in workout commands");
				default -> {
					if (current < 32) output.append("\\u00").append(Character.forDigit(current >> 4, 16)).append(Character.forDigit(current & 15, 16));
					else output.append(current);
				}
			}
		}
		output.append('"');
	}

	private static IllegalArgumentException unicode() { return new IllegalArgumentException("Invalid Unicode in command"); }
}

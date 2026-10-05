/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads a JSON/YAML object strictly: every value must have the expected type (no string-to-number
 * coercion), and every key must be consumed. {@link #finish()} fails on any key nobody asked for, so
 * a typo such as {@code stepTimoutMs} is an error with its full path, not a silent default.
 */
final class StrictNode {

    private final JsonNode node;
    private final String path;
    private final Set<String> consumed = new HashSet<>();

    StrictNode(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw new InvalidPolicyException(path + ": expected an object");
        }
        this.node = node;
        this.path = path;
    }

    String path() {
        return path;
    }

    StrictNode object(String key) {
        return new StrictNode(take(key), child(key));
    }

    int integer(String key) {
        JsonNode value = take(key);
        if (!value.isInt()) {
            throw new InvalidPolicyException(child(key) + ": expected an integer, got " + describe(value));
        }
        return value.intValue();
    }

    double number(String key) {
        JsonNode value = take(key);
        if (!value.isNumber()) {
            throw new InvalidPolicyException(child(key) + ": expected a number, got " + describe(value));
        }
        return value.doubleValue();
    }

    boolean bool(String key) {
        JsonNode value = take(key);
        if (!value.isBoolean()) {
            throw new InvalidPolicyException(child(key) + ": expected true or false, got " + describe(value));
        }
        return value.booleanValue();
    }

    String text(String key) {
        JsonNode value = take(key);
        if (!value.isTextual()) {
            throw new InvalidPolicyException(child(key) + ": expected text, got " + describe(value));
        }
        return value.textValue();
    }

    <E extends Enum<E>> E enumValue(String key, Class<E> type) {
        return parseEnum(text(key), type, child(key));
    }

    <E extends Enum<E>> List<E> enumList(String key, Class<E> type) {
        JsonNode value = take(key);
        if (!value.isArray()) {
            throw new InvalidPolicyException(child(key) + ": expected a list, got " + describe(value));
        }
        List<E> out = new ArrayList<>();
        for (int i = 0; i < value.size(); i++) {
            JsonNode item = value.get(i);
            String itemPath = child(key) + "[" + i + "]";
            if (!item.isTextual()) {
                throw new InvalidPolicyException(itemPath + ": expected text, got " + describe(item));
            }
            E parsed = parseEnum(item.textValue(), type, itemPath);
            if (out.contains(parsed)) {
                throw new InvalidPolicyException(itemPath + ": duplicate value " + parsed);
            }
            out.add(parsed);
        }
        return out;
    }

    /** The object's keys in document order, for maps keyed by name. Reading each one consumes it. */
    List<String> keys() {
        List<String> out = new ArrayList<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    /** Fails if the object has keys that were never read. Call after reading every expected field. */
    void finish() {
        List<String> unknown = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            String key = it.next();
            if (!consumed.contains(key)) {
                unknown.add(child(key));
            }
        }
        if (!unknown.isEmpty()) {
            throw new InvalidPolicyException("unknown policy field(s): " + String.join(", ", unknown));
        }
    }

    private JsonNode take(String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            throw new InvalidPolicyException(child(key) + ": required but missing");
        }
        consumed.add(key);
        return value;
    }

    private String child(String key) {
        return path + "." + key;
    }

    private static <E extends Enum<E>> E parseEnum(String raw, Class<E> type, String path) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new InvalidPolicyException(path + ": '" + raw + "' is not one of "
                    + Arrays.toString(type.getEnumConstants()).toUpperCase(Locale.ROOT));
        }
    }

    private static String describe(JsonNode value) {
        return value.getNodeType().toString().toLowerCase(Locale.ROOT) + " " + value;
    }
}

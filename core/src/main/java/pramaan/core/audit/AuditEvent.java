/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.audit;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * One audit record: a type and a small set of scalar fields. By construction it cannot carry biometric
 * data or names (Build Map #10): values are short strings, numbers or booleans only, and field names
 * that suggest images, frames, templates or personal names are refused.
 */
public final class AuditEvent {

    /** Long enough for ids, hashes and reason codes; far too short for an encoded image. */
    public static final int MAX_TEXT_LENGTH = 256;

    private static final Pattern TYPE = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern KEY = Pattern.compile("[a-z][A-Za-z0-9]*");
    private static final Set<String> FORBIDDEN_KEY_PARTS = Set.of("image", "frame", "photo", "biovalue", "template",
            "picture", "video");
    private static final Set<String> FORBIDDEN_KEYS = Set.of("name", "fullname", "firstname", "lastname",
            "residentname", "operatorname", "email", "phone", "address", "dob");

    private final String type;
    private final Map<String, Object> fields;

    private AuditEvent(String type, Map<String, Object> fields) {
        this.type = type;
        this.fields = Collections.unmodifiableMap(fields);
    }

    public static Builder of(String type) {
        if (type == null || !TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("audit event type must be UPPER_SNAKE_CASE, got " + type);
        }
        return new Builder(type);
    }

    public String type() {
        return type;
    }

    /** Fields sorted by name. */
    public Map<String, Object> fields() {
        return fields;
    }

    /** Builds an event, rejecting anything that could smuggle biometric or personal data into the log. */
    public static final class Builder {

        private final String type;
        private final Map<String, Object> fields = new TreeMap<>();

        private Builder(String type) {
            this.type = type;
        }

        public Builder put(String key, String value) {
            if (value != null && value.length() > MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException("audit field '" + key + "' is " + value.length()
                        + " characters; the limit is " + MAX_TEXT_LENGTH + " (no encoded data in the audit log)");
            }
            return putChecked(key, value);
        }

        public Builder put(String key, long value) {
            return putChecked(key, value);
        }

        public Builder put(String key, double value) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("audit field '" + key + "' must be finite, got " + value);
            }
            return putChecked(key, value);
        }

        public Builder put(String key, boolean value) {
            return putChecked(key, value);
        }

        public AuditEvent build() {
            return new AuditEvent(type, new TreeMap<>(fields));
        }

        private Builder putChecked(String key, Object value) {
            if (key == null || !KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("audit field names are camelCase, got " + key);
            }
            String lower = key.toLowerCase(Locale.ROOT);
            if (FORBIDDEN_KEYS.contains(lower)) {
                throw new IllegalArgumentException("audit field '" + key + "' would hold personal data; log an id instead");
            }
            for (String part : FORBIDDEN_KEY_PARTS) {
                if (lower.contains(part)) {
                    throw new IllegalArgumentException("audit field '" + key + "' suggests biometric data, which never goes"
                            + " in the audit log; log a hash or an id instead");
                }
            }
            if (fields.containsKey(key)) {
                throw new IllegalArgumentException("audit field '" + key + "' set twice");
            }
            fields.put(key, value);
            return this;
        }
    }
}

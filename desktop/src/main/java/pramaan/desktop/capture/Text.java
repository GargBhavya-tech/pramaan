/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** A message id and its parameters: what the screen shows, before translation (Bible Part 19). */
public final class Text {

    private final String id;
    private final Map<String, String> params;

    private Text(String id, Map<String, String> params) {
        this.id = Objects.requireNonNull(id, "id");
        this.params = Collections.unmodifiableMap(new TreeMap<>(params));
    }

    public static Text of(String id) {
        return new Text(id, Map.of());
    }

    public static Text of(String id, Map<String, String> params) {
        return new Text(id, params);
    }

    public String id() {
        return id;
    }

    public Map<String, String> params() {
        return params;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Text && ((Text) o).id.equals(id) && ((Text) o).params.equals(params);
    }

    @Override
    public int hashCode() {
        return id.hashCode() * 31 + params.hashCode();
    }

    @Override
    public String toString() {
        return params.isEmpty() ? id : id + params;
    }
}

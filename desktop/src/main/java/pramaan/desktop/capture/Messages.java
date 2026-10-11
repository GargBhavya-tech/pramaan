/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Message ids to words, from {@code messages.properties} (one file per language, as the Registration Client
 * does it). Lookup order for a {@link Text}:
 * <ol>
 *   <li>{@code id#value} for a message with one parameter — a plural or a special case ("blink once");</li>
 *   <li>{@code id}, with each {@code {name}} replaced by the parameter's value, itself translated through
 *       {@code pramaan.value.name.VALUE} when that key exists ("LEFT" becomes "left").</li>
 * </ol>
 * A missing id shows the id itself: visibly wrong, never silently blank.
 */
public final class Messages {

    private final ResourceBundle bundle;

    private Messages(ResourceBundle bundle) {
        this.bundle = bundle;
    }

    public static Messages forLocale(Locale locale) {
        return new Messages(ResourceBundle.getBundle("pramaan.desktop.capture.messages", locale));
    }

    public boolean has(String id) {
        return bundle.containsKey(id);
    }

    public String resolve(Text text) {
        Map<String, String> params = text.params();
        if (params.size() == 1) {
            String special = text.id() + "#" + params.values().iterator().next();
            if (bundle.containsKey(special)) {
                return bundle.getString(special);
            }
        }
        String template;
        try {
            template = bundle.getString(text.id());
        } catch (MissingResourceException e) {
            return text.id();
        }
        for (Map.Entry<String, String> p : params.entrySet()) {
            String valueKey = "pramaan.value." + p.getKey() + "." + p.getValue();
            String value = bundle.containsKey(valueKey) ? bundle.getString(valueKey) : p.getValue();
            template = template.replace("{" + p.getKey() + "}", value);
        }
        return template;
    }
}

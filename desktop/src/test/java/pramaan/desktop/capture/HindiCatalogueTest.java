/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import pramaan.core.session.LivenessError;

/**
 * Build Map #91: a second language, Hindi, is a full catalogue, not a partial one — {@link Messages}'s
 * {@link java.util.ResourceBundle} lookup falls back to the base bundle for a key missing from
 * {@code messages_hi.properties}, so a gap would otherwise render in English without anyone noticing. These tests
 * read the two {@code .properties} files directly, bypassing that fallback.
 */
class HindiCatalogueTest {

    private static Set<String> keysOf(String resource) throws IOException {
        Properties p = new Properties();
        try (InputStream in = HindiCatalogueTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("resource not found: " + resource);
            }
            try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                p.load(r);
            }
        }
        return new TreeSet<>(p.stringPropertyNames());
    }

    @Test
    void everyEnglishKeyHasAHindiTranslation() throws IOException {
        Set<String> en = keysOf("messages.properties");
        Set<String> hi = keysOf("messages_hi.properties");
        Set<String> missing = en.stream().filter(k -> !hi.contains(k)).collect(Collectors.toCollection(TreeSet::new));
        assertTrue(missing.isEmpty(), "keys with no Hindi translation: " + missing);
    }

    @Test
    void hindiHasNoStrayKeysTheEnglishCatalogueDoesNotDefine() throws IOException {
        // A stray key is usually a typo'd id: it would never be looked up, and the real id would fall back silently.
        Set<String> en = keysOf("messages.properties");
        Set<String> hi = keysOf("messages_hi.properties");
        Set<String> extra = hi.stream().filter(k -> !en.contains(k)).collect(Collectors.toCollection(TreeSet::new));
        assertTrue(extra.isEmpty(), "Hindi-only keys with no English counterpart to translate: " + extra);
    }

    @Test
    void everyHindiValueIsNonEmptyAndDistinctFromTheKey() throws IOException {
        Properties hi = new Properties();
        try (InputStream in = HindiCatalogueTest.class.getResourceAsStream("messages_hi.properties");
                InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            hi.load(r);
        }
        for (String key : hi.stringPropertyNames()) {
            String v = hi.getProperty(key);
            assertFalse(v == null || v.isBlank(), key + " has no Hindi text");
            assertFalse(v.equals(key), key + " was left as its own id");
        }
    }

    /**
     * Bible Part 22, 18: a resident must never learn which defense caught them. Every {@code Severity.HIGH}
     * {@link LivenessError} renders one of a small, fixed set of strings — shared by every error in its group —
     * the same in every language, so the specific error that fired is never revealed on screen.
     */
    @Test
    void everyHighSeverityErrorInTheSameGroupRendersIdenticallyInEnglishAndHindi() {
        Messages en = Messages.forLocale(Locale.ROOT);
        Messages hi = Messages.forLocale(Locale.forLanguageTag("hi"));
        Map<String, Set<String>> byMessageId = new TreeMap<>();
        for (LivenessError e : LivenessError.values()) {
            if (e.severity() == LivenessError.Severity.HIGH) {
                e.messageId().ifPresent(id -> byMessageId.computeIfAbsent(id, k -> new TreeSet<>()).add(e.name()));
            }
        }
        assertTrue(byMessageId.size() >= 2, "expected more than one HIGH-severity message id to compare across");
        for (Map.Entry<String, Set<String>> group : byMessageId.entrySet()) {
            String id = group.getKey();
            String enText = en.resolve(Text.of(id));
            String hiText = hi.resolve(Text.of(id));
            assertFalse(enText.equals(id), id + " has no English text");
            assertFalse(hiText.equals(id), id + " has no Hindi text");
            assertFalse(CaptureScreenModelTest.FORBIDDEN.matcher(enText).find(), enText);
            assertFalse(CaptureScreenModelTest.FORBIDDEN.matcher(hiText).find(), hiText);
            // group.getValue() lists the distinct LivenessErrors sharing id: whichever fired, resolve(id) is the
            // same call with the same result — the rendering cannot distinguish them.
        }
    }
}

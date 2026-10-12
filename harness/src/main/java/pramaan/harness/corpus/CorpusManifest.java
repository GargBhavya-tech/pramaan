/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.corpus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The corpus manifest (docs/corpus-plan.md; Build Map #16): one row per clip, with its label, attack
 * species, pseudonymous person id and recording conditions. Loading is strict — a mislabelled clip
 * silently corrupts every APCER number, so a bad row is an error with its line number.
 */
public final class CorpusManifest {

    public static final String HEADER = "clip,label,pais,person,lighting,eyewear,device,fps";

    /** PAI species ids from docs/corpus-plan.md. Label precisely: APCER is reported per species. */
    public static final Set<String> KNOWN_PAIS = Collections.unmodifiableSet(new TreeSet<>(List.of(
            "print-matte-a4", "print-glossy-4x6", "print-cutout-eyes", "print-curved", "screen-phone-photo",
            "screen-phone-video", "screen-tablet-video", "screen-laptop-video", "mask-paper", "inject-virtual-cam")));

    public static final Set<String> LIGHTING = Set.of("good", "dim", "backlit", "fluorescent");
    public static final Set<String> EYEWEAR = Set.of("none", "glasses", "sunglasses");

    /** Bona fide or attack. */
    public enum Label {
        BONA_FIDE,
        ATTACK
    }

    /** One clip. */
    public static final class Entry {

        private final Path clip;
        private final Label label;
        private final String pais;
        private final String person;
        private final String lighting;
        private final String eyewear;
        private final String device;
        private final double fps;

        Entry(Path clip, Label label, String pais, String person, String lighting, String eyewear, String device, double fps) {
            this.clip = clip;
            this.label = label;
            this.pais = pais;
            this.person = person;
            this.lighting = lighting;
            this.eyewear = eyewear;
            this.device = device;
            this.fps = fps;
        }

        public Path clip() {
            return clip;
        }

        public Label label() {
            return label;
        }

        /** The attack species, or null for bona fide clips. */
        public String pais() {
            return pais;
        }

        public String person() {
            return person;
        }

        public String lighting() {
            return lighting;
        }

        public String eyewear() {
            return eyewear;
        }

        public String device() {
            return device;
        }

        public double fps() {
            return fps;
        }
    }

    private final List<Entry> entries;

    private CorpusManifest(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    /** Loads and validates a manifest; clip paths are relative to the manifest's directory and must exist. */
    public static CorpusManifest load(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).trim().equals(HEADER)) {
            throw new IOException(csv + ": first line must be exactly '" + HEADER + "'");
        }
        Path base = csv.toAbsolutePath().getParent();
        List<Entry> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                entries.add(parse(line, base));
            } catch (IllegalArgumentException e) {
                errors.add("line " + (i + 1) + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new IOException(csv + " has " + errors.size() + " invalid row(s):\n" + String.join("\n", errors));
        }
        return new CorpusManifest(entries);
    }

    private static Entry parse(String line, Path base) {
        String[] f = line.split(",", -1);
        if (f.length != 8) {
            throw new IllegalArgumentException("expected 8 columns, found " + f.length);
        }
        Label label;
        switch (f[1].trim().toLowerCase(Locale.ROOT)) {
            case "bona_fide":
                label = Label.BONA_FIDE;
                break;
            case "attack":
                label = Label.ATTACK;
                break;
            default:
                throw new IllegalArgumentException("label must be bona_fide or attack, got '" + f[1] + "'");
        }
        String pais = f[2].trim();
        if (label == Label.ATTACK && !KNOWN_PAIS.contains(pais)) {
            throw new IllegalArgumentException("attack needs a known PAIS id, got '" + pais + "'; known: " + KNOWN_PAIS);
        }
        if (label == Label.BONA_FIDE && !pais.isEmpty()) {
            throw new IllegalArgumentException("bona fide clips have no PAIS, got '" + pais + "'");
        }
        String person = f[3].trim();
        if (!person.matches("P[0-9]{2,4}")) {
            throw new IllegalArgumentException("person must be a pseudonymous id like P07, never a name; got '" + person + "'");
        }
        String lighting = f[4].trim();
        if (!LIGHTING.contains(lighting)) {
            throw new IllegalArgumentException("lighting must be one of " + LIGHTING + ", got '" + lighting + "'");
        }
        String eyewear = f[5].trim();
        if (!EYEWEAR.contains(eyewear)) {
            throw new IllegalArgumentException("eyewear must be one of " + EYEWEAR + ", got '" + eyewear + "'");
        }
        double fps;
        try {
            fps = Double.parseDouble(f[7].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("fps is not a number: '" + f[7] + "'", e);
        }
        if (!(fps > 0)) {
            throw new IllegalArgumentException("fps must be positive");
        }
        Path clip = base.resolve(f[0].trim()).normalize();
        if (!Files.exists(clip)) {
            throw new IllegalArgumentException("clip not found: " + f[0].trim());
        }
        return new Entry(clip, label, label == Label.ATTACK ? pais : null, person, lighting, eyewear, f[6].trim(), fps);
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Clips matching every non-null criterion. */
    public List<Entry> select(Label label, String pais, String lighting) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if ((label == null || e.label == label)
                    && (pais == null || pais.equals(e.pais))
                    && (lighting == null || lighting.equals(e.lighting))) {
                out.add(e);
            }
        }
        return out;
    }

    /** Pseudonymous person ids, for splitting tuning and evaluation halves by person, not by clip. */
    public Set<String> people() {
        Set<String> people = new TreeSet<>();
        for (Entry e : entries) {
            people.add(e.person);
        }
        return people;
    }
}

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import pramaan.core.Workflow;
import pramaan.core.source.DeviceLevel;

/**
 * The operating-point table (Bible Part 9; Build Map #37): the only place fusion numbers live — cue
 * weights, the device-level bias, the disagreement gap and every accept/reject threshold.
 *
 * <p>It is bound to the exact models it was measured with: {@link #load} refuses a table whose model list
 * differs in any way from the loaded models (a changed hash, a missing model, or an extra one). That is
 * what stops thresholds silently drifting when a model is swapped. The harness (#69) generates it; until
 * then the checked-in table is hand-written and says {@code "provisional": true}.
 */
public final class OperatingPointTable {

    /** The device class used when a deployment's own class has no entry. */
    public static final String DEFAULT_DEVICE_CLASS = "default";

    private static final ObjectMapper JSON = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    private final int tableVersion;
    private final boolean provisional;
    private final Map<String, String> models;
    private final Map<String, CueWeight> cues;
    private final Map<DeviceLevel, Double> bias;
    private final double disagreementLogitGap;
    private final double thetaSame;
    private final Map<String, Map<String, OperatingPoint>> deviceClasses;
    private final MoireCalibration moireDefault;
    private final Map<String, MoireCalibration> moireByCamera;

    private OperatingPointTable(int tableVersion, boolean provisional, Map<String, String> models,
            Map<String, CueWeight> cues, Map<DeviceLevel, Double> bias, double disagreementLogitGap, double thetaSame,
            Map<String, Map<String, OperatingPoint>> deviceClasses, MoireCalibration moireDefault,
            Map<String, MoireCalibration> moireByCamera) {
        this.tableVersion = tableVersion;
        this.provisional = provisional;
        this.models = Collections.unmodifiableMap(models);
        this.cues = Collections.unmodifiableMap(cues);
        this.bias = Collections.unmodifiableMap(bias);
        this.disagreementLogitGap = disagreementLogitGap;
        this.thetaSame = thetaSame;
        this.deviceClasses = Collections.unmodifiableMap(deviceClasses);
        this.moireDefault = moireDefault;
        this.moireByCamera = Collections.unmodifiableMap(moireByCamera);
    }

    /**
     * Parses a table strictly and checks it against the models actually loaded.
     *
     * @param loadedModelSha256 model file name to hex SHA-256 of the bytes that were loaded
     * @throws InvalidPolicyException on any malformed field, or if the table's models differ from the loaded ones
     */
    public static OperatingPointTable load(byte[] json, String origin, Map<String, String> loadedModelSha256) {
        Objects.requireNonNull(loadedModelSha256, "loadedModelSha256");
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new InvalidPolicyException(origin + ": not valid JSON: " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new InvalidPolicyException(origin + ": unreadable: " + e.getMessage(), e);
        }
        OperatingPointTable table;
        try {
            table = parse(new StrictNode(root, "table"));
        } catch (InvalidPolicyException e) {
            throw new InvalidPolicyException(origin + ": " + e.getMessage(), e);
        }
        table.checkModels(origin, loadedModelSha256);
        return table;
    }

    /**
     * The model files a table is bound to, so a host loads exactly those — an extra model is refused as firmly
     * as a missing one. Names only: {@link #load} still checks every byte against the table's hashes.
     */
    public static java.util.Set<String> modelNames(byte[] json, String origin) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException e) {
            throw new InvalidPolicyException(origin + ": not valid JSON: " + e.getMessage(), e);
        }
        try {
            return java.util.Collections.unmodifiableSet(parse(new StrictNode(root, "table")).models.keySet());
        } catch (InvalidPolicyException e) {
            throw new InvalidPolicyException(origin + ": " + e.getMessage(), e);
        }
    }

    private static OperatingPointTable parse(StrictNode root) {
        int version = root.integer("tableVersion");
        if (version != 1) {
            throw new InvalidPolicyException("unsupported tableVersion " + version);
        }
        boolean provisional = root.bool("provisional");
        root.text("generatedBy");

        StrictNode modelsNode = root.object("models");
        Map<String, String> models = new TreeMap<>();
        for (String name : modelsNode.keys()) {
            String sha = modelsNode.text(name);
            if (!sha.matches("[0-9a-f]{64}")) {
                throw new InvalidPolicyException(modelsNode.path() + "." + name + ": expected 64 lowercase hex characters");
            }
            models.put(name, sha);
        }
        modelsNode.finish();
        if (models.isEmpty()) {
            throw new InvalidPolicyException("models: a table bound to no model binds nothing");
        }

        StrictNode fusion = root.object("fusion");
        StrictNode cuesNode = fusion.object("cues");
        Map<String, CueWeight> cues = new LinkedHashMap<>();
        for (String cue : cuesNode.keys()) {
            StrictNode c = cuesNode.object(cue);
            cues.put(cue, new CueWeight(cue, c.number("wPos"), c.number("wNeg"), c.bool("physiological")));
            c.finish();
        }
        cuesNode.finish();
        StrictNode biasNode = fusion.object("bias");
        Map<DeviceLevel, Double> bias = new EnumMap<>(DeviceLevel.class);
        for (DeviceLevel level : DeviceLevel.values()) {
            bias.put(level, biasNode.number(level.name()));
        }
        biasNode.finish();
        if (bias.get(DeviceLevel.L0) > bias.get(DeviceLevel.L1)) {
            throw new InvalidPolicyException("fusion.bias: L0 (key in host software) must not be trusted more than L1");
        }
        double gap = fusion.number("disagreementLogitGap");
        if (!(gap > 0)) {
            throw new InvalidPolicyException("fusion.disagreementLogitGap must be > 0");
        }
        fusion.finish();

        StrictNode binding = root.object("binding");
        double thetaSame = binding.number("thetaSame");
        binding.finish();
        if (!(thetaSame > 0 && thetaSame < 1)) {
            throw new InvalidPolicyException("binding.thetaSame must be between 0 and 1");
        }

        StrictNode classesNode = root.object("deviceClasses");
        Map<String, Map<String, OperatingPoint>> classes = new TreeMap<>();
        for (String deviceClass : classesNode.keys()) {
            StrictNode pointsNode = classesNode.object(deviceClass);
            Map<String, OperatingPoint> points = new TreeMap<>();
            for (String name : pointsNode.keys()) {
                StrictNode p = pointsNode.object(name);
                points.put(name, new OperatingPoint(name, p.number("tauAccept"), p.number("tauReject"), p.text("target")));
                p.finish();
            }
            pointsNode.finish();
            classes.put(deviceClass, points);
        }
        classesNode.finish();
        if (!classes.containsKey(DEFAULT_DEVICE_CLASS)) {
            throw new InvalidPolicyException("deviceClasses: a \"" + DEFAULT_DEVICE_CLASS + "\" class is required");
        }
        MoireCalibration moireDefault = new MoireCalibration(MoireCalibration.PROVISIONAL_THRESHOLD, MoireCalibration.PROVISIONAL_SLOPE,
                MoireCalibration.Mode.VOTE, "provisional: Bible Part 8.2 example ranges, never measured");
        Map<String, MoireCalibration> moireByCamera = new TreeMap<>();
        if (root.keys().contains("moireCalibration")) {
            StrictNode moire = root.object("moireCalibration");
            moireDefault = readMoire(moire.object("default"));
            if (moire.keys().contains("cameras")) {
                StrictNode cameras = moire.object("cameras");
                for (String camera : cameras.keys()) {
                    if (camera.trim().isEmpty()) {
                        throw new InvalidPolicyException("moireCalibration.cameras: a camera needs a name");
                    }
                    moireByCamera.put(camera, readMoire(cameras.object(camera)));
                }
                cameras.finish();
            }
            moire.finish();
        }
        root.finish();
        return new OperatingPointTable(version, provisional, models, cues, bias, gap, thetaSame, classes, moireDefault, moireByCamera);
    }

    private static MoireCalibration readMoire(StrictNode n) {
        double threshold = n.number("peakinessThreshold");
        double slope = n.number("slope");
        String mode = n.text("mode");
        MoireCalibration.Mode parsed;
        switch (mode) {
            case "vote":
                parsed = MoireCalibration.Mode.VOTE;
                break;
            case "abstain":
                parsed = MoireCalibration.Mode.ABSTAIN;
                break;
            default:
                throw new InvalidPolicyException("moireCalibration mode must be vote or abstain: " + mode);
        }
        MoireCalibration c = new MoireCalibration(threshold, slope, parsed, n.text("basis"));
        n.finish();
        return c;
    }

    /**
     * The moire calibration for the connected camera: its own entry when the table has one for this make and model, the table's
     * default otherwise. The camera name is {@code make + " " + model} as the device reports it.
     */
    public MoireCalibration moireCalibration(String make, String model) {
        String key = (make == null ? "" : make.trim()) + " " + (model == null ? "" : model.trim());
        MoireCalibration own = moireByCamera.get(key.trim());
        return own != null ? own : moireDefault;
    }

    /** True when the table names this camera: a camera with no entry runs on the default, which may be provisional. */
    public boolean hasMoireCalibrationFor(String make, String model) {
        return moireByCamera.containsKey(((make == null ? "" : make.trim()) + " " + (model == null ? "" : model.trim())).trim());
    }

    private void checkModels(String origin, Map<String, String> loaded) {
        Map<String, String> actual = new TreeMap<>();
        loaded.forEach((k, v) -> actual.put(k, v.toLowerCase(Locale.ROOT)));
        if (actual.equals(models)) {
            return;
        }
        StringBuilder why = new StringBuilder();
        TreeSet<String> names = new TreeSet<>(models.keySet());
        names.addAll(actual.keySet());
        for (String name : names) {
            String want = models.get(name);
            String got = actual.get(name);
            if (want == null) {
                why.append("; ").append(name).append(" is loaded but the table was not measured with it");
            } else if (got == null) {
                why.append("; ").append(name).append(" is in the table but not loaded");
            } else if (!want.equals(got)) {
                why.append("; ").append(name).append(" has sha256 ").append(got, 0, Math.min(12, got.length()))
                        .append("... but the table was measured with ").append(want, 0, 12).append("...");
            }
        }
        throw new InvalidPolicyException(origin + ": operating points do not match the loaded models" + why
                + ". Regenerate the table with the harness (#69) for these models; refusing to run with thresholds"
                + " measured on different ones.");
    }

    /** Fails unless every profile's operating point exists for the default device class. */
    public void requireCovers(LivenessPolicy policy) {
        for (Workflow w : Workflow.values()) {
            String name = policy.profile(w).operatingPoint();
            if (!deviceClasses.get(DEFAULT_DEVICE_CLASS).containsKey(name)) {
                throw new InvalidPolicyException("policy profile " + w + " names operating point '" + name
                        + "', which the operating-point table does not define");
            }
        }
    }

    /** The named point for this device class, or for {@link #DEFAULT_DEVICE_CLASS} if the class has none. */
    public OperatingPoint operatingPoint(String name, String deviceClass) {
        Map<String, OperatingPoint> points = deviceClasses.get(deviceClass);
        OperatingPoint p = points == null ? null : points.get(name);
        if (p == null) {
            p = deviceClasses.get(DEFAULT_DEVICE_CLASS).get(name);
        }
        if (p == null) {
            throw new InvalidPolicyException("no operating point '" + name + "'");
        }
        return p;
    }

    public Optional<CueWeight> weight(String cue) {
        return Optional.ofNullable(cues.get(cue));
    }

    public double bias(DeviceLevel level) {
        return bias.get(level);
    }

    public double disagreementLogitGap() {
        return disagreementLogitGap;
    }

    /** Identity continuity threshold on SFace cosine similarity (Bible Part 13, lock 3), bound to the SFace hash. */
    public double thetaSame() {
        return thetaSame;
    }

    public boolean provisional() {
        return provisional;
    }

    public int tableVersion() {
        return tableVersion;
    }

    public Map<String, String> models() {
        return models;
    }

    /** Lowercase hex SHA-256, for building the loaded-model map passed to {@link #load}. */
    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder s = new StringBuilder(64);
            for (byte b : d) {
                s.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return s.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }
}

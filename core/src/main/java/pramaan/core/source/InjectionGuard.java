/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Judge the pipe before judging the face (Bible Part 5; Build Map #84): a host an attacker controls can skip the
 * lens entirely by routing a virtual camera into the capture path. This is the part of Part 5's three checks that
 * software alone can do without a native camera-enumeration library or a real device's timing distribution to
 * calibrate against:
 * <ol>
 *   <li>{@link #denylistMatch}: the bound device's make/model/code against known virtual-camera products —
 *       a hard veto when policy says so (Bible: "one <em>bound to the capture path</em> is a veto");</li>
 *   <li>{@link #timingCoefficientOfVariation}: real sensors jitter; a suspiciously steady clock does not
 *       (a soft signal — see the class doc's honest limits before wiring it into a veto);</li>
 *   <li>{@link #duplicateFrame}: two consecutive frames with identical bytes, which a live sensor does not send.</li>
 * </ol>
 *
 * <p>Also built: {@link #hostVirtualCameras} over a platform's {@link HostCameraInventory} (the OS's own camera list,
 * which catches a virtual camera that is installed even when the SBI device does not name it) and {@link DeviceIdentity} (the
 * digitalId's certificate chain). <strong>Not built:</strong> the PRNU sensor fingerprint (Part 5, point 4 —
 * explicitly STRETCH), and a signed, externally maintained denylist. {@link #denylistMatch} on the SBI device's own
 * reported name closes the common case (an off-the-shelf virtual-camera product used as-is), not the targeted one;
 * the host list and the identity check narrow it, and none of them was tried against a real attacker.
 */
public final class InjectionGuard {

    /**
     * Well-known virtual-camera products (Bible Part 5's own named examples, plus a few more common ones),
     * matched case-insensitively as substrings of the device's make, model or code. This is a plain, in-code list,
     * not the <em>signed, externally maintained</em> denylist Part 5 calls for — see the class doc.
     */
    public static final List<String> KNOWN_VIRTUAL_CAMERA_PRODUCTS = Collections.unmodifiableList(Arrays.asList(
            "OBS Virtual Camera", "OBS-Camera", "ManyCam", "XSplit VCam", "XSplit Broadcaster", "v4l2loopback",
            "Snap Camera", "iVCam", "EpocCam", "DroidCam", "CamTwist", "NDI Virtual Input", "Elgato Virtual Camera",
            "Streamlabs", "Vcam", "e2eSoft VCam", "ChromaCam", "Wecam"));

    /** Below this coefficient of variation, a 60-frame window's timing is suspiciously regular (Bible Part 5.3). */
    public static final double DEFAULT_CV_THRESHOLD = 0.002;
    public static final int DEFAULT_WINDOW = 60;

    private InjectionGuard() {
    }

    /**
     * The denylisted product name the device's make, model or code matches, if any. Checked against every field
     * MOSIP's device-info response gives, because a virtual-camera driver may report its product name in any of
     * them.
     */
    public static Optional<String> denylistMatch(DeviceMetadata device, List<String> denylist) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(denylist, "denylist");
        for (String candidate : new String[] {device.make(), device.model(), device.deviceCode()}) {
            if (candidate == null) {
                continue;
            }
            String lower = candidate.toLowerCase(Locale.ROOT);
            for (String product : denylist) {
                if (lower.contains(product.toLowerCase(Locale.ROOT))) {
                    return Optional.of(product);
                }
            }
        }
        return Optional.empty();
    }

    /** As {@link #denylistMatch(DeviceMetadata, List)} against {@link #KNOWN_VIRTUAL_CAMERA_PRODUCTS}. */
    public static Optional<String> denylistMatch(DeviceMetadata device) {
        return denylistMatch(device, KNOWN_VIRTUAL_CAMERA_PRODUCTS);
    }

    /**
     * The denylisted products among the host's own camera list (Bible Part 5, point 2): a virtual camera installed
     * on the station even if the SBI device does not name it. Each product is reported once.
     */
    public static List<String> hostVirtualCameras(List<String> hostCameraNames, List<String> denylist) {
        Objects.requireNonNull(hostCameraNames, "hostCameraNames");
        Objects.requireNonNull(denylist, "denylist");
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        for (String name : hostCameraNames) {
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            for (String product : denylist) {
                if (lower.contains(product.toLowerCase(Locale.ROOT))) {
                    found.add(product);
                }
            }
        }
        return Collections.unmodifiableList(new java.util.ArrayList<>(found));
    }

    /**
     * $CV_\Delta = \sigma(\Delta) / \mu(\Delta)$ over consecutive frame-arrival gaps (Bible Part 5.3). NaN with
     * fewer than three timestamps (two gaps) — not enough to say anything.
     *
     * <p><strong>Honest limit (Bible Part 5.3):</strong> on the SBI path, frames cross a localhost HTTP stream, so
     * host-side timing is dominated by transport buffering — this is a real signal only with
     * {@code sensorTimestampNs} from a device that provides it, and its false-positive rate against real device
     * jitter is not yet measured (no corpus of real timing distributions exists — Build Map #65). It is therefore
     * exposed here as a diagnostic, not wired into a veto or a fusion cue.
     */
    public static double timingCoefficientOfVariation(long[] timestampsNs) {
        Objects.requireNonNull(timestampsNs, "timestampsNs");
        if (timestampsNs.length < 3) {
            return Double.NaN;
        }
        int n = timestampsNs.length - 1;
        double[] gaps = new double[n];
        double mean = 0;
        for (int i = 0; i < n; i++) {
            gaps[i] = timestampsNs[i + 1] - timestampsNs[i];
            mean += gaps[i];
        }
        mean /= n;
        if (mean <= 0) {
            return Double.NaN;
        }
        double variance = 0;
        for (double g : gaps) {
            variance += (g - mean) * (g - mean);
        }
        variance /= n;
        return Math.sqrt(variance) / mean;
    }

    /** Suspiciously steady clocking: enough timestamps, and {@link #timingCoefficientOfVariation} below threshold. */
    public static boolean lowVarianceTiming(long[] timestampsNs, double threshold) {
        double cv = timingCoefficientOfVariation(timestampsNs);
        return !Double.isNaN(cv) && cv < threshold;
    }

    /** Two frames with byte-identical content: a live sensor does not repeat a frame exactly (Bible Part 5.3). */
    public static boolean duplicateFrame(byte[] a, byte[] b) {
        return a != null && b != null && Arrays.equals(a, b);
    }
}

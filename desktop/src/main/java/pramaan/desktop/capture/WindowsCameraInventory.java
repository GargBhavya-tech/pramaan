/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import pramaan.core.source.HostCameraInventory;

/**
 * The cameras Windows lists (Bible Part 5, point 2), from two places:
 * <ul>
 *   <li>the DirectShow and Media Foundation video-input <em>registrations</em> in the registry, read with
 *       {@code reg.exe} in a fraction of a second on every look. This is where a software camera shows up: a real USB
 *       webcam is enumerated on the fly and has no entry, while OBS Virtual Camera has one and no Plug-and-Play device
 *       at all. Running the first version of this class on a station with OBS installed found exactly that: it read only
 *       the Plug-and-Play list and did not see OBS; and</li>
 *   <li>the Plug-and-Play devices of class Camera and Image, which is where real webcams show up, and any camera with
 *       no physical bus (an instance id starting {@code ROOT\}, {@code SWD\} or {@code SW\}). PowerShell needs about
 *       five seconds for this, so it is read once in the background ({@link #warmUp()}) and refreshed every few minutes;
 *       a look never waits for it.</li>
 * </ul>
 * {@link #softwareCameras()} lists registered software filters and bus-less devices, whether or not their names are on
 * a denylist. No native code. On any other platform, or if {@code reg.exe} cannot be run, the answer is "unknown" and
 * nobody is accused. Honest limit: a driver can pick any name, so the denylist finds an off-the-shelf virtual camera;
 * the software list is what a renamed one would still show up in, and it is recorded, not acted on.
 */
public final class WindowsCameraInventory implements HostCameraInventory {

    private static final String DSHOW = "{860BB310-5D01-11d0-BD3B-00A0C911CE86}";
    private static final String MF = "{E5323777-F976-4f5b-9B55-B94699C46E44}";
    private static final String[] REGISTRY_KEYS = {
        "HKLM\\SOFTWARE\\Classes\\CLSID\\" + DSHOW + "\\Instance",
        "HKLM\\SOFTWARE\\WOW6432Node\\Classes\\CLSID\\" + DSHOW + "\\Instance",
        "HKLM\\SOFTWARE\\Classes\\CLSID\\" + MF + "\\Instance"
    };
    private static final Pattern FRIENDLY_NAME = Pattern.compile("^\\s*FriendlyName\\s+REG_SZ\\s+(.+?)\\s*$");

    static final String PNP_COMMAND = "$ErrorActionPreference='SilentlyContinue';"
            + "Get-PnpDevice -Class Camera,Image -PresentOnly | Where-Object { $_.FriendlyName } | ForEach-Object {"
            + " 'PNP|' + $_.FriendlyName + '|' + $_.InstanceId }; exit 0";
    private static final long REG_TIMEOUT_SECONDS = 5;
    private static final long PNP_TIMEOUT_SECONDS = 25;
    private static final long PNP_TTL_NANOS = TimeUnit.MINUTES.toNanos(5);

    private final Supplier<Optional<String>> registry;
    private final Supplier<Optional<String>> pnp;
    private final boolean inline;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile String pnpOutput = "";
    private volatile long pnpAtNanos;
    private volatile boolean pnpRead;

    /** Reads this machine. */
    public WindowsCameraInventory() {
        this(WindowsCameraInventory::readRegistry, WindowsCameraInventory::runPnp, false);
    }

    /**
     * @param registry the raw output of the registry queries, or empty if they could not be run
     * @param pnp the Plug-and-Play records ({@code PNP|name|instanceId} lines), or empty if they could not be read
     * @param inline true to refresh the Plug-and-Play records on the calling thread (tests); false in the background
     */
    WindowsCameraInventory(Supplier<Optional<String>> registry, Supplier<Optional<String>> pnp, boolean inline) {
        this.registry = registry;
        this.pnp = pnp;
        this.inline = inline;
    }

    /** Starts the slow Plug-and-Play read now, in the background, so the first session does not miss it. */
    public void warmUp() {
        refreshPnp();
    }

    @Override
    public Optional<List<String>> cameraNames() {
        return read().map(Cameras::names);
    }

    @Override
    public Optional<List<String>> softwareCameras() {
        return read().map(Cameras::software);
    }

    @Override
    public Optional<Cameras> read() {
        Optional<String> reg = registry.get();
        if (reg.isEmpty()) {
            return Optional.empty(); // could not even ask: unknown
        }
        if (!pnpRead || System.nanoTime() - pnpAtNanos > PNP_TTL_NANOS) {
            refreshPnp();
        }
        Parsed p = parse(registryRecords(reg.get()) + "\n" + pnpOutput);
        return Optional.of(new Cameras(p.names, p.software));
    }

    private void refreshPnp() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        Runnable work = () -> {
            try {
                pnp.get().ifPresent(out -> {
                    pnpOutput = out;
                    pnpRead = true;
                });
                pnpAtNanos = System.nanoTime();
            } finally {
                refreshing.set(false);
            }
        };
        if (inline) {
            work.run();
        } else {
            Thread t = new Thread(work, "pramaan-camera-inventory");
            t.setDaemon(true);
            t.start();
        }
    }

    /** The two lists in one pass over the records. */
    static final class Parsed {
        final List<String> names = new ArrayList<>();
        final List<String> software = new ArrayList<>();

        private static void add(List<String> list, String name) {
            if (!list.contains(name)) {
                list.add(name);
            }
        }
    }

    /** {@code FriendlyName REG_SZ x} lines of {@code reg query} output, as {@code SOFT|x} records. */
    static String registryRecords(String regOutput) {
        StringBuilder sb = new StringBuilder();
        for (String line : regOutput.split("\\R")) {
            Matcher m = FRIENDLY_NAME.matcher(line);
            if (m.matches()) {
                sb.append("SOFT|").append(m.group(1)).append('\n');
            }
        }
        return sb.toString();
    }

    /** Lines are {@code PNP|name|instanceId} or {@code SOFT|name}; anything else is ignored. */
    static Parsed parse(String output) {
        Parsed p = new Parsed();
        for (String line : output.split("\\R")) {
            String[] f = line.trim().split("\\|");
            if (f.length < 2 || f[1].isBlank()) {
                continue;
            }
            String name = f[1].trim();
            if ("PNP".equals(f[0])) {
                Parsed.add(p.names, name);
                if (f.length > 2 && !hasPhysicalBus(f[2])) {
                    Parsed.add(p.software, name);
                }
            } else if ("SOFT".equals(f[0])) {
                Parsed.add(p.names, name);
                Parsed.add(p.software, name);
            }
        }
        return p;
    }

    /** USB, PCI, ACPI and Bluetooth devices hang off hardware; ROOT, SWD and SW are made by software. */
    static boolean hasPhysicalBus(String instanceId) {
        String id = instanceId.trim().toUpperCase(Locale.ROOT);
        return !(id.startsWith("ROOT\\") || id.startsWith("SWD\\") || id.startsWith("SW\\"));
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    /** All three registry keys. A key that does not exist is an empty result, not a failure; no {@code reg.exe} is. */
    private static Optional<String> readRegistry() {
        if (!windows()) {
            return Optional.empty();
        }
        StringBuilder all = new StringBuilder();
        Charset charset = Charset.forName(System.getProperty("sun.jnu.encoding", "UTF-8"));
        try {
            for (String key : REGISTRY_KEYS) {
                Process p = new ProcessBuilder("reg", "query", key, "/s", "/v", "FriendlyName").redirectErrorStream(true)
                        .start();
                p.getOutputStream().close();
                if (!p.waitFor(REG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return Optional.empty();
                }
                all.append(new String(p.getInputStream().readAllBytes(), charset)).append('\n');
            }
            return Optional.of(all.toString());
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static Optional<String> runPnp() {
        if (!windows()) {
            return Optional.empty();
        }
        try {
            String encoded = Base64.getEncoder().encodeToString(PNP_COMMAND.getBytes(StandardCharsets.UTF_16LE));
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                    .redirectErrorStream(false).start();
            p.getOutputStream().close();
            if (!p.waitFor(PNP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return Optional.empty();
            }
            if (p.exitValue() != 0) {
                return Optional.empty();
            }
            return Optional.of(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}

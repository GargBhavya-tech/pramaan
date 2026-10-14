/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import pramaan.core.source.HostCameraInventory;

/**
 * The cameras Windows lists (Bible Part 5, point 2): the Plug-and-Play devices of class Camera and Image, which is
 * where a virtual camera registers as well. Read by asking PowerShell once; no native code. On any other platform, or
 * if PowerShell fails or takes too long, the answer is "unknown" and nobody is accused.
 *
 * <p>Honest limit: this reads the names Windows holds, and a driver can pick any name. It finds an off-the-shelf
 * virtual camera that is installed; it does not find one that names itself like a webcam.
 */
public final class WindowsCameraInventory implements HostCameraInventory {

    static final String COMMAND = "Get-PnpDevice -Class Camera,Image -PresentOnly -ErrorAction SilentlyContinue"
            + " | Where-Object { $_.FriendlyName } | ForEach-Object { $_.FriendlyName }";
    private static final long TIMEOUT_SECONDS = 10;

    private final Supplier<Optional<String>> run;

    /** Reads this machine. */
    public WindowsCameraInventory() {
        this(WindowsCameraInventory::runPowerShell);
    }

    /** @param run produces the command's standard output, or empty if it could not be run (tests pass a fake) */
    WindowsCameraInventory(Supplier<Optional<String>> run) {
        this.run = run;
    }

    @Override
    public Optional<List<String>> cameraNames() {
        return run.get().map(WindowsCameraInventory::parse);
    }

    /** One friendly name per non-blank line, trimmed, duplicates kept out. */
    static List<String> parse(String output) {
        List<String> names = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String name = line.trim();
            if (!name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private static Optional<String> runPowerShell() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return Optional.empty();
        }
        try {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", COMMAND)
                    .redirectErrorStream(false).start();
            p.getOutputStream().close();
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
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

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import java.util.List;
import java.util.Optional;

/**
 * The cameras the operating system itself lists (Bible Part 5, point 2): the mechanism behind "enumerate the host's
 * video devices", as opposed to trusting the name the SBI device reports for itself. Core cannot enumerate devices
 * (it is headless, with no native code), so each platform supplies one.
 */
public interface HostCameraInventory {

    /**
     * The friendly names of the host's camera devices, or empty if the platform could not be asked. Empty means
     * "unknown", never "none": a station that cannot enumerate is not accused of anything.
     */
    Optional<List<String>> cameraNames();

    /**
     * The cameras that are software rather than hardware: registered as video-input filters, or with no physical bus.
     * Empty means "unknown" here too. They are listed whether or not their names are on any denylist, because a
     * virtual camera can be renamed and cannot easily stop being software.
     */
    default Optional<List<String>> softwareCameras() {
        return Optional.empty();
    }

    /** Both lists from one look; an implementation that has to run a command overrides this to run it once. */
    default Optional<Cameras> read() {
        return cameraNames().map(names -> new Cameras(names, softwareCameras().orElse(List.of())));
    }

    /** What one look at the host found. */
    final class Cameras {
        private final List<String> names;
        private final List<String> software;

        public Cameras(List<String> names, List<String> software) {
            this.names = List.copyOf(names);
            this.software = List.copyOf(software);
        }

        public List<String> names() {
            return names;
        }

        public List<String> software() {
            return software;
        }
    }
}

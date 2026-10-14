/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import pramaan.core.source.HostCameraInventory;
import pramaan.core.source.InjectionGuard;

class WindowsCameraInventoryTest {

    /** What {@code reg query ... /s /v FriendlyName} prints on a station with OBS installed (twice: 64 and 32 bit). */
    private static final String REG = String.join("\r\n",
            "",
            "HKEY_LOCAL_MACHINE\\SOFTWARE\\Classes\\CLSID\\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\\Instance\\{A3FCE0F5}",
            "    FriendlyName    REG_SZ    OBS Virtual Camera",
            "",
            "End of search: 1 match(es) found.",
            "ERROR: The system was unable to find the specified registry key or value.",
            "HKEY_LOCAL_MACHINE\\SOFTWARE\\WOW6432Node\\Classes\\CLSID\\{860BB310}\\Instance\\{A3FCE0F5}",
            "    FriendlyName    REG_SZ    OBS Virtual Camera",
            "");

    private static final String PNP = String.join("\r\n",
            "PNP|Integrated Camera|USB\\VID_04F2&PID_B798&MI_00\\6&56DEAED&1&0000",
            "PNP|Contoso Capture|ROOT\\CAMERA\\0000",
            "PNP|Win11 Virtual|SWD\\VCAMDEVAPI\\0001",
            "noise that is not a record",
            "PNP||USB\\EMPTY",
            "");

    private static WindowsCameraInventory inventory(String reg, String pnp) {
        return new WindowsCameraInventory(() -> Optional.ofNullable(reg), () -> Optional.ofNullable(pnp), true);
    }

    @Test
    void bothSourcesAreReadAndRepeatsAreDropped() {
        HostCameraInventory.Cameras c = inventory(REG, PNP).read().orElseThrow();
        assertEquals(List.of("OBS Virtual Camera", "Integrated Camera", "Contoso Capture", "Win11 Virtual"), c.names());
    }

    @Test
    void aSoftwareFilterAndACameraWithNoPhysicalBusAreBothSoftwareButARealWebcamIsNot() {
        HostCameraInventory.Cameras c = inventory(REG, PNP).read().orElseThrow();
        assertEquals(List.of("OBS Virtual Camera", "Contoso Capture", "Win11 Virtual"), c.software());
        assertFalse(c.software().contains("Integrated Camera"));
    }

    @Test
    void theDenylistFindsOBSEvenThoughPlugAndPlayNeverListedIt() {
        HostCameraInventory.Cameras c = inventory(REG, PNP).read().orElseThrow();
        assertEquals(List.of("OBS Virtual Camera"),
                InjectionGuard.hostVirtualCameras(c.names(), InjectionGuard.KNOWN_VIRTUAL_CAMERA_PRODUCTS));
    }

    @Test
    void aRenamedVirtualCameraIsStillInTheSoftwareList() {
        HostCameraInventory.Cameras c = inventory(
                "    FriendlyName    REG_SZ    Logitech BRIO\r\n", "PNP|Integrated Camera|USB\\VID_1&PID_2\\3").read()
                .orElseThrow();
        assertTrue(InjectionGuard.hostVirtualCameras(c.names(), InjectionGuard.KNOWN_VIRTUAL_CAMERA_PRODUCTS).isEmpty(),
                "the name fools the denylist");
        assertEquals(List.of("Logitech BRIO"), c.software(), "a real BRIO is never registered as a software filter");
    }

    @Test
    void theRegistryAloneIsEnoughWhenPlugAndPlayCannotBeRead() {
        HostCameraInventory.Cameras c = inventory(REG, null).read().orElseThrow();
        assertEquals(List.of("OBS Virtual Camera"), c.names());
    }

    @Test
    void whenTheRegistryCannotBeAskedTheAnswerIsUnknownNotNone() {
        WindowsCameraInventory unknown = inventory(null, PNP);
        assertTrue(unknown.cameraNames().isEmpty());
        assertTrue(unknown.softwareCameras().isEmpty());
        assertTrue(unknown.read().isEmpty());
    }

    @Test
    void anEmptyRegistryIsAKnownEmptyList() {
        assertEquals(Optional.of(List.of()), inventory("ERROR: nothing here\r\n", "").cameraNames());
    }

    @Test
    void thePlugAndPlayReadIsCachedNotRepeatedOnEveryLook() {
        AtomicInteger reads = new AtomicInteger();
        WindowsCameraInventory inv = new WindowsCameraInventory(() -> Optional.of(REG), () -> {
            reads.incrementAndGet();
            return Optional.of(PNP);
        }, true);
        inv.read();
        inv.read();
        inv.read();
        assertEquals(1, reads.get(), "five seconds of PowerShell is paid once, not once per session");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void thisMachinesRealAnswerIsWellFormed() {
        WindowsCameraInventory real = new WindowsCameraInventory();
        Optional<HostCameraInventory.Cameras> first = real.read();
        org.junit.jupiter.api.Assumptions.assumeTrue(first.isPresent(), "reg.exe could not be run here");
        for (String name : first.get().names()) {
            assertFalse(name.isBlank());
        }
        assertTrue(first.get().names().containsAll(first.get().software()), "every software camera is also a camera");
    }
}

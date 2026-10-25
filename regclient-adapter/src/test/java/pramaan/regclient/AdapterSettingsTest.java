/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.regclient;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.mosip.registration.liveness.LivenessRefusedException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pramaan.core.Workflow;
import pramaan.core.audit.AuditEvent;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.engine.EngineListener;
import pramaan.core.evidence.EvidencePackager;
import pramaan.core.session.EngineBundle;
import pramaan.core.session.SessionRegistry;

/**
 * Unit tests for {@link AdapterSettings} and {@link PramaanLivenessHook} that require no device,
 * no ONNX models, and no HTTP connection. Covers the static configure/reset lifecycle, the
 * refused-without-settings fast path, and the builder's defensive null-checks — the plumbing that
 * must hold before any on-device integration test can be meaningful.
 *
 * <p>The harness {@code RegistrationClientAdapterTest} exercises the full end-to-end path against a
 * real loopback device; these tests are the unit layer beneath that.
 */
class AdapterSettingsTest {

    @AfterEach
    void resetHook() {
        // Always leave the static hook in a clean state so tests do not bleed into each other.
        PramaanLivenessHook.reset();
    }

    // -----------------------------------------------------------------------------------------
    // 1. Refused without settings — fail-closed behaviour without any infrastructure
    // -----------------------------------------------------------------------------------------

    @Test
    void awaitCaptureClearanceWithoutSettingsThrowsLivenessRefused() {
        // Hook is in the reset state — no configure() has been called.
        PramaanLivenessHook hook = new PramaanLivenessHook();
        hook.beginFaceScan("FACE");
        assertThrows(LivenessRefusedException.class, hook::awaitCaptureClearance,
                "awaitCaptureClearance must refuse when no settings are installed");
    }

    @Test
    void afterCaptureWithoutSettingsThrowsLivenessRefused() {
        PramaanLivenessHook hook = new PramaanLivenessHook();
        hook.beginFaceScan("FACE");
        assertThrows(LivenessRefusedException.class,
                () -> hook.afterCapture(List.of()),
                "afterCapture must refuse when no settings are installed");
    }

    @Test
    void awaitCaptureWithoutBeginFaceScanThrowsLivenessRefused() {
        PramaanLivenessHook hook = new PramaanLivenessHook();
        // beginFaceScan was never called; awaitCaptureClearance must refuse, not NPE.
        assertThrows(LivenessRefusedException.class, hook::awaitCaptureClearance,
                "awaitCaptureClearance must refuse when beginFaceScan was never called");
    }

    @Test
    void afterCaptureWithoutBeginFaceScanThrowsLivenessRefused() {
        PramaanLivenessHook hook = new PramaanLivenessHook();
        assertThrows(LivenessRefusedException.class,
                () -> hook.afterCapture(List.of()),
                "afterCapture must refuse when beginFaceScan was never called");
    }

    // -----------------------------------------------------------------------------------------
    // 2. onPreviewFrame never throws — it is called from the client's UI thread
    // -----------------------------------------------------------------------------------------

    @Test
    void onPreviewFrameWithNullJpegDoesNotThrow() {
        PramaanLivenessHook hook = new PramaanLivenessHook();
        hook.beginFaceScan("FACE");
        assertDoesNotThrow(() -> hook.onPreviewFrame(null, System.currentTimeMillis()),
                "onPreviewFrame must be null-safe: the client may pass null on a dropped frame");
    }

    @Test
    void onPreviewFrameBeforeBeginFaceScanDoesNotThrow() {
        PramaanLivenessHook hook = new PramaanLivenessHook();
        // Some client implementations start streaming before calling beginFaceScan.
        assertDoesNotThrow(() -> hook.onPreviewFrame(new byte[]{0x01}, System.currentTimeMillis()),
                "onPreviewFrame before beginFaceScan must be a no-op, not an exception");
    }

    // -----------------------------------------------------------------------------------------
    // 3. Subject builder null checks — missing arguments are caught at build time, not silently
    // -----------------------------------------------------------------------------------------

    @Test
    void subjectRejectsNullWorkflow() {
        assertThrows(NullPointerException.class,
                () -> new AdapterSettings.Subject(null, "R1", "OP1", "c1"),
                "Subject must reject a null workflow");
    }

    @Test
    void subjectRejectsNullSubjectId() {
        assertThrows(NullPointerException.class,
                () -> new AdapterSettings.Subject(Workflow.RESIDENT, null, "OP1", "c1"),
                "Subject must reject a null subjectId");
    }

    @Test
    void subjectRejectsNullOperatorId() {
        assertThrows(NullPointerException.class,
                () -> new AdapterSettings.Subject(Workflow.RESIDENT, "R1", null, "c1"),
                "Subject must reject a null operatorId");
    }

    @Test
    void subjectRejectsNullCentreId() {
        assertThrows(NullPointerException.class,
                () -> new AdapterSettings.Subject(Workflow.RESIDENT, "R1", "OP1", null),
                "Subject must reject a null centreId");
    }

    @Test
    void subjectAccessorsReturnSuppliedValues() {
        AdapterSettings.Subject s = new AdapterSettings.Subject(Workflow.OPERATOR, "O1", "OP2", "c2");
        assertNotNull(s.workflow());
        assertNotNull(s.subjectId());
        assertNotNull(s.operatorId());
        assertNotNull(s.centreId());
    }

    // -----------------------------------------------------------------------------------------
    // 4. configure / reset lifecycle
    // -----------------------------------------------------------------------------------------

    @Test
    void resetLeavesHookInRefusedState() {
        // Install and then immediately remove settings.
        PramaanLivenessHook.reset();
        PramaanLivenessHook hook = new PramaanLivenessHook();
        hook.beginFaceScan("FACE");
        assertThrows(LivenessRefusedException.class, hook::awaitCaptureClearance,
                "after reset, every scan must be refused");
    }

    @Test
    void multipleBeginFaceScanCallsDoNotThrow() {
        // The client may call beginFaceScan more than once (e.g. the user presses Scan again).
        PramaanLivenessHook hook = new PramaanLivenessHook();
        hook.beginFaceScan("FACE");
        assertDoesNotThrow(() -> hook.beginFaceScan("FACE"),
                "a second beginFaceScan must supersede the first, not throw");
    }
}

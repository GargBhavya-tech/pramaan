/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.binding;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.crypto.JwsVerificationException;
import pramaan.core.source.SignedCapture;

/**
 * Closes the capture-swap hole (Bible Part 13; Build Map #54-#57). The preview is unsigned and the capture
 * is signed; nothing links them unless all four locks hold:
 * <ol>
 *   <li><b>nonce</b> — the device signed this attempt's nonce, with a timestamp within the allowed skew;</li>
 *   <li><b>chain</b> — MOSIP's capture hash recomputes from the signed {@code bioValue} and links to the
 *       session's earlier captures; H_final then binds it to our frame chain;</li>
 *   <li><b>identity</b> — the captured face matches the median embedding of the attempt's verified frames,
 *       on the same unbroken track;</li>
 *   <li><b>time</b> — the face was live-verified within the window before the capture was requested.</li>
 * </ol>
 * All four are always evaluated, so the audit log shows every failure, not just the first.
 */
public final class CaptureBinder {

    private final JwsTrust trust;
    private final List<BindingLock> locks = new ArrayList<>();

    /**
     * @param thetaSame from the operating-point table (bound to the SFace model hash)
     * @param window from policy: longest gap from the last verified frame to the capture request
     * @param maxSkew from policy: largest difference between the device's signed timestamp and receipt
     */
    public CaptureBinder(JwsTrust trust, double thetaSame, Duration window, Duration maxSkew) {
        this.trust = Objects.requireNonNull(trust, "trust");
        locks.add(new Locks.NonceLock(maxSkew));
        locks.add(new Locks.ChainLock());
        locks.add(new Locks.IdentityLock(thetaSame));
        locks.add(new Locks.TimeLock(window));
        locks.add(new Locks.OperatorLock(thetaSame));
    }

    public BindingResult bind(BindingInput in) {
        VerifiedCapture current = verifyOrNull(in.capture());
        List<VerifiedCapture> earlier = new ArrayList<>();
        for (SignedCapture c : in.earlierCaptures()) {
            VerifiedCapture v = verifyOrNull(c);
            if (v == null) {
                earlier = null;
                break;
            }
            earlier.add(v);
        }
        List<LockOutcome> outcomes = new ArrayList<>();
        for (BindingLock lock : locks) {
            outcomes.add(lock.check(in, current, earlier));
        }
        boolean chainOk = outcomes.stream().anyMatch(o -> o.lock().equals(Locks.ChainLock.NAME) && o.passed());
        String finalHash = chainOk ? SbiCaptureChain.finalHash(in.evidenceHead(), in.capture().signedData()) : null;
        return new BindingResult(in.nonce(), in.evidenceHead(), outcomes, finalHash);
    }

    private VerifiedCapture verifyOrNull(SignedCapture c) {
        try {
            return VerifiedCapture.verify(c, trust);
        } catch (JwsVerificationException | IOException | IllegalArgumentException e) {
            return null;
        }
    }
}

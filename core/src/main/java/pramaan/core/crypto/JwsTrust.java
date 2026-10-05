/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.crypto;

import java.security.PublicKey;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Who we believe. Device signatures carry an {@code x5c} certificate chain, which must chain to a
 * configured trust anchor (the MOSIP device CA in production). Test signers may instead be pinned by
 * key id. An empty trust set trusts nothing — there is no "accept anything" switch.
 */
public final class JwsTrust {

    private final Set<TrustAnchor> anchors;
    private final Map<String, PublicKey> keysById;
    private final Clock clock;

    private JwsTrust(Set<TrustAnchor> anchors, Map<String, PublicKey> keysById, Clock clock) {
        this.anchors = Collections.unmodifiableSet(anchors);
        this.keysById = Collections.unmodifiableMap(keysById);
        this.clock = clock;
    }

    public static Builder builder(Clock clock) {
        return new Builder(clock);
    }

    PublicKey keyFor(String kid) throws JwsVerificationException {
        PublicKey key = keysById.get(kid);
        if (key == null) {
            throw new JwsVerificationException("no trusted key with id '" + kid + "'");
        }
        return key;
    }

    /** Validates the chain (leaf first) against the anchors at the clock's current time, without revocation. */
    void validateChain(List<X509Certificate> chain) throws JwsVerificationException {
        if (anchors.isEmpty()) {
            throw new JwsVerificationException("no trust anchors configured for x5c certificate chains");
        }
        List<X509Certificate> path = new ArrayList<>(chain);
        // A path must not include the anchor itself.
        while (!path.isEmpty() && isAnchor(path.get(path.size() - 1))) {
            path.remove(path.size() - 1);
        }
        if (path.isEmpty()) {
            throw new JwsVerificationException("x5c contains only a trust anchor, no signing certificate");
        }
        try {
            CertPath certPath = CertificateFactory.getInstance("X.509").generateCertPath(path);
            PKIXParameters params = new PKIXParameters(anchors);
            params.setRevocationEnabled(false);
            params.setDate(Date.from(clock.instant()));
            CertPathValidator.getInstance("PKIX").validate(certPath, params);
        } catch (java.security.GeneralSecurityException e) {
            throw new JwsVerificationException("certificate chain is not trusted: " + e.getMessage(), e);
        }
    }

    private boolean isAnchor(X509Certificate cert) {
        for (TrustAnchor anchor : anchors) {
            if (cert.equals(anchor.getTrustedCert())) {
                return true;
            }
        }
        return false;
    }

    /** Builds a trust set. */
    public static final class Builder {

        private final Clock clock;
        private final Set<TrustAnchor> anchors = new HashSet<>();
        private final Map<String, PublicKey> keysById = new HashMap<>();

        private Builder(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        public Builder anchor(X509Certificate certificate) {
            anchors.add(new TrustAnchor(Objects.requireNonNull(certificate, "certificate"), null));
            return this;
        }

        /** Pins a public key by key id. Intended for test signers such as the mock device. */
        public Builder key(String kid, PublicKey key) {
            keysById.put(Objects.requireNonNull(kid, "kid"), Objects.requireNonNull(key, "key"));
            return this;
        }

        public JwsTrust build() {
            return new JwsTrust(new HashSet<>(anchors), new HashMap<>(keysById), clock);
        }
    }
}

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.android;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.source.ModeDDevice;

/**
 * The signing key of the Mode D device on this phone: one RSA-2048 key created inside Android Keystore the first time it is asked
 * for, usable only to sign (SHA-256 with PKCS#1 v1.5, the JWS "RS256"), and never exportable. Where the phone has a trusted
 * execution environment or a secure element the key lives there ({@link #hardwareBacked}); the app cannot read it, only ask the
 * keystore to sign.
 *
 * <p>The key is named by id in each capture's JWS header and verified against the public key pinned under the same id
 * ({@link #trust}), because a bare keystore key has no certificate chain the verifier would accept (a certificate that is its own
 * trust anchor is refused). A deployment replaces this with a certificate issued under the MOSIP trust chain.
 */
public final class ModeDKey {

    public static final String ALIAS = "pramaan-mode-d";
    /** The key id the JWS header carries and the trust pins. */
    public static final String KEY_ID = "pramaan-mode-d-1";

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final boolean hardwareBacked;

    private ModeDKey(PrivateKey privateKey, PublicKey publicKey, boolean hardwareBacked) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.hardwareBacked = hardwareBacked;
    }

    /** Loads the key, creating it on first use. */
    public static ModeDKey loadOrCreate() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .build());
            generator.generateKeyPair();
        }
        PrivateKey priv = (PrivateKey) store.getKey(ALIAS, null);
        PublicKey pub = store.getCertificate(ALIAS).getPublicKey();
        boolean hardware;
        try {
            KeyInfo info = KeyFactory.getInstance(priv.getAlgorithm(), "AndroidKeyStore").getKeySpec(priv, KeyInfo.class);
            hardware = info.isInsideSecureHardware();
        } catch (GeneralSecurityException e) {
            // Unknown: reported as not hardware-backed, never as a guarantee.
            hardware = false;
        }
        return new ModeDKey(priv, pub, hardware);
    }

    public PublicKey publicKey() {
        return publicKey;
    }

    /** True only when Android reports the key is inside secure hardware (a TEE or a secure element). */
    public boolean hardwareBacked() {
        return hardwareBacked;
    }

    /** What verifies a capture this key signed. */
    public JwsTrust trust(Clock clock) {
        return ModeDDevice.trustForPinnedKey(KEY_ID, publicKey, clock);
    }

    /** The signer to give a {@link ModeDDevice}; the key never leaves the keystore. */
    public ModeDDevice.PayloadSigner payloadSigner() {
        return ModeDDevice.PayloadSigner.withKeyId(KEY_ID, privateKey);
    }
}

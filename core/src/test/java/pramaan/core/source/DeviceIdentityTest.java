/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pramaan.core.crypto.Jws;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.testing.TestClock;

/** Bible Part 5, point 1: a digitalId that is signed must prove it; one that is not signed says nothing. */
class DeviceIdentityTest {

    private static KeyPair provider;
    private static KeyPair stranger;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        provider = gen.generateKeyPair();
        stranger = gen.generateKeyPair();
    }

    private static JwsTrust trust() {
        return JwsTrust.builder(TestClock.at("2026-09-23T10:00:00Z")).key("provider", provider.getPublic()).build();
    }

    private static String signed(KeyPair key, String kid) throws Exception {
        return Jws.signRs256("{\"type\":\"Face\",\"make\":\"Acme\"}".getBytes(StandardCharsets.UTF_8), kid, key.getPrivate());
    }

    @Test
    void aDigitalIdSignedByTheProviderIsVerified() throws Exception {
        assertEquals(DeviceIdentity.VERIFIED, DeviceIdentity.check(signed(provider, "provider"), trust()));
    }

    @Test
    void aDigitalIdSignedByAStrangerFails() throws Exception {
        assertEquals(DeviceIdentity.FAILED, DeviceIdentity.check(signed(stranger, "provider"), trust()));
        assertEquals(DeviceIdentity.FAILED, DeviceIdentity.check(signed(provider, "not-a-known-key"), trust()));
    }

    @Test
    void aTamperedPayloadFails() throws Exception {
        String[] parts = signed(provider, "provider").split("\\.");
        String forged = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"type\":\"Face\",\"make\":\"Other\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(DeviceIdentity.FAILED, DeviceIdentity.check(parts[0] + "." + forged + "." + parts[2], trust()));
    }

    @Test
    void anUnregisteredDeviceWithPlainBase64IsUnsignedNotFailed() {
        String plain = Base64.getEncoder().encodeToString("{\"type\":\"Face\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(DeviceIdentity.UNSIGNED, DeviceIdentity.check(plain, trust()));
        assertEquals(DeviceIdentity.UNSIGNED, DeviceIdentity.check("", trust()));
        assertEquals(DeviceIdentity.UNSIGNED, DeviceIdentity.check(null, trust()));
    }

    @Test
    void withNoTrustNothingIsChecked() throws Exception {
        assertEquals(DeviceIdentity.UNCHECKED, DeviceIdentity.check(signed(provider, "provider"), null));
    }
}

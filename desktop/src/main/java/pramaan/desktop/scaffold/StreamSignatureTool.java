/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.scaffold;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import pramaan.core.Workflow;
import pramaan.core.crypto.JwsTrust;
import pramaan.core.policy.YamlPolicySource;
import pramaan.core.session.SessionContext;
import pramaan.core.source.DeviceEvent;
import pramaan.core.source.Frame;
import pramaan.core.source.FrameSink;
import pramaan.core.source.StreamSignatureProfile;
import pramaan.sbi.SbiConfig;
import pramaan.sbi.SbiFrameSource;

/**
 * Installation step for the stream fingerprint (Bible Part 5): record the JPEG header signature of this station's real
 * camera, so a later session can tell a stream from another encoder (a virtual camera, a replayed file).
 *
 * <pre>
 * ./gradlew :desktop:streamSignature --args="--frames a.jpg b.jpg c.jpg --device 'Acme Cam1' --out build/scaffold/stream-signature.json"
 * ./gradlew :desktop:streamSignature --args="--from-device --device-ca ca.cer --ports 4501-4510 --out build/scaffold/stream-signature.json"
 * </pre>
 *
 * <p>Enrol from a station you trust, with the real camera and nothing virtual installed or selected. Do it again after a
 * firmware update or a camera swap, because those legitimately change the headers.
 */
public final class StreamSignatureTool {

    private static final int FRAMES_FROM_DEVICE = 30;

    private StreamSignatureTool() {
    }

    public static void main(String[] args) throws Exception {
        List<Path> frames = new ArrayList<>();
        Path out = null;
        String device = "unnamed camera";
        boolean fromDevice = false;
        Path ca = null;
        int first = 4501;
        int last = 4510;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--frames":
                    while (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        frames.add(Path.of(args[++i]));
                    }
                    break;
                case "--out":
                    out = Path.of(args[++i]);
                    break;
                case "--device":
                    device = args[++i];
                    break;
                case "--from-device":
                    fromDevice = true;
                    break;
                case "--device-ca":
                    ca = Path.of(args[++i]);
                    break;
                case "--ports":
                    String[] p = args[++i].split("-");
                    first = Integer.parseInt(p[0]);
                    last = Integer.parseInt(p.length > 1 ? p[1] : p[0]);
                    break;
                default:
                    throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (out == null || (frames.isEmpty() && !fromDevice) || (fromDevice && ca == null)) {
            System.err.println("usage: --out <file> and either --frames <jpeg files...> [--device <name>]"
                    + " or --from-device --device-ca <cer> [--ports a-b]");
            System.exit(2);
        }
        List<byte[]> bytes = new ArrayList<>();
        if (fromDevice) {
            bytes.addAll(readFromDevice(ca, first, last));
            device = "SBI device on ports " + first + "-" + last;
        } else {
            for (Path f : frames) {
                bytes.add(Files.readAllBytes(f));
            }
        }
        StreamSignatureProfile profile = StreamSignatureProfile.enrol(device, Clock.systemUTC().instant(), bytes);
        if (out.toAbsolutePath().getParent() != null) {
            Files.createDirectories(out.toAbsolutePath().getParent());
        }
        Files.write(out, profile.toJson().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + out + ": " + profile.signatures().size() + " signature(s)");
        profile.signatures().values().forEach(s -> System.out.println("  " + s));
    }

    private static List<byte[]> readFromDevice(Path ca, int first, int last) throws Exception {
        X509Certificate anchor = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(ca)));
        Clock clock = Clock.systemUTC();
        JwsTrust trust = JwsTrust.builder(clock).anchor(anchor).build();
        List<byte[]> frames = new CopyOnWriteArrayList<>();
        CountDownLatch enough = new CountDownLatch(FRAMES_FROM_DEVICE);
        try (SbiFrameSource source = new SbiFrameSource(SbiConfig.builder(trust, clock).ports(first, last)
                .probeTimeout(Duration.ofSeconds(10)).build())) {
            source.discover();
            SessionContext session = SessionContext.start(Workflow.RESIDENT,
                    YamlPolicySource.of(Path.of("config", "pramaan-policy.yaml")).load(), clock, new SecureRandom());
            source.start(session, new FrameSink() {
                @Override
                public void onFrame(Frame frame) {
                    if (enough.getCount() > 0) {
                        frames.add(frame.copyBytes());
                        enough.countDown();
                    }
                }

                @Override
                public void onDeviceEvent(DeviceEvent event) {
                }
            });
            if (!enough.await(60, TimeUnit.SECONDS)) {
                throw new IOException("the device sent only " + frames.size() + " frames in a minute");
            }
        }
        return frames;
    }
}

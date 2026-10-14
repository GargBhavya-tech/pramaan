/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.engine;

import pramaan.core.source.DeviceIlluminator;
import pramaan.core.source.FlashLight;
import pramaan.core.source.HostCameraInventory;
import pramaan.core.source.PupilSource;
import pramaan.core.source.StereoSource;

/**
 * What a registration station has around the SBI device (Bible Part 31.2, Part 40): a light facing the resident, a
 * second camera, an iris camera, a stored fingerprint of the empty backdrop. Every part is optional, and a station
 * with none of them runs exactly the engine that ships without them — each missing part simply makes its cues abstain.
 *
 * <p>Immutable; build one with {@link #builder()} and hand it to {@link StandardEngines#create}.
 */
public final class Station {

    /** A station with nothing extra: the plain SBI device and the screen. */
    public static final Station NONE = builder().build();

    private final FlashLight flashLight;
    private final DeviceIlluminator illuminator;
    private final StereoSource stereo;
    private final PupilSource pupil;
    private final SceneContext scene;
    private final HostCameraInventory hostCameras;

    private Station(Builder b) {
        this.flashLight = b.flashLight;
        this.illuminator = b.illuminator;
        this.stereo = b.stereo;
        this.pupil = b.pupil;
        this.scene = b.scene;
        this.hostCameras = b.hostCameras;
    }

    public static Builder builder() {
        return new Builder();
    }

    public FlashLight flashLight() {
        return flashLight;
    }

    /** The device's own illuminator, if the station sets one explicitly; otherwise the engine asks the source. */
    public DeviceIlluminator illuminator() {
        return illuminator;
    }

    public StereoSource stereo() {
        return stereo;
    }

    public PupilSource pupil() {
        return pupil;
    }

    public SceneContext scene() {
        return scene;
    }

    /** The platform's camera list, or null if the station cannot enumerate it. */
    public HostCameraInventory hostCameras() {
        return hostCameras;
    }

    /** Builder; every part is optional. */
    public static final class Builder {
        private FlashLight flashLight;
        private DeviceIlluminator illuminator;
        private StereoSource stereo;
        private PupilSource pupil;
        private SceneContext scene;
        private HostCameraInventory hostCameras;

        public Builder flashLight(FlashLight light) {
            this.flashLight = light;
            return this;
        }

        public Builder illuminator(DeviceIlluminator illuminator) {
            this.illuminator = illuminator;
            return this;
        }

        public Builder stereo(StereoSource source) {
            this.stereo = source;
            return this;
        }

        public Builder pupil(PupilSource source) {
            this.pupil = source;
            return this;
        }

        public Builder scene(SceneContext context) {
            this.scene = context;
            return this;
        }

        public Builder hostCameras(HostCameraInventory inventory) {
            this.hostCameras = inventory;
            return this;
        }

        public Station build() {
            return new Station(this);
        }
    }
}

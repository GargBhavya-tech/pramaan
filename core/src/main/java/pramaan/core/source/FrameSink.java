/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

/** Receives what a {@link FaceFrameSource} produces. Called from the source's delivery thread. */
public interface FrameSink {

    void onFrame(Frame frame);

    void onDeviceEvent(DeviceEvent event);
}

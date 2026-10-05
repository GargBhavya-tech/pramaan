/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.source;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Build Map #11: the device interface exposes no HTTP, Android or UI types, so every platform can implement it. */
class FaceFrameSourceContractTest {

    private static final List<String> FORBIDDEN = List.of("java.net.", "javax.net.", "javafx.", "android.", "java.awt.");

    @Test
    void signaturesUseNoPlatformTypes() {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : new Class<?>[] {FaceFrameSource.class, FrameSink.class}) {
            for (Method m : type.getMethods()) {
                List<Type> types = new ArrayList<>(List.of(m.getGenericParameterTypes()));
                types.add(m.getGenericReturnType());
                types.addAll(List.of(m.getGenericExceptionTypes()));
                for (Type t : types) {
                    for (String prefix : FORBIDDEN) {
                        if (t.getTypeName().startsWith(prefix)) {
                            violations.add(type.getSimpleName() + "." + m.getName() + " uses " + t.getTypeName());
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }
}

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.desktop.capture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Text-to-speech for the capture screen (Bible Part 16.1, 19.1; Build Map #91): every prompt and outcome is also
 * spoken, in the screen's language, for residents who cannot read it. The screen speaks only words it shows, so a
 * voice can say nothing the screen may not.
 */
public interface Speech {

    /** No voice. */
    Speech NONE = text -> {
    };

    /** Speaks the text without blocking the caller. */
    void say(String text);

    /**
     * The Windows voice (System.Speech, part of every Windows install), run in the background. A voice for the
     * screen's language must be installed in Windows; otherwise Windows reads with its default voice.
     */
    static Speech windows() {
        return text -> {
            // Single quotes are doubled for a PowerShell string literal; the command travels base64-encoded (UTF-16LE),
            // so no other character of the text can reach the shell as syntax. The text is always our own catalogue's.
            String script = "Add-Type -AssemblyName System.Speech; "
                    + "(New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak('" + text.replace("'", "''") + "')";
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            Thread t = new Thread(() -> {
                try {
                    new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
                } catch (IOException e) {
                    System.err.println("PRAMAAN: speech unavailable: " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "pramaan-speech");
            t.setDaemon(true);
            t.start();
        };
    }

    /** The voice for this platform, when one exists. */
    static Speech forPlatform() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? windows() : NONE;
    }
}

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.policy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Anti-rollback (Bible Part 17.2): the highest bundle version this client has run. An older bundle is refused, so
 * an operator cannot restore last month's looser policy. Production keeps the number in TPM NV storage (desktop) or
 * Keystore-protected storage (Android); {@link #file} is the development stand-in.
 */
public interface RollbackGuard {

    /** The highest version accepted so far, 0 if none. */
    long highestSeen() throws IOException;

    /** Records a newly accepted version; never lowers the stored value. */
    void record(long version) throws IOException;

    /** A guard kept in a plain file (development and tests only: a file is no TPM). */
    static RollbackGuard file(Path path) {
        return new RollbackGuard() {
            @Override
            public long highestSeen() throws IOException {
                return Files.exists(path) ? Long.parseLong(new String(Files.readAllBytes(path), StandardCharsets.US_ASCII).trim()) : 0;
            }

            @Override
            public void record(long version) throws IOException {
                if (version <= highestSeen()) {
                    return;
                }
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
                Files.write(tmp, Long.toString(version).getBytes(StandardCharsets.US_ASCII));
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        };
    }
}

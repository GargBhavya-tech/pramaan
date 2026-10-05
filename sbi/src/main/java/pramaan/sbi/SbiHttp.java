/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.sbi;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import pramaan.core.source.DeviceException;

/** SBI over loopback HTTP with its custom verbs (MOSIPDISC, MOSIPDINFO, STREAM, RCAPTURE). */
final class SbiHttp {

    private final HttpClient client;
    private final String host;

    SbiHttp(String host, Duration connectTimeout) {
        this.host = host;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
    }

    HttpRequest request(int port, String verb, String path, byte[] body, Duration timeout) {
        return HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .method(verb, HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    byte[] call(int port, String verb, String path, byte[] body, Duration timeout) throws DeviceException {
        try {
            HttpResponse<byte[]> response = client.send(request(port, verb, path, body, timeout),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new DeviceException(DeviceException.Reason.PROTOCOL,
                        verb + " " + path + " on port " + port + " returned HTTP " + response.statusCode());
            }
            return response.body();
        } catch (HttpTimeoutException e) {
            throw new DeviceException(DeviceException.Reason.TIMEOUT, verb + " " + path + " timed out on port " + port, e);
        } catch (ConnectException e) {
            throw new DeviceException(DeviceException.Reason.UNAVAILABLE, "nothing listening on port " + port, e);
        } catch (IOException e) {
            throw new DeviceException(DeviceException.Reason.UNAVAILABLE, verb + " " + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceException(DeviceException.Reason.UNAVAILABLE, "interrupted", e);
        }
    }

    HttpResponse<InputStream> openStream(int port, byte[] body, Duration timeout) throws DeviceException {
        try {
            HttpResponse<InputStream> response = client.send(request(port, "STREAM", "/stream", body, timeout),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new DeviceException(DeviceException.Reason.PROTOCOL, "STREAM returned HTTP " + response.statusCode());
            }
            return response;
        } catch (IOException e) {
            throw new DeviceException(DeviceException.Reason.UNAVAILABLE, "cannot open the stream: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceException(DeviceException.Reason.UNAVAILABLE, "interrupted", e);
        }
    }
}

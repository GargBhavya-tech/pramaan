#!/usr/bin/env bash
# Calls every endpoint of the reference SBI device (the one that implements the Liveness Profile) and saves the responses.
# This is how docs/api/examples/profile-device-session.json was made. Needs: bash, curl, openssl, python 3.
#
#   1. Start the device (it serves ONE still photograph, so it is a reference for the transport and the signatures):
#        ./gradlew :harness:runProfileDevice --args="--port 4533 --frame some.jpg --work build/api-demo/ca"
#      Any JPEG will do. Nothing biometric is needed.
#   2. In another terminal, from the repository root:
#        bash docs/api/examples/try-the-device.sh
#   3. Stop the device with Ctrl+C.
set -euo pipefail
H=${H:-http://127.0.0.1:4533}
T=0123456789abcdef0123456789abcdef                                           # a session nonce: 32 hex characters
PREV=E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855        # SHA-256 of the empty string: the first capture
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-build/api-demo}; mkdir -p "$OUT"; cd "$OUT"

echo "1. discover";   curl -s -m 20 -X MOSIPDISC "$H/device" -o disc.json -w "   HTTP %{http_code}\n"

echo "2. stream, with the key agreement (a client ephemeral P-256 key, X.509 SPKI, base64)"
openssl ecparam -name prime256v1 -genkey -noout | openssl pkey -pubout -outform DER | base64 -w0 > client-key.b64
curl -s -m 3 -X STREAM "$H/stream" -H "Content-Type: application/json" \
  -d "{\"deviceId\":\"1\",\"deviceSubId\":\"0\",\"timeout\":\"2000\",\"livenessProfile\":{\"version\":\"0.1\",\"transactionId\":\"$T\",\"clientEphemeralKey\":\"$(cat client-key.b64)\"}}" \
  -D stream.headers -o stream.bin -w "   HTTP %{http_code}\n" || true

echo "3. rcapture"
curl -s -m 20 -X RCAPTURE "$H/capture" -H "Content-Type: application/json" \
  -d "{\"env\":\"Staging\",\"purpose\":\"Registration\",\"specVersion\":\"0.9.5\",\"timeout\":\"10000\",\"captureTime\":\"2026-10-24T05:08:30.000Z\",\"transactionId\":\"$T\",\"bio\":[{\"type\":\"Face\",\"count\":\"1\",\"bioSubType\":[],\"requestedScore\":\"40\",\"deviceId\":\"1\",\"deviceSubId\":\"0\",\"previousHash\":\"$PREV\"}]}" \
  -o rcapture.json -w "   HTTP %{http_code}\n"

echo "4. illuminate"
curl -s -m 20 -X ILLUMINATE "$H/illuminate" -H "Content-Type: application/json" \
  -d "{\"transactionId\":\"$T\",\"schedule\":[{\"colour\":\"GREEN\",\"durationMs\":\"400\"},{\"colour\":\"BLUE\",\"durationMs\":\"450\"}]}" \
  -o illuminate.jws -w "   HTTP %{http_code}\n"

echo "5. captureburst"
curl -s -m 20 -X CAPTUREBURST "$H/captureburst" -H "Content-Type: application/json" \
  -d "{\"transactionId\":\"$T\",\"count\":\"3\",\"intervalMs\":\"200\"}" -o burst.json -w "   HTTP %{http_code}\n"

echo "6. the error paths"
curl -s -m 10 -X GET "$H/device" -o /dev/null -w "   wrong verb:        HTTP %{http_code}\n"
curl -s -m 10 -X RCAPTURE "$H/capture" -H "Content-Type: application/json" -d "not json" -o /dev/null -w "   body not JSON:     HTTP %{http_code}\n"
curl -s -m 10 -X ILLUMINATE "$H/illuminate" -H "Content-Type: application/json" \
  -d '{"transactionId":"ffffffffffffffffffffffffffffffff","schedule":[{"colour":"GREEN","durationMs":"400"}]}' -o /dev/null -w "   unknown session:   HTTP %{http_code}\n"
curl -s -m 10 -X CAPTUREBURST "$H/captureburst" -H "Content-Type: application/json" \
  -d "{\"transactionId\":\"$T\",\"count\":\"500\",\"intervalMs\":\"200\"}" -o /dev/null -w "   burst too large:   HTTP %{http_code}\n"

echo "7. decode the signed parts into readable JSON"
python "$HERE/decode_jws.py" > /dev/null
echo "   wrote $OUT/examples.json"

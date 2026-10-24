import base64, json, re, sys

def b64(s):
    s += "=" * (-len(s) % 4)
    return base64.urlsafe_b64decode(s)

def jws(token):
    h, p, sig = token.strip().split(".")
    header = json.loads(b64(h))
    if "x5c" in header:
        header["x5c"] = ["<%d-char certificate>" % len(c) for c in header["x5c"]]
    payload = json.loads(b64(p))
    return header, payload, len(sig)

def trim(o):
    if isinstance(o, dict):
        return {k: (v[:40] + "...<%d chars>" % len(v) if k in ("bioValue", "digitalId", "data", "clientEphemeralKey", "deviceEphemeralKey") and isinstance(v, str) and len(v) > 60 else trim(v)) for k, v in o.items()}
    if isinstance(o, list):
        return [trim(x) for x in o]
    return o

out = {}
disc = json.load(open("disc.json"))
d = disc[0]
h, p, _ = jws(d["digitalId"])
out["discovery"] = trim(d)
out["discovery_digitalId_header"] = h
out["discovery_digitalId_payload"] = p
hd = open("stream.headers", "r", errors="replace").read()
ans = re.search(r"(?i)x-sbi-lp-answer:\s*(\S+)", hd).group(1)
h, p, _ = jws(ans)
out["stream_answer_header"] = h
out["stream_answer_payload"] = trim(p)
raw = open("stream.bin", "rb").read()
first = raw.split(b"\xff\xd8")[0].decode("ascii", "replace")
out["stream_part_headers"] = first.strip().splitlines()
cps = re.findall(rb"X-SBI-LP-Checkpoint:(\S+)", raw)
if cps:
    h, p, _ = jws(cps[0].decode())
    out["stream_checkpoint_payload"] = p
rc = json.load(open("rcapture.json"))
e = rc["biometrics"][0]
h, p, _ = jws(e["data"])
out["rcapture_entry"] = trim(e)
out["rcapture_payload"] = trim(p)
out["rcapture_hash"] = e["hash"]
h, p, _ = jws(open("illuminate.jws").read())
out["illuminate_payload"] = p
bj = json.load(open("burst.json"))
out["burst"] = [{"index": trim(jws(x["data"])[1]).get("burstIndex"), "hash": x["hash"], "transactionId": jws(x["data"])[1]["transactionId"]} for x in bj["biometrics"]]
json.dump(out, open("examples.json", "w"), indent=2)
print(json.dumps(out, indent=2)[:6500])

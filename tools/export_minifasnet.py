# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
"""Export Minivision's MiniFASNet weights (Apache-2.0) to ONNX for the passive scorer (Build Map #24).

Needs the Silent-Face-Anti-Spoofing checkout in third_party/ and the tools venv with torch + onnx:

    tools/.venv/Scripts/python tools/export_minifasnet.py

Writes models/minifasnet_v2_2.7_80x80.onnx and models/minifasnet_v1se_4.0_80x80.onnx, each with input
"input" [1,3,80,80] float (BGR, raw 0-255 - NOT divided by 255, exactly as the reference ToTensor does)
and output "logits" [1,3] (pre-softmax; class 1 = real). Exporting logits, not probabilities, because the
engine averages logits over time (Build Map #25).

Then checks parity: PyTorch and ONNX outputs must agree to 1e-4 on random inputs.
"""

import hashlib
import pathlib
import sys
from collections import OrderedDict

import numpy as np
import torch

REPO = pathlib.Path("third_party/Silent-Face-Anti-Spoofing")
OUT = pathlib.Path("models")
sys.path.insert(0, str(REPO))

from src.model_lib.MiniFASNet import MiniFASNetV1SE, MiniFASNetV2  # noqa: E402

MODELS = [
    # (weights file, class, output name, scale)
    ("2.7_80x80_MiniFASNetV2.pth", MiniFASNetV2, "minifasnet_v2_2.7_80x80.onnx", 2.7),
    ("4_0_0_80x80_MiniFASNetV1SE.pth", MiniFASNetV1SE, "minifasnet_v1se_4.0_80x80.onnx", 4.0),
]


def kernel(h, w):
    return ((h + 15) // 16, (w + 15) // 16)  # same as src/utility.get_kernel


def load(weights, cls):
    model = cls(conv6_kernel=kernel(80, 80))
    state = torch.load(REPO / "resources" / "anti_spoof_models" / weights, map_location="cpu")
    if next(iter(state)).startswith("module."):
        state = OrderedDict((k[len("module."):], v) for k, v in state.items())
    model.load_state_dict(state)
    model.eval()
    return model


def main():
    OUT.mkdir(exist_ok=True)
    import onnx
    for weights, cls, name, scale in MODELS:
        model = load(weights, cls)
        dummy = torch.rand(1, 3, 80, 80) * 255.0
        path = OUT / name
        torch.onnx.export(model, dummy, str(path), input_names=["input"], output_names=["logits"],
                          opset_version=13, dynamo=False)
        m = onnx.load(str(path))
        onnx.checker.check_model(m)

        # Parity: ONNX Runtime (if present) or onnx's reference evaluator vs PyTorch.
        rng = np.random.default_rng(7)
        x = (rng.random((4, 1, 3, 80, 80)) * 255.0).astype(np.float32)
        try:
            import onnxruntime as ort
            sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
            run = lambda a: sess.run(None, {"input": a})[0]
        except ImportError:
            from onnx.reference import ReferenceEvaluator
            ref = ReferenceEvaluator(m)
            run = lambda a: ref.run(None, {"input": a})[0]
        worst = 0.0
        with torch.no_grad():
            for sample in x:
                expected = model(torch.from_numpy(sample)).numpy()
                worst = max(worst, float(np.abs(run(sample) - expected).max()))
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        status = "OK" if worst < 1e-4 else "MISMATCH"
        print(f"{name}: IR {m.ir_version}, opset {m.opset_import[0].version}, {path.stat().st_size} bytes, "
              f"max |onnx - torch| = {worst:.2e} [{status}], sha256 {digest}")
        if worst >= 1e-4:
            raise SystemExit(f"parity check failed for {name}")


if __name__ == "__main__":
    main()

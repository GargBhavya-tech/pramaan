# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Generate the tiny ONNX model used by the ONNX Runtime smoke test (Build Map #2).

The model computes Y = 2 * X + 1 for X of shape [1, 4]. It is built from scratch, so it has no
licence or provenance questions, and it is small enough (~200 bytes) to commit as a test fixture.

Run from the repo root with the project venv:
    tools/.venv/Scripts/python tools/make_smoke_model.py
"""

import pathlib

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

OUT = pathlib.Path("core/src/test/resources/models/smoke_affine.onnx")

# Pin the format explicitly. ONNX Runtime rejects models whose IR version or opset is newer than it
# supports, and a fresh `onnx` package would otherwise stamp its own (possibly newer) defaults.
IR_VERSION = 8
OPSET = 13


def build() -> onnx.ModelProto:
    x = helper.make_tensor_value_info("x", TensorProto.FLOAT, [1, 4])
    y = helper.make_tensor_value_info("y", TensorProto.FLOAT, [1, 4])
    two = numpy_helper.from_array(np.array([2.0], dtype=np.float32), name="two")
    one = numpy_helper.from_array(np.array([1.0], dtype=np.float32), name="one")
    nodes = [
        helper.make_node("Mul", ["x", "two"], ["scaled"], name="scale"),
        helper.make_node("Add", ["scaled", "one"], ["y"], name="shift"),
    ]
    graph = helper.make_graph(nodes, "pramaan_smoke_affine", [x], [y], initializer=[two, one])
    model = helper.make_model(
        graph,
        producer_name="pramaan-tools",
        opset_imports=[helper.make_opsetid("", OPSET)],
    )
    model.ir_version = IR_VERSION
    onnx.checker.check_model(model)
    return model


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(build(), str(OUT))
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes, IR {IR_VERSION}, opset {OPSET})")


if __name__ == "__main__":
    main()

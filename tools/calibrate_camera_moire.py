#!/usr/bin/env python3
"""
Camera Moiré Calibration Tool for PRAMAAN.
Measures 2D FFT peakiness distribution on live faces vs. screen replays
and says whether moire earns a vote on this camera at all. The result goes into the signed operating-point table
(moireCalibration.cameras), never into a launch flag.
Matches MoireCue.java exact frequency annulus, Hann window, and JPEG lattice exclusions.
"""

import sys
import os
import argparse
import numpy as np

N = 128
R1 = 12
R2 = 60
AXIS_BAND = 2
JPEG_BLOCK = 8
LATTICE_BAND = 1

def near_block_lattice(f):
    period = N // JPEG_BLOCK
    m = f % period
    return m <= LATTICE_BAND or m >= period - LATTICE_BAND

def compute_peakiness(crop_gray_128):
    """
    Computes peakiness on a 128x128 grayscale uint8 or float image.
    Matches MoireCue.peakiness exactly.
    """
    assert crop_gray_128.shape == (N, N), f"Expected shape ({N}, {N}), got {crop_gray_128.shape}"
    gray = crop_gray_128.astype(np.float64)
    mean_val = np.mean(gray)
    
    # 2D Periodic Hann window
    x = np.arange(N)
    y = np.arange(N)
    hann_x = 0.5 * (1 - np.cos(2 * np.pi * x / N))
    hann_y = 0.5 * (1 - np.cos(2 * np.pi * y / N))
    hann_2d = np.outer(hann_y, hann_x)
    
    windowed = (gray - mean_val) * hann_2d
    fft_vals = np.fft.fft2(windowed)
    
    mags = []
    max_mag = 0.0
    
    for v in range(N):
        fv = v if v <= N // 2 else v - N
        if abs(fv) <= AXIS_BAND:
            continue
        v_lattice = near_block_lattice(fv)
        for u in range(N):
            fu = u if u <= N // 2 else u - N
            if abs(fu) <= AXIS_BAND:
                continue
            r = np.hypot(fu, fv)
            if r < R1 or r > R2:
                continue
            if v_lattice and near_block_lattice(fu):
                continue
            m = np.abs(fft_vals[v, u])
            mags.append(m)
            if m > max_mag:
                max_mag = m
                
    if not mags:
        return 0.0
    median_mag = float(np.median(mags))
    if median_mag == 0:
        return 0.0
    return float(max_mag / median_mag)

def sample_webcam(device_id=0, num_samples=30, label="live"):
    import cv2
    cap = cv2.VideoCapture(device_id)
    if not cap.isOpened():
        print(f"Error: Could not open camera {device_id}")
        return []
    
    # Simple Haar or YuNet detector to crop the face
    cascade_path = cv2.data.haarcascades + 'haarcascade_frontalface_default.xml'
    face_cascade = cv2.CascadeClassifier(cascade_path)
    
    print(f"\n--- Sampling {label.upper()} face on camera {device_id} ---")
    if label == "live":
        print("Look directly into the camera. Hold still at normal distance (40-60 cm).")
    else:
        print("Hold up a phone screen replaying a face in front of the camera.")
    print("Press SPACE to capture a frame, or 'q' to finish early.")
    
    peakiness_values = []
    while len(peakiness_values) < num_samples:
        ret, frame = cap.read()
        if not ret:
            break
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        faces = face_cascade.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=5, minSize=(128, 128))
        
        display = frame.copy()
        if len(faces) > 0:
            x, y, w, h = faces[0]
            cv2.rectangle(display, (x, y), (x+w, y+h), (0, 255, 0), 2)
            cv2.putText(display, f"Samples: {len(peakiness_values)}/{num_samples} (Press SPACE)",
                        (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 0), 2)
        else:
            cv2.putText(display, "No face detected >= 128px", (10, 30),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 255), 2)
            
        cv2.imshow(f"Moiré Calibration - {label}", display)
        key = cv2.waitKey(1) & 0xFF
        if key == ord('q'):
            break
        elif key == ord(' ') and len(faces) > 0:
            x, y, w, h = faces[0]
            # Center 128x128 crop without rescaling
            cx, cy = x + w // 2, y + h // 2
            x0 = max(0, cx - N // 2)
            y0 = max(0, cy - N // 2)
            crop = gray[y0:y0+N, x0:x0+N]
            if crop.shape == (N, N):
                p = compute_peakiness(crop)
                peakiness_values.append(p)
                print(f"Sample {len(peakiness_values)}: peakiness = {p:.2f}")

    cap.release()
    cv2.destroyAllWindows()
    return peakiness_values

def main():
    parser = argparse.ArgumentParser(description="Calibrate Moiré Cue for webcam")
    parser.add_argument("--camera", type=int, default=0, help="Camera index")
    parser.add_argument("--samples", type=int, default=15, help="Number of samples to capture per condition")
    parser.add_argument("--live-dir", type=str, help="Directory of 128x128 crop images of live faces")
    parser.add_argument("--replay-dir", type=str, help="Directory of 128x128 crop images of screen replays")
    parser.add_argument("--name", type=str, help="The camera as the device reports it: make and model, for example \"Xiaomi 22071219AI\"")
    args = parser.parse_args()

    live_vals = []
    replay_vals = []

    if args.live_dir:
        import cv2
        for f in os.listdir(args.live_dir):
            p = os.path.join(args.live_dir, f)
            img = cv2.imread(p, cv2.IMREAD_GRAYSCALE)
            if img is not None and img.shape >= (N, N):
                cx, cy = img.shape[1] // 2, img.shape[0] // 2
                crop = img[cy - N//2:cy + N//2, cx - N//2:cx + N//2]
                live_vals.append(compute_peakiness(crop))
    elif sys.stdin.isatty():
        live_vals = sample_webcam(args.camera, args.samples, "live")

    if args.replay_dir:
        import cv2
        for f in os.listdir(args.replay_dir):
            p = os.path.join(args.replay_dir, f)
            img = cv2.imread(p, cv2.IMREAD_GRAYSCALE)
            if img is not None and img.shape >= (N, N):
                cx, cy = img.shape[1] // 2, img.shape[0] // 2
                crop = img[cy - N//2:cy + N//2, cx - N//2:cx + N//2]
                replay_vals.append(compute_peakiness(crop))
    elif sys.stdin.isatty() and live_vals:
        replay_vals = sample_webcam(args.camera, args.samples, "replay")

    if not live_vals and not replay_vals:
        print("No samples collected. Use --live-dir and --replay-dir, or run interactively with a webcam.")
        return

    print("\n================ CALIBRATION REPORT ================")
    if live_vals:
        print(f"Live Face Peakiness (N={len(live_vals)}):")
        print(f"  Mean:   {np.mean(live_vals):.2f}")
        print(f"  Std:    {np.std(live_vals):.2f}")
        print(f"  Median: {np.median(live_vals):.2f}")
        print(f"  Range:  [{np.min(live_vals):.2f}, {np.max(live_vals):.2f}]")
    if replay_vals:
        print(f"\nReplay Attack Peakiness (N={len(replay_vals)}):")
        print(f"  Mean:   {np.mean(replay_vals):.2f}")
        print(f"  Std:    {np.std(replay_vals):.2f}")
        print(f"  Median: {np.median(replay_vals):.2f}")
        print(f"  Range:  [{np.min(replay_vals):.2f}, {np.max(replay_vals):.2f}]")

    if live_vals and replay_vals:
        live = np.asarray(live_vals)
        replay = np.asarray(replay_vals)
        # Mann-Whitney: the chance that a random replay frame is peakier than a random live frame (0.5 = no information)
        greater = sum(1 for r in replay for l in live if r > l) + 0.5 * sum(1 for r in replay for l in live if r == l)
        auc = greater / (len(replay) * len(live))
        # the threshold that flags at most 2% of the live frames, and what it then catches
        candidate = float(np.percentile(live, 98))
        caught = float(np.mean(replay > candidate))
        print(f"\nSeparation: area under the curve {auc:.2f} (0.5 means moire tells live from replay no better than a coin).")
        print(f"A threshold of {candidate:.1f} flags 2% of the live frames and catches {100 * caught:.0f}% of the replay frames.")
        enough = len(live) >= 30 and len(replay) >= 30
        if auc >= 0.95 and caught >= 0.80 and enough:
            mode = "vote"
            print("Moire separates on this camera, at this distance, in these conditions: it may vote.")
        else:
            mode = "abstain"
            why = "separation is not good enough" if enough else "fewer than 30 frames of each kind"
            print(f"Moire does NOT earn a vote on this camera ({why}): leave it abstaining.")
        name = args.name or "<make> <model>"
        basis = (f"Measured {len(live)} live and {len(replay)} replay frames: live median {np.median(live):.1f} "
                 f"({live.min():.1f} to {live.max():.1f}), replay median {np.median(replay):.1f} "
                 f"({replay.min():.1f} to {replay.max():.1f}), area under the curve {auc:.2f}.")
        print("\nTo record this in the signed table, add under moireCalibration.cameras in config/operating-points.json,")
        print("then rebuild the bundle (the table is hashed and signed with the rest; there is no launch flag):\n")
        import json
        print(json.dumps({name: {"mode": mode, "peakinessThreshold": round(candidate, 1), "slope": 0.6, "basis": basis}}, indent=2))

if __name__ == "__main__":
    main()

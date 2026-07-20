package com.cameracheck.domain;

/**
 * Optional capability of a {@link CameraConnection}: pan/tilt control of a
 * motorized camera. Like {@link OnvifProvisioning}, the API layer discovers
 * support via {@code instanceof}; fixed-lens drivers simply don't implement it.
 */
public interface PtzControl {

    /**
     * Starts (or stops) a continuous pan/tilt movement.
     *
     * @param channel 1-based camera channel (Nth video source / media profile)
     * @param pan pan speed −100…100 (negative = left, 0 = stop panning);
     *        values outside the range are clamped
     * @param tilt tilt speed −100…100 (negative = down, 0 = stop tilting);
     *        values outside the range are clamped
     *        <p>{@code pan == 0 && tilt == 0} stops all movement.
     */
    void continuousMove(int channel, int pan, int tilt);

    /** Clamps a speed to the contract range −100…100. */
    static int clampSpeed(int speed) {
        return Math.max(-100, Math.min(100, speed));
    }
}

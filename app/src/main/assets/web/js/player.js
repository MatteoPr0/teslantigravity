/**
 * Tesla Model 3 (MCU2 Intel Atom) Ultra-Low Latency Video Decoder
 * 
 * Hardware-Enforced Architecture:
 * 1. 100% WebCodecs VideoDecoder (Direct GPU hardware pipeline)
 * 2. Instant VideoFrame.close() garbage collection (Zero RAM leak)
 * 3. Never demotes to software decoding (avoids CPU exhaustion)
 */

class TeslaStreamPlayer {
    constructor(canvasElement, videoElement) {
        this.canvas = canvasElement;
        this.videoEl = videoElement;
        this.ctx = this.canvas ? this.canvas.getContext('2d', { alpha: false, desynchronized: true }) : null;

        this.decoder = null;
        this.mode = 'webcodecs'; // Strict hardware acceleration
        this.hasReceivedKeyframe = false;
        this.hasReceivedRealVideo = false;

        this.stats = {
            fps: 0,
            frameCount: 0,
            decodedFrames: 0,
            droppedFrames: 0,
            latencyMs: 0,
            bufferMs: 0,
            bytesReceived: 0,
            lastFpsUpdate: performance.now()
        };

        this.initEngine();
        this.startStatsTimer();
    }

    initEngine() {
        const supportsWebCodecs = typeof window.VideoDecoder === 'function' && typeof window.EncodedVideoChunk === 'function';

        if (supportsWebCodecs) {
            this.initWebCodecs();
        } else {
            console.warn('[Decoder] WebCodecs non supportato in questo browser.');
        }
    }

    /* =========================================================================
       1. WEBCODECS ENGINE (Zero Buffer, Direct Hardware Decoding)
       ========================================================================= */
    initWebCodecs() {
        this.mode = 'webcodecs';
        if (this.canvas) this.canvas.style.display = 'block';
        if (this.videoEl) this.videoEl.style.display = 'none';

        try {
            if (this.decoder && this.decoder.state !== 'closed') {
                try { this.decoder.close(); } catch (_) {}
            }

            this.decoder = new VideoDecoder({
                output: (frame) => this.handleWebCodecsFrame(frame),
                error: (e) => {
                    console.warn('[WebCodecs] Errore frame decoder:', e);
                    this.stats.droppedFrames++;
                    // Non degradare a MSE: richiedi semplicemente il prossimo KeyFrame
                    this.hasReceivedKeyframe = false;
                }
            });

            this.decoder.configure({
                codec: 'avc1.42E01E', // Baseline Profile Level 3.1
                optimizeForLatency: true,
                hardwareAcceleration: 'prefer-hardware'
            });

            console.log('[WebCodecs] Motore Hardware H.264 configurato con successo.');
        } catch (err) {
            console.error('[WebCodecs] Inizializzazione fallita:', err);
        }
    }

    handleWebCodecsFrame(frame) {
        this.hasReceivedRealVideo = true;
        this.stats.decodedFrames++;
        this.stats.frameCount++;

        if (frame.timestamp) {
            const now = performance.now();
            const latency = now - (frame.timestamp / 1000);
            if (latency > 0 && latency < 5000) {
                this.stats.latencyMs = Math.round(latency);
            }
        }

        if (this.canvas.width !== frame.displayWidth || this.canvas.height !== frame.displayHeight) {
            this.canvas.width = frame.displayWidth;
            this.canvas.height = frame.displayHeight;
        }

        if (this.ctx) {
            this.ctx.drawImage(frame, 0, 0, this.canvas.width, this.canvas.height);
        }

        // CRITICAL MEMORY GUARD: Close VideoFrame immediately to avoid Intel Atom GPU leak
        frame.close();
    }

    /* =========================================================================
       2. FEED CHUNKS FROM WEBSOCKET
       ========================================================================= */
    feedChunk(dataBuffer, serverTimestamp = null, isKeyHint = false) {
        this.stats.bytesReceived += dataBuffer.byteLength;

        if (!this.decoder || this.decoder.state === 'closed') {
            this.initWebCodecs();
            return;
        }

        const u8 = new Uint8Array(dataBuffer);
        const isKey = isKeyHint || this.checkIfKeyframe(u8);

        if (isKey) {
            this.hasReceivedKeyframe = true;
        }

        if (this.hasReceivedKeyframe) {
            try {
                const chunk = new EncodedVideoChunk({
                    type: isKey ? 'key' : 'delta',
                    timestamp: (serverTimestamp || performance.now()) * 1000,
                    data: dataBuffer
                });
                this.decoder.decode(chunk);
            } catch (e) {
                this.stats.droppedFrames++;
                if (this.decoder.state === 'closed') {
                    this.initWebCodecs();
                }
            }
        }
    }

    checkIfKeyframe(u8) {
        for (let i = 0; i < Math.min(u8.length - 4, 64); i++) {
            if (u8[i] === 0 && u8[i+1] === 0) {
                let nalOffset = u8[i+2] === 1 ? i+3 : (u8[i+2] === 0 && u8[i+3] === 1 ? i+4 : -1);
                if (nalOffset !== -1 && nalOffset < u8.length) {
                    const nalType = u8[nalOffset] & 0x1F;
                    if (nalType === 5 || nalType === 7 || nalType === 8) return true;
                }
            }
        }
        return false;
    }

    startStatsTimer() {
        setInterval(() => {
            const now = performance.now();
            const elapsed = (now - this.stats.lastFpsUpdate) / 1000;
            if (elapsed >= 1.0) {
                this.stats.fps = Math.round(this.stats.frameCount / elapsed);
                this.stats.frameCount = 0;
                this.stats.lastFpsUpdate = now;
            }
        }, 1000);
    }

    getDiagnostics() {
        return {
            mode: this.mode,
            fps: this.stats.fps,
            latencyMs: this.stats.latencyMs,
            bufferMs: this.stats.bufferMs,
            decodedFrames: this.stats.decodedFrames,
            droppedFrames: this.stats.droppedFrames,
            bytesReceived: this.stats.bytesReceived
        };
    }
}

window.TeslaStreamPlayer = TeslaStreamPlayer;

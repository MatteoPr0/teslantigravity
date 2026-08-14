/**
 * Tesla Model 3 Ultra-Low Latency Video Player Engine
 * 
 * Optimized specifically for Intel Atom (MCU2) Chromium sandbox:
 * 1. WebCodecs Hardware Accelerated VideoDecoder (Primary, Zero Buffer)
 * 2. MediaSource Sliding-Window Buffer Purging (Fallback, Zero Memory Leak)
 * 3. Video-Only Strict Isolation (Guards against Audio IPC crashes during Drive)
 */

class VideoPlayer {
    constructor(options = {}) {
        this.canvas = document.getElementById(options.canvasId || 'video-canvas');
        this.ctx = this.canvas ? this.canvas.getContext('2d', { alpha: false, desynchronized: true }) : null;
        this.videoEl = document.getElementById(options.videoId || 'video-player');
        
        this.mode = 'unknown'; // 'webcodecs' | 'mse'
        this.decoder = null;
        this.mediaSource = null;
        this.sourceBuffer = null;
        this.mseQueue = [];
        this.isMseInit = false;
        
        // Performance & Telemetry metrics
        this.stats = {
            fps: 0,
            frameCount: 0,
            lastFpsUpdate: performance.now(),
            latencyMs: 0,
            bufferMs: 0,
            decodedFrames: 0,
            droppedFrames: 0,
            bitrateKbps: 0,
            bytesReceived: 0,
            lastBitrateUpdate: performance.now()
        };

        // Cache for SPS/PPS parameters for H.264 re-initialization
        this.cachedSps = null;
        this.cachedPps = null;
        this.hasReceivedKeyframe = false;

        // Auto-detect best engine
        this.initEngine();
        this.startTelemetryLoop();
    }

    initEngine() {
        const preferredEngine = localStorage.getItem('tesla_engine_pref') || 'auto';
        const supportsWebCodecs = typeof window.VideoDecoder === 'function' && typeof window.EncodedVideoChunk === 'function';

        if ((preferredEngine === 'auto' || preferredEngine === 'webcodecs') && supportsWebCodecs) {
            this.initWebCodecs();
        } else {
            this.initMSE();
        }
    }

    /* =========================================================================
       1. WEBCODECS ENGINE (Zero Buffer, Direct Hardware Decoding)
       ========================================================================= */
    initWebCodecs() {
        console.log('[Player] Inizializzazione WebCodecs Hardware Decoder...');
        this.mode = 'webcodecs';
        
        if (this.canvas) this.canvas.style.display = 'block';
        if (this.videoEl) this.videoEl.style.display = 'none';

        try {
            this.decoder = new VideoDecoder({
                output: (frame) => this.handleWebCodecsFrame(frame),
                error: (e) => {
                    console.error('[WebCodecs] Errore di decodifica:', e);
                    this.stats.droppedFrames++;
                    // Fallback to MSE if WebCodecs crashes
                    if (this.stats.droppedFrames > 10 && this.mode === 'webcodecs') {
                        console.warn('[Player] Troppi errori WebCodecs, fallback su MSE...');
                        this.initMSE();
                    }
                }
            });

            // Configure H.264 Baseline Profile Level 3.1 (Optimal for Intel Atom MCU2)
            this.decoder.configure({
                codec: 'avc1.42E01E', // Baseline Profile Level 3.0/3.1
                optimizeForLatency: true,
                hardwareAcceleration: 'prefer-hardware'
            });

            console.log('[Player] WebCodecs configurato con successo (H.264 Low-Latency).');
        } catch (err) {
            console.warn('[Player] Fallimento configurazione WebCodecs, fallback su MSE:', err);
            this.initMSE();
        }
    }

    handleWebCodecsFrame(frame) {
        this.stats.decodedFrames++;
        this.stats.frameCount++;

        // Calculate end-to-end latency if timestamp metadata is present
        if (frame.timestamp) {
            const now = performance.now();
            const latency = now - (frame.timestamp / 1000);
            if (latency > 0 && latency < 5000) {
                this.stats.latencyMs = Math.round(latency);
            }
        }

        // Adjust canvas resolution dynamically to match video frame
        if (this.canvas.width !== frame.displayWidth || this.canvas.height !== frame.displayHeight) {
            this.canvas.width = frame.displayWidth;
            this.canvas.height = frame.displayHeight;
        }

        // Paint frame to 2D Hardware-Accelerated Canvas
        if (this.ctx) {
            this.ctx.drawImage(frame, 0, 0, this.canvas.width, this.canvas.height);
        }

        // CRITICAL MEMORY GUARD: Must close VideoFrame immediately to prevent GPU memory leak on Intel Atom!
        frame.close();
    }

    /* =========================================================================
       2. MEDIASOURCE (MSE) ENGINE (Sliding-Window Buffer Purging)
       ========================================================================= */
    initMSE() {
        console.log('[Player] Inizializzazione MediaSource (MSE) Low-Latency Engine...');
        this.mode = 'mse';

        if (this.canvas) this.canvas.style.display = 'none';
        if (this.videoEl) {
            this.videoEl.style.display = 'block';
            this.videoEl.muted = true;
            this.videoEl.playsInline = true;
            this.videoEl.autoplay = true;
        }

        this.mediaSource = new MediaSource();
        this.videoEl.src = URL.createObjectURL(this.mediaSource);

        this.mediaSource.addEventListener('sourceopen', () => {
            console.log('[MSE] MediaSource aperto. Configurazione SourceBuffer...');
            try {
                // Codec: avc1.42E01E (H.264 Baseline Level 3.1)
                const mime = 'video/mp4; codecs="avc1.42E01E"';
                if (MediaSource.isTypeSupported(mime)) {
                    this.sourceBuffer = this.mediaSource.addSourceBuffer(mime);
                    this.sourceBuffer.mode = 'sequence';

                    this.sourceBuffer.addEventListener('updateend', () => {
                        this.processMseQueue();
                        this.purgePastBuffer();
                        this.catchupLiveEdge();
                    });

                    this.isMseInit = true;
                    this.processMseQueue();
                } else {
                    console.error('[MSE] Mime type non supportato dal browser Tesla:', mime);
                }
            } catch (err) {
                console.error('[MSE] Errore creazione SourceBuffer:', err);
            }
        });
    }

    processMseQueue() {
        if (!this.sourceBuffer || this.sourceBuffer.updating || this.mseQueue.length === 0) {
            return;
        }

        const chunk = this.mseQueue.shift();
        try {
            this.sourceBuffer.appendBuffer(chunk);
            this.stats.decodedFrames++;
            this.stats.frameCount++;
        } catch (e) {
            if (e.name === 'QuotaExceededError') {
                console.warn('[MSE] QuotaExceeded! Svuotamento forzato buffer...');
                this.forceFlushMseBuffer();
            } else {
                console.error('[MSE] Errore appendBuffer:', e);
            }
        }
    }

    /**
     * SLIDING WINDOW BUFFER PURGE:
     * Removes past played frames from RAM every second.
     * Essential for Tesla Model 3 (Intel Atom) to avoid OOM "Aw, Snap!" crashes.
     */
    purgePastBuffer() {
        if (!this.sourceBuffer || this.sourceBuffer.updating || !this.videoEl) return;
        
        try {
            const currentTime = this.videoEl.currentTime;
            // Keep only 0.3 seconds behind current playhead
            if (currentTime > 0.8 && this.sourceBuffer.buffered.length > 0) {
                const start = this.sourceBuffer.buffered.start(0);
                const purgeUntil = currentTime - 0.3;
                if (purgeUntil > start) {
                    this.sourceBuffer.remove(start, purgeUntil);
                }
            }
        } catch (err) {
            console.warn('[MSE] Errore svuotamento buffer:', err);
        }
    }

    /**
     * LIVE-EDGE CATCH-UP:
     * If browser lags behind live stream, accelerates playback or seeks directly.
     */
    catchupLiveEdge() {
        if (!this.videoEl || this.videoEl.buffered.length === 0) return;

        const bufferEnd = this.videoEl.buffered.end(0);
        const drift = bufferEnd - this.videoEl.currentTime;
        this.stats.bufferMs = Math.round(drift * 1000);

        if (drift > 0.20) {
            // Hard jump to live edge if delay > 200ms
            this.videoEl.currentTime = bufferEnd - 0.02;
            this.videoEl.playbackRate = 1.0;
        } else if (drift > 0.06) {
            // Micro catchup: 6% speed increase to smoothly eliminate micro-delay
            this.videoEl.playbackRate = 1.06;
        } else {
            this.videoEl.playbackRate = 1.0;
        }
    }

    forceFlushMseBuffer() {
        if (!this.sourceBuffer || this.sourceBuffer.updating) return;
        try {
            if (this.sourceBuffer.buffered.length > 0) {
                this.sourceBuffer.remove(0, this.sourceBuffer.buffered.end(0));
            }
        } catch (e) {
            console.error('[MSE] Errore flush:', e);
        }
    }

    /* =========================================================================
       3. FEED CHUNKS FROM WEBSOCKET
       ========================================================================= */
    feedChunk(dataBuffer, serverTimestamp = null) {
        this.stats.bytesReceived += dataBuffer.byteLength;

        if (this.mode === 'webcodecs') {
            this.feedWebCodecsChunk(dataBuffer, serverTimestamp);
        } else if (this.mode === 'mse') {
            this.mseQueue.push(dataBuffer);
            this.processMseQueue();
        }
    }

    feedWebCodecsChunk(dataBuffer, serverTimestamp) {
        if (!this.decoder || this.decoder.state === 'closed') return;

        const u8 = new Uint8Array(dataBuffer);
        const isKey = this.checkIfKeyframe(u8);

        if (!this.hasReceivedKeyframe && !isKey) {
            // Drop delta frames until first IDR / Keyframe arrives
            this.stats.droppedFrames++;
            return;
        }
        this.hasReceivedKeyframe = true;

        const timestampMicros = (serverTimestamp || performance.now()) * 1000;

        try {
            const chunk = new EncodedVideoChunk({
                type: isKey ? 'key' : 'delta',
                timestamp: timestampMicros,
                data: dataBuffer
            });
            this.decoder.decode(chunk);
        } catch (err) {
            console.error('[WebCodecs] Errore decode chunk:', err);
            this.stats.droppedFrames++;
        }
    }

    /**
     * Inspect NAL unit headers for H.264 IDR frames (NAL type 5) or SPS/PPS (7/8)
     */
    checkIfKeyframe(u8) {
        // Fast scan for Annex-B start codes: 0x00 0x00 0x01 or 0x00 0x00 0x00 0x01
        for (let i = 0; i < Math.min(u8.length - 4, 64); i++) {
            if (u8[i] === 0 && u8[i+1] === 0) {
                let nalOffset = -1;
                if (u8[i+2] === 1) {
                    nalOffset = i + 3;
                } else if (u8[i+2] === 0 && u8[i+3] === 1) {
                    nalOffset = i + 4;
                }

                if (nalOffset !== -1 && nalOffset < u8.length) {
                    const nalType = u8[nalOffset] & 0x1F;
                    if (nalType === 5 || nalType === 7 || nalType === 8) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /* =========================================================================
       4. TELEMETRY & FPS CALCULATOR
       ========================================================================= */
    startTelemetryLoop() {
        setInterval(() => {
            const now = performance.now();
            const elapsedFps = (now - this.stats.lastFpsUpdate) / 1000;
            if (elapsedFps >= 1.0) {
                this.stats.fps = Math.round(this.stats.frameCount / elapsedFps);
                this.stats.frameCount = 0;
                this.stats.lastFpsUpdate = now;
            }

            const elapsedBitrate = (now - this.stats.lastBitrateUpdate) / 1000;
            if (elapsedBitrate >= 1.0) {
                this.stats.bitrateKbps = Math.round((this.stats.bytesReceived * 8) / (elapsedBitrate * 1000));
                this.stats.bytesReceived = 0;
                this.stats.lastBitrateUpdate = now;
            }
        }, 1000);
    }
}

window.VideoPlayer = VideoPlayer;

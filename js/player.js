/**
 * Tesla Model 3 Ultra-Low Latency Video Player Engine
 * 
 * Optimized specifically for Intel Atom (MCU2) Chromium sandbox:
 * 1. WebCodecs Hardware Accelerated VideoDecoder (Primary, Zero Buffer)
 * 2. MediaSource Sliding-Window Buffer Purging (Fallback, Zero Memory Leak)
 * 3. Video-Only Strict Isolation (Guards against Audio IPC crashes during Drive)
 * 4. Interactive Test Pattern & Touch Visualizer
 */

class VideoPlayer {
    constructor(options = {}) {
        this.canvas = document.getElementById(options.canvasId || 'video-canvas');
        this.ctx = this.canvas ? this.canvas.getContext('2d', { alpha: false, desynchronized: true }) : null;
        this.videoEl = document.getElementById(options.videoId || 'video-player');
        
        this.mode = 'unknown';
        this.decoder = null;
        this.mediaSource = null;
        this.sourceBuffer = null;
        this.mseQueue = [];
        
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

        this.hasReceivedRealVideo = false;
        this.hasReceivedKeyframe = false;
        
        // Touch ripples for interactive test visualizer
        this.touchRipples = [];
        this.mockCarX = 0.5;
        this.mockCarY = 0.5;
        this.mockAnimAngle = 0;

        this.initEngine();
        this.startTelemetryLoop();
        this.startMockRenderer();
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
        this.mode = 'webcodecs';
        if (this.canvas) this.canvas.style.display = 'block';
        if (this.videoEl) this.videoEl.style.display = 'none';

        try {
            this.decoder = new VideoDecoder({
                output: (frame) => this.handleWebCodecsFrame(frame),
                error: (e) => {
                    this.stats.droppedFrames++;
                    if (this.stats.droppedFrames > 15 && this.mode === 'webcodecs') {
                        this.initMSE();
                    }
                }
            });

            this.decoder.configure({
                codec: 'avc1.42E01E', // Baseline Profile Level 3.1
                optimizeForLatency: true,
                hardwareAcceleration: 'prefer-hardware'
            });
        } catch (err) {
            this.initMSE();
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

        // CRITICAL MEMORY GUARD: Close VideoFrame immediately
        frame.close();
    }

    /* =========================================================================
       2. MEDIASOURCE (MSE) ENGINE (Sliding-Window Buffer Purging)
       ========================================================================= */
    initMSE() {
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
            try {
                const mime = 'video/mp4; codecs="avc1.42E01E"';
                if (MediaSource.isTypeSupported(mime)) {
                    this.sourceBuffer = this.mediaSource.addSourceBuffer(mime);
                    this.sourceBuffer.mode = 'sequence';

                    this.sourceBuffer.addEventListener('updateend', () => {
                        this.processMseQueue();
                        this.purgePastBuffer();
                        this.catchupLiveEdge();
                    });

                    this.processMseQueue();
                }
            } catch (_) {}
        });
    }

    processMseQueue() {
        if (!this.sourceBuffer || this.sourceBuffer.updating || this.mseQueue.length === 0) return;
        const chunk = this.mseQueue.shift();
        try {
            this.sourceBuffer.appendBuffer(chunk);
            this.hasReceivedRealVideo = true;
            this.stats.decodedFrames++;
            this.stats.frameCount++;
        } catch (_) {}
    }

    purgePastBuffer() {
        if (!this.sourceBuffer || this.sourceBuffer.updating || !this.videoEl) return;
        try {
            const currentTime = this.videoEl.currentTime;
            if (currentTime > 0.8 && this.sourceBuffer.buffered.length > 0) {
                const start = this.sourceBuffer.buffered.start(0);
                const purgeUntil = currentTime - 0.3;
                if (purgeUntil > start) {
                    this.sourceBuffer.remove(start, purgeUntil);
                }
            }
        } catch (_) {}
    }

    catchupLiveEdge() {
        if (!this.videoEl || this.videoEl.buffered.length === 0) return;
        const bufferEnd = this.videoEl.buffered.end(0);
        const drift = bufferEnd - this.videoEl.currentTime;
        this.stats.bufferMs = Math.round(drift * 1000);

        if (drift > 0.20) {
            this.videoEl.currentTime = bufferEnd - 0.02;
            this.videoEl.playbackRate = 1.0;
        } else if (drift > 0.06) {
            this.videoEl.playbackRate = 1.06;
        } else {
            this.videoEl.playbackRate = 1.0;
        }
    }

    /* =========================================================================
       3. FEED CHUNKS FROM WEBSOCKET
       ========================================================================= */
    feedChunk(dataBuffer, serverTimestamp = null) {
        this.stats.bytesReceived += dataBuffer.byteLength;

        if (this.mode === 'webcodecs') {
            if (!this.decoder || this.decoder.state === 'closed') return;
            const u8 = new Uint8Array(dataBuffer);
            const isKey = this.checkIfKeyframe(u8);

            if (isKey || this.hasReceivedKeyframe) {
                this.hasReceivedKeyframe = true;
                try {
                    const chunk = new EncodedVideoChunk({
                        type: isKey ? 'key' : 'delta',
                        timestamp: (serverTimestamp || performance.now()) * 1000,
                        data: dataBuffer
                    });
                    this.decoder.decode(chunk);
                } catch (_) {
                    this.stats.droppedFrames++;
                }
            }
        } else if (this.mode === 'mse') {
            this.mseQueue.push(dataBuffer);
            this.processMseQueue();
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

    /* =========================================================================
       4. INTERACTIVE TEST DASHBOARD & TOUCH VISUALIZER
       ========================================================================= */
    addTouchPoint(normX, normY) {
        this.touchRipples.push({
            x: normX,
            y: normY,
            radius: 5,
            maxRadius: 40,
            opacity: 1.0
        });
    }

    startMockRenderer() {
        const renderLoop = () => {
            if (!this.hasReceivedRealVideo && this.ctx && this.canvas) {
                this.renderMockAndroidAutoUI();
            }
            requestAnimationFrame(renderLoop);
        };
        requestAnimationFrame(renderLoop);
    }

    renderMockAndroidAutoUI() {
        const ctx = this.ctx;
        const w = this.canvas.width;
        const h = this.canvas.height;

        this.mockAnimAngle += 0.02;

        // Background / Map simulation
        ctx.fillStyle = '#14171d';
        ctx.fillRect(0, 0, w, h);

        // Map grid lines
        ctx.strokeStyle = '#1e2430';
        ctx.lineWidth = 2;
        const gridSize = 60;
        for (let x = 0; x < w; x += gridSize) {
            ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, h); ctx.stroke();
        }
        for (let y = 0; y < h; y += gridSize) {
            ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke();
        }

        // Simulated Roads
        ctx.strokeStyle = '#2d3748';
        ctx.lineWidth = 14;
        ctx.beginPath();
        ctx.moveTo(100, h - 80);
        ctx.bezierCurveTo(w * 0.4, h * 0.7, w * 0.3, h * 0.3, w - 100, 100);
        ctx.stroke();

        // Route highlight
        ctx.strokeStyle = '#3b82f6';
        ctx.lineWidth = 8;
        ctx.beginPath();
        ctx.moveTo(100, h - 80);
        ctx.bezierCurveTo(w * 0.4, h * 0.7, w * 0.3, h * 0.3, w - 100, 100);
        ctx.stroke();

        // Animated GPS Vehicle Marker
        const carX = w * 0.45 + Math.sin(this.mockAnimAngle) * 60;
        const carY = h * 0.45 + Math.cos(this.mockAnimAngle) * 40;
        
        ctx.fillStyle = '#e82127';
        ctx.shadowColor = '#e82127';
        ctx.shadowBlur = 15;
        ctx.beginPath();
        ctx.arc(carX, carY, 12, 0, Math.PI * 2);
        ctx.fill();
        ctx.shadowBlur = 0;

        // Top-left Navigation Card (Google Maps Mock)
        ctx.fillStyle = 'rgba(24, 27, 36, 0.92)';
        ctx.strokeStyle = 'rgba(255,255,255,0.1)';
        ctx.lineWidth = 1;
        this.roundRect(ctx, 24, 24, 340, 120, 14, true, true);

        ctx.fillStyle = '#10b981';
        ctx.font = 'bold 28px sans-serif';
        ctx.fillText('↰ 450 m', 44, 68);

        ctx.fillStyle = '#f0f2f5';
        ctx.font = 'bold 16px sans-serif';
        ctx.fillText('Via Roma / Corso Italia', 44, 98);

        ctx.fillStyle = '#9095a0';
        ctx.font = '13px sans-serif';
        ctx.fillText('Arrivo 17:15 · 18 min · 14.2 km', 44, 124);

        // Media Player Card (Spotify Mock)
        this.roundRect(ctx, w - 360, 24, 336, 120, 14, true, true);
        ctx.fillStyle = '#1db954';
        ctx.font = 'bold 14px sans-serif';
        ctx.fillText('🟢 Spotify · Android Auto', w - 340, 52);

        ctx.fillStyle = '#f0f2f5';
        ctx.font = 'bold 16px sans-serif';
        ctx.fillText('Tesla Low-Latency Streamer', w - 340, 80);

        ctx.fillStyle = '#9095a0';
        ctx.font = '13px sans-serif';
        ctx.fillText('Model 3 2019 (Intel Atom MCU2)', w - 340, 104);

        ctx.fillStyle = '#f0f2f5';
        ctx.font = '20px sans-serif';
        ctx.fillText('⏮   ⏸   ⏭', w - 340, 130);

        // Bottom Android Auto Taskbar
        ctx.fillStyle = '#0f1015';
        ctx.fillRect(0, h - 64, w, 64);
        ctx.strokeStyle = 'rgba(255,255,255,0.08)';
        ctx.strokeRect(0, h - 64, w, 1);

        // Taskbar Icons
        ctx.fillStyle = '#f0f2f5';
        ctx.font = '22px sans-serif';
        ctx.fillText('⊞', 24, h - 24); // App Grid
        ctx.fillText('🗺️', 72, h - 24); // Maps
        ctx.fillText('🎵', 120, h - 24); // Spotify
        ctx.fillText('📞', 168, h - 24); // Phone

        // Live Clock & Status in bottom right
        const d = new Date();
        const timeStr = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
        ctx.font = 'bold 16px monospace';
        ctx.fillStyle = '#f0f2f5';
        ctx.fillText(`${timeStr}  📶 5G  🔋 94%`, w - 210, h - 26);

        // Render Touch Ripples
        for (let i = this.touchRipples.length - 1; i >= 0; i--) {
            const r = this.touchRipples[i];
            ctx.strokeStyle = `rgba(59, 130, 246, ${r.opacity})`;
            ctx.fillStyle = `rgba(59, 130, 246, ${r.opacity * 0.25})`;
            ctx.lineWidth = 3;
            ctx.beginPath();
            ctx.arc(r.x * w, r.y * h, r.radius, 0, Math.PI * 2);
            ctx.fill();
            ctx.stroke();

            r.radius += 1.8;
            r.opacity -= 0.04;
            if (r.opacity <= 0 || r.radius >= r.maxRadius) {
                this.touchRipples.splice(i, 1);
            }
        }
    }

    roundRect(ctx, x, y, width, height, radius, fill, stroke) {
        ctx.beginPath();
        ctx.moveTo(x + radius, y);
        ctx.lineTo(x + width - radius, y);
        ctx.quadraticCurveTo(x + width, y, x + width, y + radius);
        ctx.lineTo(x + width, y + height - radius);
        ctx.quadraticCurveTo(x + width, y + height, x + width - radius, y + height);
        ctx.lineTo(x + radius, y + height);
        ctx.quadraticCurveTo(x, y + height, x, y + height - radius);
        ctx.lineTo(x, y + radius);
        ctx.quadraticCurveTo(x, y, x + radius, y);
        ctx.closePath();
        if (fill) ctx.fill();
        if (stroke) ctx.stroke();
    }

    /* =========================================================================
       5. TELEMETRY & FPS CALCULATOR
       ========================================================================= */
    startTelemetryLoop() {
        setInterval(() => {
            const now = performance.now();
            const elapsedFps = (now - this.stats.lastFpsUpdate) / 1000;
            if (elapsedFps >= 1.0) {
                this.stats.fps = this.hasReceivedRealVideo ? Math.round(this.stats.frameCount / elapsedFps) : 30;
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

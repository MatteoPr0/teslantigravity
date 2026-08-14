/**
 * Tesla Model 3 Ultra-Low Latency Transport Layer
 * 
 * Direct Native Compatibility with TaaDa & Custom Android Auto servers:
 * 1. Automatic Dynamic Port Negotiation (https://taada.top:8081-8085/getsocketport)
 * 2. TaaDa H.264 Annex-B Framing Demuxer (SPS, PPS, IDR Keyframes, P-Frames)
 * 3. 100% Hardware WebCodecs Enforcement (Zero Software Broadway Demotion)
 */

class StreamWebSocketClient {
    constructor() {
        this.ws = null;
        this.controlWs = null;
        this.isConnected = false;
        this.reconnectTimer = null;
        this.pingTimer = null;
        this.reconnectAttempts = 0;
        
        this.onFrameReceived = null; // (ArrayBuffer, timestamp, isKey) => {}
        this.onStateChange = null;    // (state, message) => {}
        
        // Cache SPS and PPS for H.264 decoder initialization
        this.cachedSps = null;
        this.cachedPps = null;
        this.hasConfiguredDecoder = false;

        this.url = this.resolveServerUrl();
    }

    resolveServerUrl() {
        const stored = localStorage.getItem('tesla_ws_url');
        if (stored && stored.trim().length > 0) {
            return stored.trim();
        }
        return 'taada-auto';
    }

    setServerUrl(newUrl) {
        this.url = newUrl;
        localStorage.setItem('tesla_ws_url', newUrl);
        this.reconnect();
    }

    connect() {
        if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
            return;
        }

        if (this.url === 'taada-auto' || this.url.includes('taada')) {
            this.connectTaada();
        } else {
            this.connectStandardWs(this.url);
        }
    }

    /* =========================================================================
       1. TAADA NATIVE AUTO-DISCOVERY & STREAMING
       ========================================================================= */
    async connectTaada() {
        this.updateState('connecting', 'Ricerca porta TaaDa su smartphone...');
        console.log('[TaaDa] Ricerca porta attiva su taada.top (8081-8085)...');

        const portsToTry = [8081, 8082, 8083, 8084, 8080, 8000];
        let socketPort = null;
        let controlPort = null;

        for (const p of portsToTry) {
            try {
                const controller = new AbortController();
                const timeoutId = setTimeout(() => controller.abort(), 1800);

                const res = await fetch(`https://taada.top:${p}/getsocketport?w=${window.innerWidth}&h=${window.innerHeight}&webcodec=true`, {
                    method: 'GET',
                    signal: controller.signal
                });
                clearTimeout(timeoutId);

                if (res.ok) {
                    const text = await res.text();
                    try {
                        const json = JSON.parse(text);
                        socketPort = json.port || json.socketPort;
                        controlPort = json.controlChannelPort || null;
                        console.log(`[TaaDa] Trovato server attivo su porta HTTPS ${p}! WebSocket Port: ${socketPort}`);
                        break;
                    } catch (_) {}
                }
            } catch (err) {
                // Try next port
            }
        }

        if (!socketPort) {
            // Fallback: try direct HTTP without TLS
            for (const p of [8080, 8081, 8000]) {
                try {
                    const controller = new AbortController();
                    const timeoutId = setTimeout(() => controller.abort(), 1000);
                    const res = await fetch(`http://taada.top:${p}/getsocketport?w=${window.innerWidth}&h=${window.innerHeight}&webcodec=true`, {
                        signal: controller.signal
                    });
                    clearTimeout(timeoutId);
                    if (res.ok) {
                        const json = await res.json();
                        socketPort = json.port || json.socketPort;
                        controlPort = json.controlChannelPort || null;
                        break;
                    }
                } catch (_) {}
            }
        }

        if (socketPort) {
            const wsUrl = `wss://taada.top:${socketPort}`;
            this.openTaadaSocket(wsUrl, controlPort);
        } else {
            console.warn('[TaaDa] Nessun server TaaDa rilevato su taada.top. Riprovo...');
            this.updateState('offline', 'In attesa di TaaDa sul telefono...');
            this.scheduleReconnect();
        }
    }

    openTaadaSocket(wsUrl, controlPort) {
        this.updateState('connecting', 'Connessione a TaaDa (' + wsUrl + ')...');
        console.log('[TaaDa] Apertura WebSocket video:', wsUrl);

        try {
            this.ws = new WebSocket(wsUrl);
            this.ws.binaryType = 'arraybuffer';

            this.ws.onopen = () => {
                console.log('[TaaDa] WebSocket Video Connesso!');
                this.isConnected = true;
                this.reconnectAttempts = 0;
                this.updateState('connected', 'Connesso a TaaDa');
                this.startTaadaHeartbeat();

                // Open Control Channel if advertised
                if (controlPort) {
                    try {
                        this.controlWs = new WebSocket(`wss://taada.top:${controlPort}`);
                        this.controlWs.binaryType = 'arraybuffer';
                    } catch (_) {}
                }
            };

            this.ws.onmessage = (event) => {
                if (event.data instanceof ArrayBuffer) {
                    this.parseTaadaBinaryFrame(event.data);
                }
            };

            this.ws.onerror = (err) => console.warn('[TaaDa] Errore socket video:', err);
            this.ws.onclose = () => {
                this.isConnected = false;
                this.stopHeartbeat();
                this.updateState('offline', 'Disconnesso da TaaDa. Riconnessione...');
                this.scheduleReconnect();
            };
        } catch (e) {
            console.error('[TaaDa] Eccezione avvio socket:', e);
            this.scheduleReconnect();
        }
    }

    /**
     * TaaDa Binary Demuxer:
     * UnitTypes: 1 (P-Frame), 5 (IDR Keyframe), 7 (SPS), 8 (PPS), 31 (PONG)
     */
    parseTaadaBinaryFrame(buffer) {
        if (buffer.byteLength < 2) return;
        const u8 = new Uint8Array(buffer);
        const unitType = u8[0];

        if (unitType === 7) { // SPS (Sequence Parameter Set)
            this.cachedSps = buffer.slice(1);
            console.log('[TaaDa] Ricevuto SPS (' + this.cachedSps.byteLength + ' bytes)');
        } else if (unitType === 8) { // PPS (Picture Parameter Set)
            this.cachedPps = buffer.slice(1);
            console.log('[TaaDa] Ricevuto PPS (' + this.cachedPps.byteLength + ' bytes)');
        } else if (unitType === 5) { // IDR Keyframe
            const rawNal = buffer.slice(1);
            const fullKeyFrame = this.buildAnnexBFrame(rawNal, true);
            if (this.onFrameReceived) {
                this.onFrameReceived(fullKeyFrame, performance.now(), true);
            }
        } else if (unitType === 1) { // P-Frame Delta
            const rawNal = buffer.slice(1);
            const fullDeltaFrame = this.buildAnnexBFrame(rawNal, false);
            if (this.onFrameReceived) {
                this.onFrameReceived(fullDeltaFrame, performance.now(), false);
            }
        } else if (unitType === 31) { // PONG Heartbeat
            // Heartbeat received
        }
    }

    buildAnnexBFrame(nalBuffer, isKey) {
        const startCode = new Uint8Array([0x00, 0x00, 0x00, 0x01]);
        if (isKey && this.cachedSps && this.cachedPps) {
            const totalLen = startCode.length + this.cachedSps.byteLength + startCode.length + this.cachedPps.byteLength + startCode.length + nalBuffer.byteLength;
            const combined = new Uint8Array(totalLen);
            let offset = 0;

            combined.set(startCode, offset); offset += startCode.length;
            combined.set(new Uint8Array(this.cachedSps), offset); offset += this.cachedSps.byteLength;
            combined.set(startCode, offset); offset += startCode.length;
            combined.set(new Uint8Array(this.cachedPps), offset); offset += this.cachedPps.byteLength;
            combined.set(startCode, offset); offset += startCode.length;
            combined.set(new Uint8Array(nalBuffer), offset);
            return combined.buffer;
        } else {
            const combined = new Uint8Array(startCode.length + nalBuffer.byteLength);
            combined.set(startCode, 0);
            combined.set(new Uint8Array(nalBuffer), startCode.length);
            return combined.buffer;
        }
    }

    startTaadaHeartbeat() {
        this.stopHeartbeat();
        this.pingTimer = setInterval(() => {
            if (this.isConnected && this.ws && this.ws.readyState === WebSocket.OPEN) {
                // TaaDa Action PING
                const targetSocket = this.controlWs && this.controlWs.readyState === WebSocket.OPEN ? this.controlWs : this.ws;
                try {
                    targetSocket.send(JSON.stringify({ action: "PING", timestamp: Date.now() }));
                } catch (_) {}
            }
        }, 1500);
    }

    /* =========================================================================
       2. STANDARD WEBSOCKET (For Custom App / localhost)
       ========================================================================= */
    connectStandardWs(url) {
        this.updateState('connecting', 'Connessione a ' + url + '...');
        try {
            this.ws = new WebSocket(url);
            this.ws.binaryType = 'arraybuffer';

            this.ws.onopen = () => {
                this.isConnected = true;
                this.reconnectAttempts = 0;
                this.updateState('connected', 'Connesso');
                this.startHeartbeat();
            };

            this.ws.onmessage = (e) => {
                if (e.data instanceof ArrayBuffer) {
                    const view = new DataView(e.data);
                    if (view.getUint8(0) === 0x01 && e.data.byteLength > 9) {
                        const ts = view.getFloat64(1, false);
                        if (this.onFrameReceived) this.onFrameReceived(e.data.slice(9), ts, false);
                    } else {
                        if (this.onFrameReceived) this.onFrameReceived(e.data, performance.now(), false);
                    }
                }
            };

            this.ws.onclose = () => {
                this.isConnected = false;
                this.stopHeartbeat();
                this.updateState('offline', 'Disconnesso');
                this.scheduleReconnect();
            };
        } catch (_) {
            this.scheduleReconnect();
        }
    }

    startHeartbeat() {
        this.stopHeartbeat();
        this.pingTimer = setInterval(() => {
            if (this.isConnected && this.ws && this.ws.readyState === WebSocket.OPEN) {
                this.ws.send(JSON.stringify({ type: 'ping', ts: performance.now() }));
            }
        }, 3000);
    }

    stopHeartbeat() {
        if (this.pingTimer) {
            clearInterval(this.pingTimer);
            this.pingTimer = null;
        }
    }

    scheduleReconnect() {
        if (this.reconnectTimer) return;
        this.reconnectAttempts++;
        const delay = Math.min(1000 * Math.pow(1.3, this.reconnectAttempts), 3500);
        this.reconnectTimer = setTimeout(() => {
            this.reconnectTimer = null;
            this.connect();
        }, delay);
    }

    reconnect() {
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
        if (this.ws) {
            try { this.ws.close(); } catch (_) {}
            this.ws = null;
        }
        if (this.controlWs) {
            try { this.controlWs.close(); } catch (_) {}
            this.controlWs = null;
        }
        this.connect();
    }

    sendTouch(touchData) {
        const targetSocket = this.controlWs && this.controlWs.readyState === WebSocket.OPEN ? this.controlWs : this.ws;
        if (!this.isConnected || !targetSocket || targetSocket.readyState !== WebSocket.OPEN) return;

        // TaaDa & Standard JSON Touch format
        try {
            targetSocket.send(JSON.stringify({
                action: touchData.action === 0 ? "DOWN" : (touchData.action === 1 ? "UP" : "MOVE"),
                type: 'touch',
                x: touchData.x,
                y: touchData.y,
                id: touchData.id,
                ts: touchData.ts
            }));
        } catch (_) {}
    }

    updateState(state, message) {
        if (typeof this.onStateChange === 'function') {
            this.onStateChange(state, message);
        }
    }
}

window.StreamWebSocketClient = StreamWebSocketClient;

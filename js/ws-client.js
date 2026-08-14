/**
 * Tesla Model 3 Ultra-Low Latency WebSocket Transport Layer
 * 
 * Handles binary streaming of H.264 video chunks, touch uplink,
 * heartbeat keepalives and seamless auto-reconnect.
 */

class StreamWebSocketClient {
    constructor() {
        this.ws = null;
        this.isConnected = false;
        this.reconnectTimer = null;
        this.pingTimer = null;
        this.reconnectAttempts = 0;
        
        this.onFrameReceived = null;  // (ArrayBuffer, timestamp) => {}
        this.onStateChange = null;     // (state, message) => {}
        
        // Target WebSocket URL
        this.url = this.resolveServerUrl();
    }

    resolveServerUrl() {
        const stored = localStorage.getItem('tesla_ws_url');
        if (stored && stored.trim().length > 0) {
            return stored.trim();
        }

        const host = window.location.hostname;
        const port = window.location.port || '8080';
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';

        // Local testing on PC / Mac
        if (host === 'localhost' || host === '127.0.0.1') {
            return `ws://localhost:${port}/stream`;
        }
        
        // Local network IP (e.g. 192.168.x.x)
        if (host && !host.includes('github.io')) {
            return `${protocol}//${host}:${port}/stream`;
        }

        // Default Android Hotspot IP for Tesla in-car browser
        return 'ws://192.168.43.1:8080/stream';
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

        this.updateState('connecting', 'Connessione a ' + this.url + '...');
        console.log('[WS] Connessione in corso a:', this.url);

        try {
            this.ws = new WebSocket(this.url);
            this.ws.binaryType = 'arraybuffer';

            this.ws.onopen = () => this.handleOpen();
            this.ws.onmessage = (e) => this.handleMessage(e);
            this.ws.onerror = (e) => this.handleError(e);
            this.ws.onclose = (e) => this.handleClose(e);
        } catch (err) {
            console.error('[WS] Errore avvio WebSocket:', err);
            this.scheduleReconnect();
        }
    }

    handleOpen() {
        console.log('[WS] Connesso con successo al server Android Auto!');
        this.isConnected = true;
        this.reconnectAttempts = 0;
        this.updateState('connected', 'Connesso');
        
        this.startHeartbeat();
        
        // Send initial handshake configuration
        this.sendMessage({
            type: 'handshake',
            client: 'Tesla-Model3-MCU2',
            screenWidth: window.innerWidth,
            screenHeight: window.innerHeight,
            dpr: window.devicePixelRatio || 1
        });
    }

    handleMessage(event) {
        if (event.data instanceof ArrayBuffer) {
            this.parseBinaryMessage(event.data);
        } else if (typeof event.data === 'string') {
            try {
                const msg = JSON.parse(event.data);
                this.handleJsonMessage(msg);
            } catch (err) {
                console.warn('[WS] Messaggio di testo non JSON:', event.data);
            }
        }
    }

    parseBinaryMessage(buffer) {
        if (buffer.byteLength < 1) return;

        const view = new DataView(buffer);
        const header = view.getUint8(0);

        if (header === 0x01) {
            // Type 0x01: Video Frame with 64-bit microsecond timestamp
            let timestampMs = performance.now();
            if (buffer.byteLength > 9) {
                try {
                    timestampMs = view.getFloat64(1, false); // Big-endian float64
                } catch (_) {}
            }
            const videoPayload = buffer.slice(9);
            if (typeof this.onFrameReceived === 'function') {
                this.onFrameReceived(videoPayload, timestampMs);
            }
        } else {
            if (typeof this.onFrameReceived === 'function') {
                this.onFrameReceived(buffer, performance.now());
            }
        }
    }

    handleJsonMessage(msg) {
        if (msg.type === 'pong') {
            // Heartbeat ACK
        } else if (msg.type === 'config') {
            console.log('[WS] Ricevuta configurazione server:', msg);
        }
    }

    handleError(err) {
        console.warn('[WS] Errore connessione:', err);
    }

    handleClose(event) {
        console.warn('[WS] Connessione chiusa. Code:', event.code);
        this.isConnected = false;
        this.stopHeartbeat();
        this.updateState('offline', 'Disconnesso. Riconnessione in corso...');
        this.scheduleReconnect();
    }

    scheduleReconnect() {
        if (this.reconnectTimer) return;
        
        this.reconnectAttempts++;
        const delay = Math.min(1000 * Math.pow(1.3, this.reconnectAttempts), 4000);
        
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
        this.connect();
    }

    startHeartbeat() {
        this.stopHeartbeat();
        this.pingTimer = setInterval(() => {
            if (this.isConnected) {
                this.sendMessage({ type: 'ping', ts: performance.now() });
            }
        }, 3000);
    }

    stopHeartbeat() {
        if (this.pingTimer) {
            clearInterval(this.pingTimer);
            this.pingTimer = null;
        }
    }

    sendTouch(touchData) {
        if (!this.isConnected || !this.ws || this.ws.readyState !== WebSocket.OPEN) return;

        this.sendMessage({
            type: 'touch',
            action: touchData.action, // 0: Down, 1: Up, 2: Move, 3: Cancel
            x: touchData.x,
            y: touchData.y,
            id: touchData.id,
            ts: touchData.ts
        });
    }

    sendMessage(obj) {
        if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
        try {
            this.ws.send(JSON.stringify(obj));
        } catch (e) {
            console.error('[WS] Errore invio messaggio:', e);
        }
    }

    updateState(state, message) {
        if (typeof this.onStateChange === 'function') {
            this.onStateChange(state, message);
        }
    }
}

window.StreamWebSocketClient = StreamWebSocketClient;

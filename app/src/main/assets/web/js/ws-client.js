/**
 * Tesla Model 3 Ultra-Low Latency Transport Layer
 * 
 * Auto-Detects Connection Mode:
 * 1. Direct Local Server (ws://HOST:8080/stream) when served by our app
 * 2. Remote / GitHub Pages mode with IP configurator
 */

class StreamWebSocketClient {
    constructor() {
        this.ws = null;
        this.isConnected = false;
        this.reconnectTimer = null;
        this.pingTimer = null;
        this.reconnectAttempts = 0;
        
        this.onFrameReceived = null; // (ArrayBuffer, timestamp, isKey) => {}
        this.onStateChange = null;    // (state, message) => {}

        this.url = this.resolveServerUrl();
    }

    resolveServerUrl() {
        const stored = localStorage.getItem('tesla_ws_url');
        if (stored && stored.trim().length > 0) {
            return stored.trim();
        }

        // If served from local phone server (e.g. http://192.168.43.1:8080)
        if (window.location.host && !window.location.host.includes('github.io') && !window.location.host.includes('localhost:5500')) {
            const proto = window.location.protocol === 'https:' ? 'wss://' : 'ws://';
            return `${proto}${window.location.host}/stream`;
        }

        // Default for GitHub Pages: Hotspot default gateway
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
        console.log('[WS] Apertura connessione verso:', this.url);

        try {
            this.ws = new WebSocket(this.url);
            this.ws.binaryType = 'arraybuffer';

            this.ws.onopen = () => {
                console.log('[WS] Connesso al server Tesla!');
                this.isConnected = true;
                this.reconnectAttempts = 0;
                this.updateState('connected', 'Connesso');
                this.startHeartbeat();

                // Send Handshake
                try {
                    this.ws.send(JSON.stringify({
                        type: 'handshake',
                        client: 'Tesla Model 3 MCU2',
                        screenWidth: window.innerWidth,
                        screenHeight: window.innerHeight
                    }));
                } catch (_) {}
            };

            this.ws.onmessage = (e) => {
                if (e.data instanceof ArrayBuffer) {
                    const view = new DataView(e.data);
                    if (view.getUint8(0) === 0x01 && e.data.byteLength > 9) {
                        const ts = view.getFloat64(1, false);
                        const nalData = e.data.slice(9);
                        if (this.onFrameReceived) {
                            this.onFrameReceived(nalData, ts, false);
                        }
                    } else {
                        if (this.onFrameReceived) {
                            this.onFrameReceived(e.data, performance.now(), false);
                        }
                    }
                }
            };

            this.ws.onerror = (err) => {
                console.warn('[WS] Errore socket:', err);
            };

            this.ws.onclose = () => {
                this.isConnected = false;
                this.stopHeartbeat();
                this.updateState('offline', 'Disconnesso. Riconnessione...');
                this.scheduleReconnect();
            };
        } catch (e) {
            console.error('[WS] Eccezione avvio socket:', e);
            this.scheduleReconnect();
        }
    }

    startHeartbeat() {
        this.stopHeartbeat();
        this.pingTimer = setInterval(() => {
            if (this.isConnected && this.ws && this.ws.readyState === WebSocket.OPEN) {
                try {
                    this.ws.send(JSON.stringify({ type: 'ping', ts: performance.now() }));
                } catch (_) {}
            }
        }, 2500);
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
        const delay = Math.min(1000 * Math.pow(1.2, this.reconnectAttempts), 3000);
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

    sendTouch(touchData) {
        if (!this.isConnected || !this.ws || this.ws.readyState !== WebSocket.OPEN) return;

        try {
            this.ws.send(JSON.stringify({
                type: 'touch',
                action: touchData.action,
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

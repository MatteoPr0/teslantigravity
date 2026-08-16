/**
 * Tesla Model 3 Live Telemetry HUD & Controls Manager
 */

class TelemetryHUD {
    constructor() {
        // UI Elements
        this.statusOverlay = document.getElementById('status-overlay');
        this.statusTitle = document.getElementById('status-title');
        this.statusDesc = document.getElementById('status-desc');
        this.dotIndicator = document.getElementById('dot-indicator');
        this.txtConnStatus = document.getElementById('txt-conn-status');
        
        this.valEngine = document.getElementById('val-engine');
        this.valFps = document.getElementById('val-fps');
        this.valLatency = document.getElementById('val-latency');
        this.valBuffer = document.getElementById('val-buffer');
        
        // Modals
        this.diagModal = document.getElementById('diagnostics-modal');
        this.settingsModal = document.getElementById('settings-modal');
        
        // Detailed stats elements
        this.diagBitrate = document.getElementById('diag-bitrate');
        this.diagDecoded = document.getElementById('diag-frames-decoded');
        this.diagDropped = document.getElementById('diag-frames-dropped');

        // Buttons
        this.btnManualConnect = document.getElementById('btn-manual-connect');
        this.btnOpenSettings = document.getElementById('btn-open-settings');
        this.btnToggleHud = document.getElementById('btn-toggle-hud');
        this.btnSettings = document.getElementById('btn-settings');
        this.btnFullscreen = document.getElementById('btn-fullscreen');
        this.btnCloseDiag = document.getElementById('btn-close-diag');
        this.btnCloseSettings = document.getElementById('btn-close-settings');
        this.btnSaveSettings = document.getElementById('btn-save-settings');

        // Settings Inputs
        this.inputWsUrl = document.getElementById('input-ws-url');
        this.selectEngine = document.getElementById('select-engine');
        this.selectAspect = document.getElementById('select-aspect');

        this.bindEvents();
        this.loadSettings();
        this.startRefreshLoop();
    }

    bindEvents() {
        if (this.btnManualConnect) {
            this.btnManualConnect.addEventListener('click', () => {
                if (window.App && window.App.ws) {
                    window.App.ws.reconnect();
                }
            });
        }

        if (this.btnOpenSettings) {
            this.btnOpenSettings.addEventListener('click', () => {
                this.loadSettings();
                this.toggleModal(this.settingsModal);
            });
        }

        if (this.btnToggleHud) {
            this.btnToggleHud.addEventListener('click', () => this.toggleModal(this.diagModal));
        }

        if (this.btnSettings) {
            this.btnSettings.addEventListener('click', () => {
                this.loadSettings();
                this.toggleModal(this.settingsModal);
            });
        }

        if (this.btnCloseDiag) {
            this.btnCloseDiag.addEventListener('click', () => this.closeModal(this.diagModal));
        }

        if (this.btnCloseSettings) {
            this.btnCloseSettings.addEventListener('click', () => this.closeModal(this.settingsModal));
        }

        if (this.btnSaveSettings) {
            this.btnSaveSettings.addEventListener('click', () => this.saveSettings());
        }

        if (this.btnFullscreen) {
            this.btnFullscreen.addEventListener('click', () => this.toggleFullscreen());
        }

        // Close modal on background click
        [this.diagModal, this.settingsModal].forEach(modal => {
            if (modal) {
                modal.addEventListener('click', (e) => {
                    if (e.target === modal) this.closeModal(modal);
                });
            }
        });
    }

    updateConnectionState(state, message) {
        if (!this.dotIndicator || !this.txtConnStatus) return;

        this.dotIndicator.className = 'status-dot';

        if (state === 'connected') {
            this.dotIndicator.classList.add('online');
            this.txtConnStatus.textContent = 'Online';
            if (this.statusOverlay) {
                this.statusOverlay.classList.remove('overlay-visible');
                this.statusOverlay.classList.add('overlay-hidden');
            }
        } else if (state === 'connecting') {
            this.dotIndicator.classList.add('connecting');
            this.txtConnStatus.textContent = 'Connessione...';
        } else {
            this.dotIndicator.classList.add('offline');
            this.txtConnStatus.textContent = 'Offline';
            if (this.statusOverlay) {
                this.statusOverlay.classList.remove('overlay-hidden');
                this.statusOverlay.classList.add('overlay-visible');
            }
        }

        if (this.statusTitle && message) {
            this.statusTitle.textContent = message;
        }
    }

    startRefreshLoop() {
        setInterval(() => {
            if (!window.App || !window.App.player) return;
            const player = window.App.player;
            const stats = player.stats;

            // Update Engine display
            if (this.valEngine) {
                this.valEngine.textContent = player.mode === 'webcodecs' ? 'WebCodecs (HW)' : 'MSE (Sliding)';
            }

            // Update FPS
            if (this.valFps) {
                this.valFps.textContent = stats.fps;
                if (stats.fps < 20 && player.stats.decodedFrames > 10) {
                    this.valFps.style.color = 'var(--accent-amber)';
                } else {
                    this.valFps.style.color = 'var(--text-primary)';
                }
            }

            // Update Latency
            if (this.valLatency) {
                this.valLatency.textContent = stats.latencyMs > 0 ? `${stats.latencyMs} ms` : '< 40 ms';
            }

            // Update Buffer
            if (this.valBuffer) {
                this.valBuffer.textContent = player.mode === 'webcodecs' ? '0 ms' : `${stats.bufferMs} ms`;
            }

            // Update Detailed Modal Stats
            if (this.diagBitrate) this.diagBitrate.textContent = `${stats.bitrateKbps} kbps`;
            if (this.diagDecoded) this.diagDecoded.textContent = stats.decodedFrames;
            if (this.diagDropped) this.diagDropped.textContent = stats.droppedFrames;
        }, 500);
    }

    toggleModal(modal) {
        if (!modal) return;
        if (modal.classList.contains('modal-hidden')) {
            modal.classList.remove('modal-hidden');
        } else {
            modal.classList.add('modal-hidden');
        }
    }

    closeModal(modal) {
        if (modal) modal.classList.add('modal-hidden');
    }

    toggleFullscreen() {
        if (!document.fullscreenElement) {
            document.documentElement.requestFullscreen().catch(err => {
                console.warn('[HUD] Impossibile abilitare fullscreen:', err);
            });
        } else {
            if (document.exitFullscreen) {
                document.exitFullscreen();
            }
        }
    }

    loadSettings() {
        if (this.inputWsUrl && window.App && window.App.ws) {
            this.inputWsUrl.value = window.App.ws.url;
        }

        const engine = localStorage.getItem('tesla_engine_pref') || 'auto';
        if (this.selectEngine) this.selectEngine.value = engine;

        const aspect = localStorage.getItem('tesla_aspect_pref') || 'contain';
        if (this.selectAspect) {
            this.selectAspect.value = aspect;
            this.applyAspect(aspect);
        }
    }

    saveSettings() {
        if (this.inputWsUrl && this.inputWsUrl.value.trim().length > 0) {
            const newUrl = this.inputWsUrl.value.trim();
            if (window.App && window.App.ws) {
                const normalized = window.App.ws.setServerUrl(newUrl);
                this.inputWsUrl.value = normalized;
            }
        }

        if (this.selectEngine) {
            localStorage.setItem('tesla_engine_pref', this.selectEngine.value);
        }

        if (this.selectAspect) {
            localStorage.setItem('tesla_aspect_pref', this.selectAspect.value);
            this.applyAspect(this.selectAspect.value);
        }

        this.closeModal(this.settingsModal);
        alert('Impostazioni salvate! Ricarica la pagina per applicare eventuali cambi di motore.');
    }

    applyAspect(aspect) {
        const canvas = document.getElementById('video-canvas');
        const video = document.getElementById('video-player');
        [canvas, video].forEach(el => {
            if (el) {
                if (aspect === 'cover') el.style.objectFit = 'cover';
                else if (aspect === 'stretch') el.style.objectFit = 'fill';
                else el.style.objectFit = 'contain';
            }
        });
    }
}

window.TelemetryHUD = TelemetryHUD;

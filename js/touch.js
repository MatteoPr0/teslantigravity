/**
 * Tesla Model 3 High-Performance Touch & Pointer Handler
 * 
 * Captures, normalizes and transmits touchscreen events from the Tesla screen
 * directly to the Android Auto server with sub-millisecond precision.
 */

class TouchHandler {
    constructor(elementId) {
        this.surface = document.getElementById(elementId);
        this.onPointerEvent = null; // Callback assigned by App controller
        
        this.activePointers = new Map();
        this.lastMoveTime = 0;
        this.minMoveIntervalMs = 12; // Cap move frequency at ~80Hz to protect Wi-Fi uplink

        if (this.surface) {
            this.bindEvents();
        }
    }

    bindEvents() {
        const s = this.surface;

        // Pointer Events (Unified Mouse / Touch / Stylus)
        s.addEventListener('pointerdown', (e) => this.handlePointer(e, 0), { passive: false });
        s.addEventListener('pointerup', (e) => this.handlePointer(e, 1), { passive: false });
        s.addEventListener('pointermove', (e) => this.handlePointerMove(e), { passive: false });
        s.addEventListener('pointercancel', (e) => this.handlePointer(e, 3), { passive: false });

        // Extra gesture guards for Tesla Chromium
        s.addEventListener('contextmenu', (e) => e.preventDefault());
        s.addEventListener('touchstart', (e) => e.preventDefault(), { passive: false });
        s.addEventListener('touchmove', (e) => e.preventDefault(), { passive: false });
        s.addEventListener('touchend', (e) => e.preventDefault(), { passive: false });
        s.addEventListener('gesturestart', (e) => e.preventDefault());
        s.addEventListener('gesturechange', (e) => e.preventDefault());
        s.addEventListener('gestureend', (e) => e.preventDefault());
    }

    handlePointer(e, actionType) {
        e.preventDefault();
        
        const rect = this.surface.getBoundingClientRect();
        const normX = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
        const normY = Math.max(0, Math.min(1, (e.clientY - rect.top) / rect.height));

        if (actionType === 0) { // DOWN
            this.activePointers.set(e.pointerId, { x: normX, y: normY });
            try { this.surface.setPointerCapture(e.pointerId); } catch (_) {}
        } else if (actionType === 1 || actionType === 3) { // UP / CANCEL
            this.activePointers.delete(e.pointerId);
            try { this.surface.releasePointerCapture(e.pointerId); } catch (_) {}
        }

        this.emitTouch({
            action: actionType, // 0: DOWN, 1: UP, 2: MOVE, 3: CANCEL
            x: normX,
            y: normY,
            id: e.pointerId,
            ts: Date.now()
        });
    }

    handlePointerMove(e) {
        e.preventDefault();
        if (!this.activePointers.has(e.pointerId)) return;

        const now = performance.now();
        if (now - this.lastMoveTime < this.minMoveIntervalMs) return;
        this.lastMoveTime = now;

        const rect = this.surface.getBoundingClientRect();
        const normX = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
        const normY = Math.max(0, Math.min(1, (e.clientY - rect.top) / rect.height));

        this.activePointers.set(e.pointerId, { x: normX, y: normY });

        this.emitTouch({
            action: 2, // MOVE
            x: normX,
            y: normY,
            id: e.pointerId,
            ts: Date.now()
        });
    }

    emitTouch(data) {
        if (typeof this.onPointerEvent === 'function') {
            this.onPointerEvent(data);
        }
    }
}

window.TouchHandler = TouchHandler;

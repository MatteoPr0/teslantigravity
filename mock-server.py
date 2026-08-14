#!/usr/bin/env python3
"""
Tesla Android Auto Web Client - Mock Stream Server
Simulates an Android Auto H.264 stream & Touch receiver over HTTP and WebSockets.

Usage:
    python3 mock-server.py --port 8080
"""

import sys
import os
import time
import json
import struct
import math
import asyncio
import http.server
import socketserver
import threading
from urllib.parse import urlparse

# Base directory
WEB_DIR = os.path.dirname(os.path.abspath(__file__))

class SimpleHTTPHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=WEB_DIR, **kwargs)

    def end_headers(self):
        # Enable CORS and disable caching
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Cache-Control', 'no-store, no-cache, must-revalidate, max-age=0')
        self.send_header('Cross-Origin-Opener-Policy', 'same-origin')
        self.send_header('Cross-Origin-Embedder-Policy', 'require-corp')
        super().end_headers()

    def log_message(self, format, *args):
        # Quiet HTTP logging
        pass


def run_http_server(port):
    with socketserver.TCPServer(("", port), SimpleHTTPHandler) as httpd:
        print(f"[HTTP] Server statico avviato su http://0.0.0.0:{port}")
        httpd.serve_forever()


async def handle_ws(websocket, path):
    print(f"[WS] Nuovo client Tesla connesso da {websocket.remote_address}")
    
    # Track touch points for visual feedback
    touch_points = {}
    frame_count = 0
    start_time = time.time()

    async def receive_touches():
        nonlocal touch_points
        try:
            async for message in websocket:
                if isinstance(message, str):
                    try:
                        data = json.loads(message)
                        msg_type = data.get('type')
                        if msg_type == 'touch':
                            action = data.get('action')
                            pid = data.get('id', 0)
                            x = data.get('x', 0)
                            y = data.get('y', 0)
                            
                            if action == 1 or action == 3: # Up or Cancel
                                touch_points.pop(pid, None)
                            else:
                                touch_points[pid] = (x, y)
                            
                            # Print touch log
                            act_name = ["DOWN", "UP", "MOVE", "CANCEL"][action] if 0 <= action <= 3 else "UNKNOWN"
                            print(f"[TOUCH] {act_name} -> x: {x:.3f}, y: {y:.3f} (ID: {pid})")
                        elif msg_type == 'ping':
                            await websocket.send(json.dumps({'type': 'pong', 'ts': data.get('ts')}))
                    except Exception as err:
                        pass
        except Exception as e:
            print(f"[WS] Client disconnesso dal listener touch: {e}")

    # Launch touch receiver task
    touch_task = asyncio.create_task(receive_touches())

    try:
        # Stream synthetic frame updates at ~30 FPS
        target_fps = 30
        frame_interval = 1.0 / target_fps

        while True:
            t0 = time.time()
            now_ms = time.time() * 1000.0

            # Packet format: [1 byte 0x01 Header] + [8 bytes Float64 timestamp] + [Payload]
            # In mock mode, we send periodic heartbeat frames and timing sync
            header = struct.pack('>Bd', 0x01, now_ms)
            
            # Dummy NAL-like synthetic telemetry payload for testing connection throughput
            payload_size = 8192 # 8KB per frame ~ 2Mbps
            synthetic_payload = bytearray(payload_size)
            
            packet = header + synthetic_payload
            await websocket.send(packet)
            
            frame_count += 1
            elapsed = time.time() - t0
            sleep_time = max(0.001, frame_interval - elapsed)
            await asyncio.sleep(sleep_time)

    except Exception as e:
        print(f"[WS] Connessione chiusa: {e}")
    finally:
        touch_task.cancel()


async def main_ws(ws_port):
    try:
        import websockets
        print(f"[WS] Server WebSocket avviato su ws://0.0.0.0:{ws_port}/stream")
        async with websockets.serve(handle_ws, "0.0.0.0", ws_port):
            await asyncio.Future() # run forever
    except ImportError:
        print("\n[INFO] Per avviare il mock streaming WebSocket completo, installa 'websockets':")
        print("       pip3 install websockets\n")
        print("[HTTP] Il client web statico è comunque accessibile dal browser.")
        await asyncio.Future()


if __name__ == '__main__':
    port = 8080
    if len(sys.argv) > 2 and sys.argv[1] in ('--port', '-p'):
        port = int(sys.argv[2])
    elif len(sys.argv) > 1 and sys.argv[1].isdigit():
        port = int(sys.argv[1])

    # Start HTTP static file server in a background thread
    http_thread = threading.Thread(target=run_http_server, args=(port,), daemon=True)
    http_thread.start()

    print(f"\n============================================================")
    print(f" Tesla Android Auto Web Client - Dev Server Attivo")
    print(f"============================================================")
    print(f" -> Apri sul browser Tesla: http://<IP_DEL_COMPUTER>:{port}")
    print(f" -> Oppure in locale:       http://localhost:{port}")
    print(f"============================================================\n")

    try:
        asyncio.run(main_ws(port))
    except KeyboardInterrupt:
        print("\n[SHUTDOWN] Server arrestato.")
        sys.exit(0)

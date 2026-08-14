#!/usr/bin/env python3
"""
Tesla Android Auto Web Client - Pure Python Mock Stream Server
Zero-Dependency (No pip required: uses Python 3 Standard Library).
Serves HTTP static files and handles RFC 6455 WebSockets simultaneously on port 8080.
"""

import os
import sys
import time
import json
import struct
import socket
import select
import hashlib
import base64
import threading
from urllib.parse import urlparse

WEB_DIR = os.path.dirname(os.path.abspath(__file__))
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

def encode_ws_frame(payload, opcode=0x02): # 0x02: Binary, 0x01: Text
    """Encodes a WebSocket frame from server to client (unmasked)"""
    if isinstance(payload, str):
        payload = payload.encode('utf-8')
        opcode = 0x01
    
    length = len(payload)
    frame = bytearray()
    frame.append(0x80 | (opcode & 0x0F)) # FIN + Opcode

    if length <= 125:
        frame.append(length)
    elif length <= 65535:
        frame.append(126)
        frame.extend(struct.pack('>H', length))
    else:
        frame.append(127)
        frame.extend(struct.pack('>Q', length))

    frame.extend(payload)
    return bytes(frame)


def decode_ws_frame(data):
    """Decodes a client-to-server masked WebSocket frame"""
    if len(data) < 2:
        return None, None, data

    b1 = data[0]
    b2 = data[1]
    opcode = b1 & 0x0F
    is_masked = (b2 & 0x80) != 0
    payload_len = b2 & 0x7F

    offset = 2
    if payload_len == 126:
        if len(data) < 4:
            return None, None, data
        payload_len = struct.unpack('>H', data[2:4])[0]
        offset = 4
    elif payload_len == 127:
        if len(data) < 10:
            return None, None, data
        payload_len = struct.unpack('>Q', data[2:10])[0]
        offset = 10

    mask = None
    if is_masked:
        if len(data) < offset + 4:
            return None, None, data
        mask = data[offset:offset+4]
        offset += 4

    if len(data) < offset + payload_len:
        return None, None, data # Incomplete frame

    payload = bytearray(data[offset:offset+payload_len])
    if is_masked and mask:
        for i in range(len(payload)):
            payload[i] ^= mask[i % 4]

    remaining_data = data[offset+payload_len:]
    return opcode, bytes(payload), remaining_data


def handle_client(sock, addr):
    sock.setblocking(False)
    buffer = bytearray()
    is_websocket = False
    
    # 1. Read HTTP Request
    t_start = time.time()
    while time.time() - t_start < 5.0:
        try:
            r, _, _ = select.select([sock], [], [], 0.1)
            if r:
                chunk = sock.recv(4096)
                if not chunk:
                    sock.close()
                    return
                buffer.extend(chunk)
                if b'\r\n\r\n' in buffer:
                    break
        except Exception:
            break

    if not buffer:
        sock.close()
        return

    request_str = buffer.decode('latin1', errors='ignore')
    lines = request_str.split('\r\n')
    request_line = lines[0]
    parts = request_line.split(' ')
    if len(parts) < 2:
        sock.close()
        return

    method, path = parts[0], parts[1]
    parsed_path = urlparse(path).path

    headers = {}
    for line in lines[1:]:
        if ': ' in line:
            k, v = line.split(': ', 1)
            headers[k.lower()] = v

    # 2. Check if WebSocket Upgrade
    if headers.get('upgrade', '').lower() == 'websocket' and 'sec-websocket-key' in headers:
        key = headers['sec-websocket-key']
        accept_raw = hashlib.sha1((key + WS_GUID).encode('utf-8')).digest()
        accept_b64 = base64.b64encode(accept_raw).decode('utf-8')

        response = (
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept_b64}\r\n\r\n"
        )
        sock.sendall(response.encode('latin1'))
        is_websocket = True
        print(f"[WS] Tesla Client Connesso con successo da {addr[0]}:{addr[1]}")
    else:
        # 3. Serve Static File
        if parsed_path in ('/', '/index.html'):
            filepath = os.path.join(WEB_DIR, 'index.html')
            content_type = 'text/html; charset=utf-8'
        elif parsed_path == '/style.css':
            filepath = os.path.join(WEB_DIR, 'style.css')
            content_type = 'text/css; charset=utf-8'
        elif parsed_path.startswith('/js/'):
            filepath = os.path.join(WEB_DIR, parsed_path[1:])
            content_type = 'application/javascript; charset=utf-8'
        elif parsed_path == '/standalone.html':
            filepath = os.path.join(WEB_DIR, 'standalone.html')
            content_type = 'text/html; charset=utf-8'
        else:
            filepath = os.path.join(WEB_DIR, 'index.html')
            content_type = 'text/html; charset=utf-8'

        if os.path.exists(filepath) and os.path.isfile(filepath):
            with open(filepath, 'rb') as f:
                body = f.read()
            resp = (
                f"HTTP/1.1 200 OK\r\n"
                f"Content-Type: {content_type}\r\n"
                f"Content-Length: {len(body)}\r\n"
                f"Access-Control-Allow-Origin: *\r\n"
                f"Cache-Control: no-cache\r\n"
                f"Connection: close\r\n\r\n"
            ).encode('latin1') + body
            try:
                sock.sendall(resp)
            except Exception:
                pass
        else:
            sock.sendall(b"HTTP/1.1 404 Not Found\r\nContent-Length: 9\r\n\r\nNot Found")
        sock.close()
        return

    # 4. WebSocket Active Streaming & Input Loop
    ws_buffer = bytearray()
    last_frame_time = time.time()
    frame_interval = 1.0 / 30.0 # 30 FPS stream

    try:
        while True:
            r, w, _ = select.select([sock], [sock], [], 0.01)
            
            # Read incoming touch / ping frames
            if r:
                try:
                    chunk = sock.recv(4096)
                    if not chunk:
                        break
                    ws_buffer.extend(chunk)
                    while True:
                        opcode, payload, remaining = decode_ws_frame(ws_buffer)
                        if opcode is None:
                            break
                        ws_buffer = bytearray(remaining)

                        if opcode == 0x08: # Close frame
                            sock.close()
                            return
                        elif opcode == 0x09: # Ping
                            sock.sendall(encode_ws_frame(payload, opcode=0x0A))
                        elif opcode == 0x01: # Text JSON
                            try:
                                msg = json.loads(payload.decode('utf-8'))
                                if msg.get('type') == 'touch':
                                    act = ["DOWN", "UP", "MOVE", "CANCEL"][msg.get('action', 0)]
                                    print(f" [TOUCH] {act} -> X: {msg.get('x',0):.3f}, Y: {msg.get('y',0):.3f}")
                                elif msg.get('type') == 'ping':
                                    sock.sendall(encode_ws_frame(json.dumps({'type': 'pong', 'ts': msg.get('ts')}), opcode=0x01))
                            except Exception:
                                pass
                except Exception:
                    break

            # Send synthetic video frames at 30 FPS
            now = time.time()
            if now - last_frame_time >= frame_interval and w:
                last_frame_time = now
                now_ms = now * 1000.0

                # Packet: [0x01 (Header)] + [Timestamp 8 bytes float64] + [Sample NAL dummy payload]
                header = struct.pack('>Bd', 0x01, now_ms)
                dummy_nal = bytearray(4096) # 4KB payload
                packet = header + dummy_nal
                
                try:
                    sock.sendall(encode_ws_frame(packet, opcode=0x02))
                except Exception:
                    break

            time.sleep(0.005)

    except Exception as e:
        pass
    finally:
        try:
            sock.close()
        except Exception:
            pass
        print(f"[WS] Client Tesla disconnesso.")


def main(port=8080):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(('0.0.0.0', port))
    server.listen(10)

    print("\n============================================================")
    print(f"  TESLA ANDROID AUTO - SERVER DI SIMULAZIONE ATTIVO")
    print("============================================================")
    print(f"  -> Apri nel browser del Mac:  http://localhost:{port}")
    print(f"  -> Oppure sul browser Tesla:   http://<IP_MAC>:{port}")
    print("============================================================\n")

    try:
        while True:
            sock, addr = server.accept()
            t = threading.Thread(target=handle_client, args=(sock, addr), daemon=True)
            t.start()
    except KeyboardInterrupt:
        print("\n[STOP] Server arrestato.")
        server.close()


if __name__ == '__main__':
    port = 8080
    if len(sys.argv) > 1 and sys.argv[1].isdigit():
        port = int(sys.argv[1])
    main(port)

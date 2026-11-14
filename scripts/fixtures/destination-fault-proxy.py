"""Loopback-only fault injection for the isolated destination MOCK device harness."""
import http.client
import json
import socket
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

mode_file = Path(sys.argv[1])
ROOT = '/api/experimental/destination-recommendations'


class Proxy(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.forward()

    def do_POST(self):
        self.forward()

    def forward(self):
        mode = mode_file.read_text().strip()
        if mode not in {'PASS', 'GET404', 'LOST', 'CLOSE_FAIL', 'DELAY'}:
            self.send_error(503)
            return
        route = ('start' if self.path == ROOT else
                 'close' if self.path.endswith('/close') else
                 'poll' if self.path.startswith(ROOT + '/') else 'marker')
        if not (self.path == '/mock-capabilities' or self.path.startswith(ROOT)):
            self.send_error(403)
            return
        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        connection = http.client.HTTPConnection('127.0.0.1', 18085, timeout=15)
        try:
            connection.request('GET', '/mock-capabilities')
            marker = connection.getresponse()
            capabilities = json.loads(marker.read())
            if capabilities != {'destinationRecommendationsMode': 'MOCK', 'externalCallsEnabled': False}:
                self.send_error(503)
                return
            if (mode == 'GET404' and route == 'poll') or (mode == 'CLOSE_FAIL' and route == 'close'):
                status, data = (404 if mode == 'GET404' else 503), b'{"code":"MOCK_INJECTED"}'
            else:
                connection.request(self.command, self.path, body, dict(self.headers))
                response = connection.getresponse()
                status, data = response.status, response.read()
            if mode == 'DELAY' and route == 'start':
                time.sleep(10)
            print(json.dumps({'mode': mode, 'method': self.command, 'route': route, 'status': status}), flush=True)
            if mode == 'LOST' and route == 'start':
                self.close_connection = True
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                return
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        finally:
            connection.close()


ThreadingHTTPServer(('127.0.0.1', 18086), Proxy).serve_forever()

#!/usr/bin/env python3
"""Recommendation-only SSH gateway. One durable search permit; no phone/booking routes.

Start SSH localhost:18769 -> EC2 localhost:18769 first. Never logs authentication.
The upstream may use paid search: launch only for an explicitly approved single query.
"""
import argparse
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re

ROOT = '/api/experimental/destination-recommendations'

def permitted(method, path):
    if method == 'GET':
        return path == '/api/v1/status' or bool(re.fullmatch(r'/api/v1/searches/[a-f0-9-]{36}', path)) or bool(re.fullmatch(ROOT + r'/[a-f0-9-]{36}', path))
    if method == 'POST':
        return path in ('/api/v1/searches/reconcile', '/api/v1/anonymous-sessions', '/api/v1/anonymous-sessions/refresh', ROOT) or bool(re.fullmatch(ROOT + r'/views/[a-f0-9-]{36}/close', path))
    return False

def run(state, port, upstream):
    state.mkdir(parents=True, exist_ok=True)
    os.chmod(state, 0o700)
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass
        def do_GET(self):
            self.forward()
        def do_POST(self):
            self.forward()
        def forward(self):
            if not permitted(self.command, self.path):
                self.send_error(403, 'RECOMMENDATION_ONLY'); return
            size = int(self.headers.get('Content-Length', '0'))
            if size < 0 or size > 32768:
                self.send_error(413); return
            body = self.rfile.read(size) if size else None
            if self.command == 'POST' and self.path == ROOT:
                try:
                    fd = os.open(state / 'search-attempted', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                    os.close(fd)
                except FileExistsError:
                    self.send_error(409, 'SINGLE_SEARCH_ALREADY_ATTEMPTED'); return
                # Mark before upstream IO. Restart/response loss never grants another call.
                print('SEARCH_ATTEMPTED=1', flush=True)
            headers = {k: self.headers[k] for k in ('Authorization', 'Idempotency-Key', 'Content-Type') if k in self.headers}
            connection = http.client.HTTPConnection('127.0.0.1', upstream, timeout=15)
            try:
                connection.request(self.command, self.path, body, headers)
                response = connection.getresponse()
                data = response.read(262145)
                route = self.path if self.path in (ROOT, '/api/v1/status', '/api/v1/searches/reconcile') else 'AUTH_OR_RESULT'
                code = ''
                if response.status != 200:
                    try:
                        code = json.loads(data).get('code', '')
                    except (ValueError, AttributeError):
                        pass
                safe_code = code if isinstance(code, str) and re.fullmatch(r'[A-Z_]{1,80}', code) else ''
                print(f'{self.command} {route} HTTP={response.status} code={safe_code}', flush=True)
                if len(data) > 262144:
                    self.send_error(502); return
                if self.command == 'GET' and self.path.startswith(ROOT + '/') and response.status == 200:
                    value = json.loads(data)
                    if value.get('state') not in ('QUEUED', 'RUNNING'):
                        # Public restaurant evidence only; no auth response or user request saved.
                        evidence = {k: value.get(k) for k in ('state', 'source', 'result', 'validUntil')}
                        target = state / 'result.json'
                        fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                        with os.fdopen(fd, 'w') as stream:
                            json.dump(evidence, stream, ensure_ascii=False, indent=2)
                self.send_response(response.status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Cache-Control', 'no-store')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers(); self.wfile.write(data)
            except (OSError, ValueError):
                self.send_error(502, 'UPSTREAM_UNAVAILABLE')
            finally:
                connection.close()
    print('Recommendation-only gateway ready; existing search permit is never reset.', flush=True)
    ThreadingHTTPServer(('127.0.0.1', port), Handler).serve_forever()

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--state', type=Path, default=Path('work/ux56-real'))
    parser.add_argument('--port', type=int, default=18091)
    parser.add_argument('--upstream', type=int, default=18769)
    args = parser.parse_args()
    run(args.state, args.port, args.upstream)

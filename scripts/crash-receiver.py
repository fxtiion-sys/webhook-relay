import argparse
import json
import threading
import time

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


request_counts = {}
request_lock = threading.Lock()


class CrashReceiver(BaseHTTPRequestHandler):

    def do_GET(self):
        if self.path == "/health":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"OK")
        else:
            self.send_error(404)

    def do_POST(self):
        if self.path != "/webhook":
            self.send_error(404)
            return

        delivery_id = self.headers.get("Webhook-Id")

        if not delivery_id:
            self.send_error(400, "Missing Webhook-Id")
            return

        content_length = int(
            self.headers.get("Content-Length", 0)
        )

        body = self.rfile.read(content_length)

        try:
            payload = json.loads(body)
        except json.JSONDecodeError:
            self.send_error(400, "Invalid JSON")
            return

        with request_lock:
            request_count = request_counts.get(delivery_id, 0) + 1
            request_counts[delivery_id] = request_count

        print(
            json.dumps({
                "event": "webhook_received",
                "deliveryId": delivery_id,
                "requestCount": request_count,
                "payload": payload,
                "timestamp": time.time()
            }),
            flush=True
        )

        if request_count == 1:
            time.sleep(5)

        try:
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"OK")

            print(
                json.dumps({
                    "event": "response_sent",
                    "deliveryId": delivery_id,
                    "requestCount": request_count
                }),
                flush=True
            )

        except (BrokenPipeError, ConnectionResetError):
            print(
                json.dumps({
                    "event": "connection_closed",
                    "deliveryId": delivery_id,
                    "requestCount": request_count
                }),
                flush=True
            )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8092)
    args = parser.parse_args()

    server = ThreadingHTTPServer(
        ("127.0.0.1", args.port),
        CrashReceiver
    )

    print(
        f"Crash receiver listening on port {args.port}",
        flush=True
    )

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class WebhookReceiver(BaseHTTPRequestHandler):

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

        print(
            json.dumps({
                "deliveryId": delivery_id,
                "payload": payload
            }),
            flush=True
        )

        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"OK")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8091)
    args = parser.parse_args()

    server = ThreadingHTTPServer(
        ("127.0.0.1", args.port),
        WebhookReceiver
    )

    print(
        f"Receiver listening on port {args.port}",
        flush=True
    )

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
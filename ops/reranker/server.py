"""Bounded local CPU reranker. Default bind is loopback; no API key or remote inference.
Start: python server.py --model-dir models --port 8091
Model license: Apache-2.0; provenance/checksums: download_model.py.
"""
import argparse
import json
import math
import os
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_BODY = 262144
MAX_DOCUMENTS = 50
MODEL_ID = "cross-encoder/mmarco-mMiniLMv2-L12-H384-v1"


def validate(payload):
    if not isinstance(payload, dict):
        raise ValueError("Expected a JSON object")
    query, docs = payload.get("query"), payload.get("documents")
    k = payload.get("top_n", 5)
    if not isinstance(query, str) or not query.strip() or len(query) > 2000:
        raise ValueError("Query must contain 1..2000 characters")
    if not isinstance(docs, list) or not 1 <= len(docs) <= MAX_DOCUMENTS:
        raise ValueError("Expected 1..50 documents")
    if any(not isinstance(d, str) or len(d) > 1200 for d in docs):
        raise ValueError("Each document must be a string of at most 1200 characters")
    if type(k) is not int or not 1 <= k <= len(docs):
        raise ValueError("top_n must be a positive integer <= document count")
    return query, docs, k


class Engine:
    def __init__(self, directory, threads=2):
        from download_model import FILES, digest
        import onnxruntime as ort
        from tokenizers import Tokenizer
        for remote, (size, checksum) in FILES.items():
            path = directory / Path(remote).name
            if not path.is_file() or path.stat().st_size != size or digest(path) != checksum:
                raise ValueError(f"Missing or unverified model file: {path.name}; run download_model.py first")
        options = ort.SessionOptions()
        options.intra_op_num_threads = max(1, threads)
        options.inter_op_num_threads = 1
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        self.session = ort.InferenceSession(str(directory / "model_quint8_avx2.onnx"), options, providers=["CPUExecutionProvider"])
        self.tokenizer = Tokenizer.from_file(str(directory / "tokenizer.json"))
        self.tokenizer.enable_truncation(max_length=256)
        self.tokenizer.enable_padding(pad_id=1, pad_token="<pad>")
        self.gate = threading.BoundedSemaphore(1)
        self.names = {i.name for i in self.session.get_inputs()}

    def rank(self, query, documents, k):
        import numpy as np
        if not self.gate.acquire(blocking=False):
            raise BlockingIOError("Reranker busy")
        try:
            scores = []
            deadline = time.monotonic() + 5
            for offset in range(0, len(documents), 8):
                if time.monotonic() > deadline:
                    raise TimeoutError("Inference budget exceeded")
                encoded = self.tokenizer.encode_batch([(query, d) for d in documents[offset:offset+8]])
                arrays = {
                    "input_ids": np.asarray([e.ids for e in encoded], dtype=np.int64),
                    "attention_mask": np.asarray([e.attention_mask for e in encoded], dtype=np.int64),
                    "token_type_ids": np.asarray([e.type_ids for e in encoded], dtype=np.int64),
                }
                logits = self.session.run(None, {n: arrays[n] for n in self.names})[0].reshape(-1)
                scores.extend(float(x) for x in logits)
            if any(not math.isfinite(s) for s in scores) or len(scores) != len(documents):
                raise ValueError("Invalid model output")
            order = sorted(range(len(scores)), key=lambda i: (-scores[i], i))[:k]
            return {"model": MODEL_ID, "results": [
                {"index": i, "relevance_score": 1 / (1 + math.exp(-max(-80, min(80, scores[i]))))} for i in order]}
        finally:
            self.gate.release()


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 8

    def __init__(self, address, engine):
        self.engine = engine
        self.connections = threading.BoundedSemaphore(8)
        super().__init__(address, Handler)

    def process_request(self, request, address):
        request.settimeout(5)
        if not self.connections.acquire(blocking=False):
            try:
                request.sendall(b"HTTP/1.1 503 Service Unavailable\r\nRetry-After: 1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except BaseException:
            self.connections.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.connections.release()


class Handler(BaseHTTPRequestHandler):
    server_version = "LocalReranker/1.0"
    sys_version = ""

    def log_message(self, *_):
        pass  # Queries and document contents must not enter logs.

    def reply(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        if status == 503:
            self.send_header("Retry-After", "1")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, socket.timeout):
            pass

    def do_GET(self):
        if self.path == "/health":
            self.reply(200, {"status": "ready", "model": MODEL_ID, "device": "cpu"})
        else:
            self.reply(404, {"error": "not_found"})

    def do_POST(self):
        if self.path != "/rerank":
            return self.reply(404, {"error": "not_found"})
        try:
            if self.headers.get("Transfer-Encoding") or self.headers.get_content_type() != "application/json":
                return self.reply(400, {"error": "JSON content-length request required"})
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= MAX_BODY:
                return self.reply(413, {"error": "request_too_large"})
            payload = json.loads(self.rfile.read(length))
            query, docs, k = validate(payload)
        except (ValueError, UnicodeError):
            return self.reply(400, {"error": "invalid_request"})
        try:
            self.reply(200, self.server.engine.rank(query, docs, k))
        except (BlockingIOError, TimeoutError):
            self.reply(503, {"error": "busy"})
        except Exception:
            self.reply(500, {"error": "inference_failed"})


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, default=Path(__file__).parent / "models")
    parser.add_argument("--port", type=int, default=8091)
    parser.add_argument("--threads", type=int, default=2)
    args = parser.parse_args()
    # In Docker, bind specifically to its internal interface; do not expose the service publicly.
    bind = socket.gethostbyname(socket.gethostname()) if os.getenv("RERANK_BIND") == "container" else "127.0.0.1"
    engine = Engine(args.model_dir, args.threads)
    with Server((bind, args.port), engine) as server:
        print(f"Local reranker ready on {bind}:{args.port}", flush=True)
        server.serve_forever()

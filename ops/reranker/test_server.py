import json
import threading
import unittest
import urllib.error
import urllib.request

from server import Server, validate


class FakeEngine:
    def rank(self, query, documents, k):
        return {"results": [{"index": i, "relevance_score": 1.0 - i / 100} for i in range(k)]}


class RerankerContractTest(unittest.TestCase):
    def test_validate_rejects_types_and_bounds(self):
        for value in [None, {}, {"query": "q", "documents": []},
                      {"query": "q", "documents": ["d"], "top_n": True},
                      {"query": "q", "documents": ["d"], "top_n": 2},
                      {"query": "q", "documents": [1], "top_n": 1},
                      {"query": "q" * 2001, "documents": ["d"], "top_n": 1}]:
            with self.assertRaises(ValueError):
                validate(value)

    def test_http_health_and_rerank_contract(self):
        with Server(("127.0.0.1", 0), FakeEngine()) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                base = f"http://127.0.0.1:{server.server_port}"
                with urllib.request.urlopen(base + "/health", timeout=2) as response:
                    self.assertEqual(json.load(response)["status"], "ready")
                body = json.dumps({"query": "冷战如何沟通", "documents": ["理解感受", "无关条目"], "top_n": 1}).encode()
                req = urllib.request.Request(base + "/rerank", data=body, headers={"Content-Type": "application/json"})
                with urllib.request.urlopen(req, timeout=2) as response:
                    self.assertEqual(json.load(response)["results"][0]["index"], 0)
                req = urllib.request.Request(base + "/rerank", data=b"{}", headers={"Content-Type": "application/json"})
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(req, timeout=2)
                self.assertEqual(error.exception.code, 400)
            finally:
                server.shutdown()
                thread.join(2)

    def test_busy_inference_returns_retry_after(self):
        class Busy:
            def rank(self, *_):
                raise BlockingIOError()
        with Server(("127.0.0.1", 0), Busy()) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                body = json.dumps({"query": "q", "documents": ["d"], "top_n": 1}).encode()
                req = urllib.request.Request(f"http://127.0.0.1:{server.server_port}/rerank", data=body,
                                             headers={"Content-Type": "application/json"})
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(req, timeout=2)
                self.assertEqual(error.exception.code, 503)
                self.assertEqual(error.exception.headers["Retry-After"], "1")
            finally:
                server.shutdown()
                thread.join(2)


if __name__ == "__main__":
    unittest.main()

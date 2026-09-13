"""Download only a pinned ONNX model and tokenizer; verify SHA256 before use.
No remote Python, pickle weights, Torch or paid inference API is involved.
"""
import argparse
import hashlib
import os
from pathlib import Path
import urllib.request

REPO = "cross-encoder/mmarco-mMiniLMv2-L12-H384-v1"
REVISION = "1427fd652930e4ba29e8149678df786c240d8825"
FILES = {
    "onnx/model_quint8_avx2.onnx": (118620016, "6c2513767fb63d008a4377bef7a7a3555433d9436342bb53e35a3a72ffc52d4b"),
    "tokenizer.json": (17082660, "62c24cdc13d4c9952d63718d6c9fa4c287974249e16b7ade6d5a85e7bbb75626"),
}
# Official source is Hugging Face; the ModelScope mirror serves byte-identical files
# (same pinned sizes/checksums below) and is reachable from mainland China networks.
SOURCES = {
    "hf": "https://huggingface.co/{repo}/resolve/{revision}/{file}",
    "modelscope": "https://www.modelscope.cn/models/{repo}/resolve/master/{file}",
}


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def download(destination, source="hf"):
    destination.mkdir(parents=True, exist_ok=True)
    template = SOURCES[source]
    for remote, (size, sha) in FILES.items():
        target = destination / Path(remote).name
        if target.exists() and target.stat().st_size == size and digest(target) == sha:
            print(f"Verified existing {target.name}", flush=True)
            continue
        url = template.format(repo=REPO, revision=REVISION, file=remote)
        partial = target.with_suffix(target.suffix + ".partial")
        print(f"Downloading {target.name} ({size} bytes)", flush=True)
        request = urllib.request.Request(url, headers={"User-Agent": "lwx-local-reranker/1.0"})
        with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as output:
            total = 0
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                if total > size:
                    raise ValueError("Download exceeds pinned size")
                output.write(chunk)
        if partial.stat().st_size != size or digest(partial) != sha:
            raise ValueError(f"Checksum mismatch: {target.name}; do not use this file")
        os.replace(partial, target)
        print(f"SHA256 verified: {target.name}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, default=Path(__file__).parent / "models")
    parser.add_argument("--source", choices=sorted(SOURCES),
                        default=os.environ.get("RERANK_MODEL_SOURCE", "hf"),
                        help="hf = Hugging Face (official); modelscope = mainland-China mirror")
    args = parser.parse_args()
    download(args.directory, args.source)

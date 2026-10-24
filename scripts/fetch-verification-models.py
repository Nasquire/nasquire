#!/usr/bin/env python3
"""명시적 내장 모델 검증용 int8 가중치·토크나이저를 무시되는 build 폴더에 받는다."""
import hashlib
import pathlib
import urllib.request

folder = pathlib.Path(__file__).resolve().parent.parent / 'build/verification/models'
folder.mkdir(parents=True, exist_ok=True)
base = 'https://huggingface.co/Xenova/multilingual-e5-small/resolve/761b726dd34fb83930e26aab4e9ac3899aa1fa78/'
files = [
    ('onnx/model_quantized.onnx', 'model-int8.onnx', 'f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193'),
    ('tokenizer.json', 'tokenizer.json', '0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39'),
]
for source, name, expected in files:
    path = folder / name
    if not path.exists():
        temporary = path.with_suffix('.part')
        try:
            with urllib.request.urlopen(base + source, timeout=90) as response, temporary.open('wb') as output:
                size = 0
                while block := response.read(65536):
                    size += len(block)
                    if size > 256 * 1024 * 1024:
                        raise RuntimeError('모델 파일 상한 초과')
                    output.write(block)
            with temporary.open('rb') as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest() != expected:
                    raise RuntimeError('SHA-256 불일치')
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)
    with path.open('rb') as stream:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != expected:
            raise RuntimeError('기존 파일 SHA-256 불일치: ' + name)
    print(name + ': SHA-256 일치')

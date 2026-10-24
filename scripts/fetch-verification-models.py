#!/usr/bin/env python3
"""명시적 내장 모델 검증용 고정 리비전 파일을 무시되는 build 폴더에 받는다.

인자 없음: 내장 E5 임베딩(int8)·토크나이저. `reranker`: 로컬 cross-encoder(qint8)·토크나이저.
"""
import hashlib
import pathlib
import sys
import urllib.request

root = pathlib.Path(__file__).resolve().parent.parent / 'build/verification/models'
SETS = {
    'embedding': (root, 'https://huggingface.co/Xenova/multilingual-e5-small/resolve/761b726dd34fb83930e26aab4e9ac3899aa1fa78/', [
        ('onnx/model_quantized.onnx', 'model-int8.onnx', 'f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193'),
        ('tokenizer.json', 'tokenizer.json', '0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39'),
    ]),
    # cross-encoder/mmarco-mMiniLMv2-L12-H384-v1 (Apache-2.0). qint8 파일은 amd64/arm64 공용이다.
    'reranker': (root / 'mmarco-mMiniLMv2-L12-H384-v1', 'https://huggingface.co/cross-encoder/mmarco-mMiniLMv2-L12-H384-v1/resolve/1427fd652930e4ba29e8149678df786c240d8825/', [
        ('onnx/model_qint8_arm64.onnx', 'model-qint8.onnx', '1825907d6c1a9001ff78124780bbde20a614a8c3df3b63409cf3c72c6fe5c8b4'),
        ('tokenizer.json', 'tokenizer.json', '62c24cdc13d4c9952d63718d6c9fa4c287974249e16b7ade6d5a85e7bbb75626'),
    ]),
}
for name in sys.argv[1:] or ['embedding']:
    folder, base, files = SETS[name]
    folder.mkdir(parents=True, exist_ok=True)
    for source, target, expected in files:
        path = folder / target
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
                raise RuntimeError('기존 파일 SHA-256 불일치: ' + target)
        print(name + '/' + target + ': SHA-256 일치')

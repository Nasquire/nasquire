# Third-party software

Nasquire's own license is in [LICENSE](LICENSE) and [LICENSE-EXCEPTIONS.md](LICENSE-EXCEPTIONS.md). Dependencies retain their own licenses and notices; those licenses are not replaced by Nasquire's additional permissions. Dependency binaries are unmodified.

| Component | Version | License / source |
| --- | --- | --- |
| Spring Boot / Framework / Security | 4.1.2 / 7.0.10 / 7.1.2 | Apache-2.0, [Spring](https://github.com/spring-projects/spring-boot) |
| Apache Lucene, Nori | 10.5.2 | Apache-2.0, [Lucene](https://lucene.apache.org/) |
| Apache POI / POI OOXML lite schemas / scratchpad | 5.5.1 | Apache-2.0, [POI](https://poi.apache.org/), [license](https://github.com/apache/poi/blob/REL_5_5_1/LICENSE) |
| Apache XMLBeans | 5.3.0 | Apache-2.0, [XMLBeans](https://xmlbeans.apache.org/) |
| hwplib (includes repackaged POI classes) / hwpxlib | 1.1.11 / 1.0.9 | Apache-2.0, [hwplib](https://github.com/neolord0/hwplib), [hwpxlib](https://github.com/neolord0/hwpxlib) |
| Commons Codec / Collections / Math / IO / Lang | 1.20.0 / 4.5.0 / 3.6.1 / 2.21.0 / 3.18.0 (core resolution; app may use Boot-managed versions) | Apache-2.0, [Apache Commons](https://commons.apache.org/) |
| SparseBitSet | 1.3 | Apache-2.0, [source](https://github.com/brettwooldridge/SparseBitSet) |
| curvesapi | 1.08 | BSD-3-Clause, [source](https://github.com/virtuald/curvesapi) |
| Apache PDFBox, FontBox | 3.0.8 | Apache-2.0, [PDFBox](https://pdfbox.apache.org/) |
| MCP Java SDK | 2.1.0 | MIT, [SDK](https://github.com/modelcontextprotocol/java-sdk) |
| ICU4J | 79.1 | Unicode-3.0 and included third-party notices, [ICU license](https://github.com/unicode-org/icu/blob/main/LICENSE) |
| Optional external Tesseract CLI / traineddata (not bundled) | operator-provided, not installed or measured in this verification | Apache-2.0, [Tesseract license](https://github.com/tesseract-ocr/tesseract/blob/main/LICENSE), [language data](https://github.com/tesseract-ocr/tessdata/blob/main/LICENSE) |
| ONNX Runtime Java CPU (full only) | 1.31.0 | MIT, [license](https://github.com/microsoft/onnxruntime/blob/v1.31.0/LICENSE), bundled ThirdPartyNotices.txt |
| DJL API / Hugging Face Tokenizers JNI (full only) | 0.39.0 | Apache-2.0, [DJL](https://github.com/deepjavalibrary/djl/blob/v0.39.0/LICENSE), [Rust tokenizers](https://github.com/huggingface/tokenizers/blob/main/LICENSE) |
| JNA (DJL transitive) | 5.17.0 | Apache-2.0 option of LGPL-2.1-or-later / Apache-2.0, [JNA](https://github.com/java-native-access/jna/blob/5.17.0/LICENSE) |
| Apache Commons Compress (POI/DJL transitive) | 1.28.0 (core) / Boot-managed app resolution | Apache-2.0, [Compress](https://commons.apache.org/proper/commons-compress/) |
| Gson | 2.14.0 (core), Boot-managed version in app | Apache-2.0, [Gson](https://github.com/google/gson/blob/main/LICENSE) |
| Optional multilingual-e5-small model/tokenizer | pinned revision, external files only | MIT upstream model, Apache-2.0 tokenizer implementation; [provenance, SHA-256 and native architectures](docs/embeddings.md) |
| Optional local reranker cross-encoder/mmarco-mMiniLMv2-L12-H384-v1 (full only) | pinned commit `1427fd652930e4ba29e8149678df786c240d8825`, `onnx/model_qint8_arm64.onnx` + `tokenizer.json`, external files only | Apache-2.0 per the model card; trained on mMARCO (MS MARCO translations) whose dataset terms are separate — operators should review them before commercial use; [provenance and SHA-256](docs/verification/full.md) |
| Optional Qdrant server (not bundled) | operator-provided, REST API only; no client library dependency | Apache-2.0, [Qdrant](https://github.com/qdrant/qdrant/blob/master/LICENSE) |
| H2 | 2.4.240 | MPL-2.0 option of MPL-2.0 / EPL-1.0, [H2 license](https://www.h2database.com/html/license.html) |
| HikariCP | 7.0.2 | Apache-2.0, [HikariCP](https://github.com/brettwooldridge/HikariCP) |
| Apache Tomcat / Log4j | 11.0.27 / 2.25.5 | Apache-2.0, [Tomcat](https://tomcat.apache.org/), [Log4j](https://logging.apache.org/log4j/2.x/) |
| Jackson / Reactor / Micrometer / SnakeYAML / JSON Schema Validator | managed by Boot / SDK | Apache-2.0; see dependency JAR notices |
| SLF4J / Reactive Streams | 2.0.20 / 1.0.4 | MIT / MIT-0 |
| Jakarta Annotations API | 3.0.0 | EPL-2.0 or GPL-2.0 with Classpath Exception; use the GPL + Classpath Exception option, [license](https://github.com/jakartaee/common-annotations-api/blob/master/LICENSE.md) |
| React / React DOM | 19.2.4 | MIT, [React](https://github.com/facebook/react/blob/main/LICENSE) |
| Vite / TypeScript (build tools) | 7.3.1 / 5.9.3 | MIT / Apache-2.0 |
| Gradle / Node Gradle plugin (build tools) | 9.8.1 / 7.1.0 | Apache-2.0 |
| Node.js (build tool) | 22.22.0 | MIT and included third-party notices |
| JUnit (test only) | 6.0.3 | EPL-2.0, [JUnit](https://junit.org/) |

This software contains unmodified binary redistributions of the H2 database engine. H2 is available under MPL 2.0 or EPL 1.0; Nasquire uses its MPL 2.0 option. H2's source and complete license are available from [the H2 project](https://www.h2database.com/html/license.html). H2 and all other listed libraries are provided without warranty, subject to their respective license terms. Nested dependency JARs carry their original license and notice files. React's license is also distributed with the frontend as `react-LICENSE.txt`.

No model weights, noncommercial-only model licenses, PyMuPDF, or the Tika parser bundle are included. Embedding implementations use ONNX Runtime or an OpenAI-compatible remote service. Remote reranking, local ONNX cross-encoder reranking, the ML-only server mode and an optional external Tesseract OCR adapter are implemented in the separate full module. The optional Qdrant vector store uses the REST API through the JDK HTTP client and Gson without additional dependencies. PaddleOCR remains unimplemented. No OCR executable or OCR language data is redistributed. ONNX Runtime and DJL are excluded from the default lite app runtime. The external model weights are never bundled in the repository or image. Native dependency JARs include Linux amd64 and arm64 binaries; only amd64 has been executed. Model provenance and checksums are documented in [embeddings](docs/embeddings.md).

Office/HWP samples are generated by Nasquire's test code, including a directly authored minimal Word 97 FIB/piece table and blank documents populated using hwplib/hwpxlib. No external document fixtures are redistributed. Excel extraction uses POI event APIs without the Tika bundle. The HWP/HWPX implementations reference Hancom's published HWP 5.0 and OWPML format specifications through their upstream libraries.

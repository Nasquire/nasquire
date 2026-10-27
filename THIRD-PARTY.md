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
| Tesseract CLI / kor+eng traineddata (full image only; external for JAR use) | OS package versions resolved at image build | Apache-2.0, [Tesseract license](https://github.com/tesseract-ocr/tesseract/blob/main/LICENSE), [language data](https://github.com/tesseract-ocr/tessdata/blob/main/LICENSE) |
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

No model weights, noncommercial-only model licenses, PyMuPDF, or the Tika parser bundle are included. Embedding implementations use ONNX Runtime or an OpenAI-compatible remote service. Remote reranking, local ONNX cross-encoder reranking, the ML-only server mode and an optional external Tesseract OCR adapter are implemented in the separate full module. The optional Qdrant vector store uses the REST API through the JDK HTTP client and Gson without additional dependencies. PaddleOCR remains unimplemented. The full container image includes Tesseract and kor+eng language data; the lite image and application JAR do not bundle them. ONNX Runtime and DJL are excluded from the default lite app runtime. The external model weights are never bundled in the repository or image. Native dependency JARs include Linux amd64 and arm64 binaries; only amd64 has been executed. Model provenance and checksums are documented in [embeddings](docs/embeddings.md).

Office/HWP samples are generated by Nasquire's test code, including a directly authored minimal Word 97 FIB/piece table and blank documents populated using hwplib/hwpxlib. No external document fixtures are redistributed. Excel extraction uses POI event APIs without the Tika bundle. The HWP/HWPX implementations reference Hancom's published HWP 5.0 and OWPML format specifications through their upstream libraries.

## 라이선스 계열 점검

기준: [a5542a2](https://github.com/Nasquire/nasquire/commit/a5542a2)의 Gradle `:app:runtimeClasspath`를 lite/full 각각 해석하고, 기존 표·POM 및 런타임 JAR의 LICENSE/NOTICE를 대조했다. 재현 명령은 `./gradlew :app:dependencies --configuration runtimeClasspath -q`와 `./gradlew -Pfull :app:dependencies --configuration runtimeClasspath -q`다. 프로젝트 모듈을 제외한 외부 아티팩트는 lite 90개, full 94개다. 테스트 전용 JUnit(EPL-2.0), Gradle·Node 등 빌드 도구와 별도 다운로드 모델은 아래 런타임 집계에서 제외한다.

선택 라이선스와 포함된 하위 구성요소도 기재한다. 상위 라이브러리가 Apache/MIT여도 포함된 파일의 라이선스는 별도일 수 있다.

| 런타임 구성요소 | lite | full | 확인한 copyleft 계열·선택 조건 및 근거 |
| --- | --- | --- | --- |
| H2 2.4.240 | 포함 | 포함 | MPL-2.0 또는 EPL-1.0. 기존 정책대로 **MPL-2.0 선택**. [H2 라이선스](https://www.h2database.com/html/license.html). |
| Jakarta Annotations API 3.0.0 | 포함 | 포함 | EPL-2.0 또는 GPL-2.0-only WITH Classpath-exception-2.0. 기존 정책대로 **GPL + Classpath 예외 선택**. JAR `META-INF/LICENSE.md`·`META-INF/NOTICE.md`와 [업스트림 라이선스](https://github.com/jakartaee/common-annotations-api/blob/master/LICENSE.md). |
| Tomcat embed core 11.0.27의 Jakarta EE XSD 파일 | 포함 | 포함 | Tomcat 자체는 Apache-2.0이나 Jakarta EE 스키마는 **EPL-2.0**으로 별도 표시. JAR `META-INF/LICENSE`의 Jakarta EE Schemas 절, [업스트림 LICENSE](https://github.com/apache/tomcat/blob/11.0.27/LICENSE). |
| JNA 5.17.0 | 제외 | 포함 | LGPL-2.1-or-later 또는 Apache-2.0. 기존 정책대로 **Apache-2.0 선택**. [JNA LICENSE](https://github.com/java-native-access/jna/blob/5.17.0/LICENSE). |
| ONNX Runtime 1.31.0의 Eigen 고지 | 제외 | 포함 | ONNX 자체는 MIT이나 JAR `ThirdPartyNotices.txt`의 Eigen은 **MPL-2.0**. [업스트림 ThirdPartyNotices](https://github.com/microsoft/onnxruntime/blob/v1.31.0/ThirdPartyNotices.txt). |
| ONNX Runtime 1.31.0의 Mbed TLS 고지 | 제외 | 포함 | Apache-2.0 또는 GPL-2.0-or-later. JAR `ThirdPartyNotices.txt`에서 배포 시 **Apache-2.0 선택**을 명시. [동일 고지](https://github.com/microsoft/onnxruntime/blob/v1.31.0/ThirdPartyNotices.txt). |

결론: **copyleft 계열 고지가 없는 구성은 아니다.** lite/full 공통으로 MPL·EPL·예외가 붙은 GPL 항목이 있고, full에는 추가 MPL 및 Apache를 선택할 수 있는 LGPL/GPL 고지가 있다. 이번 외부 JVM 런타임 목록·포함 고지 점검에서는 AGPL 의존성이나 예외 없는 GPL만을 유일한 조건으로 요구하는 항목은 확인하지 못했다(Nasquire 자체 AGPL은 별도). 라이선스 선택·Classpath 예외는 해당 의존성에 적용되며 Nasquire나 다른 의존성의 조건을 대체하지 않는다. 특히 Tomcat 스키마의 EPL 및 ONNX 하위 고지를 상위 Apache/MIT 표기만으로 생략하지 않는다. 이 목록은 향후 임의의 라이선스로 재배포할 수 있다는 결론이나 호환성에 대한 법률 판단이 아니다.

### 컨테이너 JRE·OS 패키지 (JVM 의존성과 별도)

[Dockerfile](Dockerfile)의 `eclipse-temurin:25-jre-noble` 기반 이미지와 설치 지시 기준이다. 아래는 주요 구성요소이며 OS 전체 패키지/전이 의존성의 완전한 목록은 아니다. 베이스 태그와 apt 버전은 고정되어 있지 않으므로 실제 배포 이미지의 SBOM 및 `/usr/share/doc/*/copyright`로 버전·라이선스·소스 제공 경로를 다시 확인해야 한다. 이번 작업에서는 이미지를 빌드하거나 패키지 전체를 추출하지 않았다.

| 이미지 구성요소 | 범위 | 라이선스 계열·확인 범위 |
| --- | --- | --- |
| Temurin/OpenJDK JRE | lite/full 베이스 | GPL-2.0 WITH Classpath-exception-2.0 및 포함 제3자 고지. [OpenJDK LICENSE](https://github.com/openjdk/jdk/blob/jdk-25-ga/LICENSE), 배포 JRE의 `legal/` 참고. |
| GNU libc 등 베이스 OS 라이브러리 | lite/full 베이스 | glibc의 LGPL-2.1-or-later 및 파일별 예외/별도 조건. [glibc COPYING.LIB](https://sourceware.org/git/?p=glibc.git;a=blob;f=COPYING.LIB;hb=HEAD). 다른 베이스 패키지는 실제 이미지 저작권 파일 확인 필요. |
| curl / ca-certificates | lite/full 설치 | curl은 curl 라이선스([COPYING](https://github.com/curl/curl/blob/master/COPYING)). ca-certificates는 패키지 스크립트의 GPL 계열과 인증서 자료의 별도 조건을 구분([Ubuntu 소스 패키지](https://launchpad.net/ubuntu/noble/+source/ca-certificates)). |
| Tesseract·kor/eng traineddata | full만 설치 | Apache-2.0([Tesseract](https://github.com/tesseract-ocr/tesseract/blob/main/LICENSE), [tessdata](https://github.com/tesseract-ocr/tessdata/blob/main/LICENSE)). 연결되는 Leptonica 및 이미지 라이브러리는 별도 고지·패키지 조건 확인 필요. |
| util-linux (`prlimit`, `setsid` 등) | full 설치 | 패키지 내 도구·라이브러리별 GPL/LGPL/BSD 등 혼합. [README.licensing](https://github.com/util-linux/util-linux/blob/master/README.licensing). 패키지 전체에 단일 라이선스를 가정하지 않는다. |
| fontconfig / fonts-dejavu-core | full 설치 | fontconfig는 MIT 계열, DejaVu는 Bitstream Vera·공개 도메인 등 파일별 조건. [fontconfig COPYING](https://gitlab.freedesktop.org/fontconfig/fontconfig/-/blob/main/COPYING), [DejaVu LICENSE](https://github.com/dejavu-fonts/dejavu-fonts/blob/master/LICENSE). |

OS/JRE에는 GPL·LGPL 계열 구성요소가 있으므로 JVM 목록만으로 이미지 전체에 copyleft가 없다고 판단할 수 없다. GHCR 게시 전 최종 이미지의 패키지별 고지·대응 소스 제공 요건을 확인하는 작업은 남아 있다.

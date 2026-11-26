# Third-party notices

No binary from the Google Health or Fitbit Android applications is included in
this repository, package archive, or built example APK, and none is loaded at
runtime.

## Golden Gate

- Source: https://github.com/Google-Health-API/golden-gate
- Commit: `b8a6f2552e12aaf92c1549dc88a41c9b7e73f1aa`
- Copyright 2017–2020 Fitbit, Inc.
- License: Apache License 2.0

The full license text is included in [`LICENSE-APACHE-2.0`](LICENSE-APACHE-2.0).

The native libraries under `android/core/src/main/jniLibs/` are built from this
source by [`tool/native/build.py`](tool/native/build.py) without modification;
upstream's `NOTICE` is reproduced in
[`third_party/golden-gate/NOTICE`](third_party/golden-gate/NOTICE).

The Kotlin sources under
`android/core/src/main/kotlin/com/fitbit/goldengate/bindings/` are derived from the
upstream Android bindings and retain their SPDX headers. They include limited
adaptations: a per-stack TLS key resolver and logging cleanups.

## Mbed TLS (statically linked into `libgg.so`)

- Source: https://github.com/Mbed-TLS/mbedtls, release 2.28.10
  (`2fc8413bfcb51354c8e679141b17b3f1a5942561`)
- License: dual Apache-2.0 OR GPL-2.0-or-later; used here under Apache-2.0.
  See [`third_party/mbedtls/LICENSE`](third_party/mbedtls/LICENSE)

## lwIP (statically linked into `libgg.so`)

- Source: https://savannah.nongnu.org/projects/lwip/ (Golden Gate submodule pin)
- Copyright (c) 2001, 2002 Swedish Institute of Computer Science
- License: BSD 3-Clause, see [`third_party/lwip/COPYING`](third_party/lwip/COPYING).
  Apps that ship this plugin must reproduce this notice.

Apps can show all three licenses on Flutter's license page by calling
`FitbitAir.registerLicenses()` once at startup; the example app does.

## VADER sentiment lexicon (example app)

- Source: https://github.com/cjhutto/vaderSentiment
- Copyright 2016 C.J. Hutto
- License: MIT

`example/android/app/src/main/assets/vader/vader_lexicon.txt` keeps the word and
mean-valence columns of the upstream `vader_lexicon.txt`; the license text ships
beside it as `LICENSE.txt`. `RelayVader.kt` is a Kotlin port of the upstream
scoring rules. Hutto, C.J. & Gilbert, E.E. (2014). VADER: A Parsimonious
Rule-based Model for Sentiment Analysis of Social Media Text. ICWSM-14.

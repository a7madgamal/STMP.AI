# Third-party notices

STMP is licensed under the Apache License 2.0 (see `LICENSE`). That grant covers the
original work only. The components below are used under their own licences, listed
with the copyright line each requires.

Every coordinate here is checked by `ThirdPartyNoticesTest`, which reads
`app/build.gradle.kts` and fails when an `implementation(...)` line has no entry in
this file. That test exists because a notices file goes stale the first time someone
adds a dependency, and a stale notices file is worse than none — it makes a claim
that is not true.

## Runtime dependencies

| Component | Licence | Copyright |
|---|---|---|
| `org.nanohttpd:nanohttpd` | BSD 3-Clause | Copyright (c) 2012-2016, nanohttpd |
| `com.github.mwiede:jsch` | BSD-style (see below) | Copyright (c) 2002-2015 Atsuhiko Yamanaka, JCraft, Inc. |
| `androidx.core:core-ktx` | Apache-2.0 | Copyright The Android Open Source Project |
| `androidx.appcompat:appcompat` | Apache-2.0 | Copyright The Android Open Source Project |
| `androidx.constraintlayout:constraintlayout` | Apache-2.0 | Copyright The Android Open Source Project |
| `androidx.biometric:biometric` | Apache-2.0 | Copyright The Android Open Source Project |
| `androidx.credentials:credentials` | Apache-2.0 | Copyright The Android Open Source Project |
| `androidx.credentials:credentials-play-services-auth` | Apache-2.0 | Copyright The Android Open Source Project |
| `com.google.android.material:material` | Apache-2.0 | Copyright The Android Open Source Project |
| `androidx.dynamicanimation:dynamicanimation` | Apache-2.0 | Copyright The Android Open Source Project |
| `com.google.ai.edge.litertlm:litertlm-android` | Apache-2.0 | Copyright Google LLC |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | Apache-2.0 | Copyright JetBrains s.r.o. and contributors |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | Apache-2.0 | Copyright JetBrains s.r.o. and contributors |
| `com.google.android.gms:play-services-auth` | Android Software Development Kit License | Copyright Google LLC |
| `com.google.android.libraries.identity.googleid:googleid` | Android Software Development Kit License | Copyright Google LLC |

### NanoHTTPD — BSD 3-Clause

Redistribution and use in source and binary forms, with or without modification, are
permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of
   conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice, this list
   of conditions and the following disclaimer in the documentation and/or other
   materials provided with the distribution.
3. Neither the name of nanohttpd nor the names of its contributors may be used to
   endorse or promote products derived from this software without specific prior
   written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

### JSch — three bundled licences

The `jsch` jar ships three separate licences, not one. All three are reproduced in
`META-INF/` inside the artefact:

- **JSch** — BSD-style. Copyright (c) 2002-2015 Atsuhiko Yamanaka, JCraft, Inc.
  Earlier JSch 0.0.* releases were LGPL; the current release is BSD.
- **JZlib** — BSD-style. Copyright (c) 2000-2011 ymnk, JCraft, Inc.
- **jBCrypt** — ISC. Copyright (c) 2006 Damien Miller <djm@mindrot.org>.
  Permission to use, copy, modify, and distribute this software for any purpose with or
  without fee is hereby granted, provided that the above copyright notice and this
  permission notice appear in all copies.

Each is a permissive licence requiring only that the copyright notice and permission
notice travel with the software.

### Google Play Services — Android Software Development Kit License

`play-services-auth` and `googleid` are **not** open source. They are distributed under
Google's Android SDK terms (https://developer.android.com/studio/terms.html), which is
what makes them usable inside an Android app but not modifiable or redistributable as
source.

This matters for one specific reason worth stating plainly: it is why STMP cannot be
listed on F-Droid without replacing the Play Services sign-in flow with a
browser-based OAuth implementation (AppAuth). The blocker there is this licence, not
STMP's own.

## Fonts

**Handlee** — Copyright (c) 2011, Joe Prince, Vissol Ltd.
(http://www.vissol.co.uk/mavenpro/), with Reserved Font Name "Handlee".
Licensed under the SIL Open Font License, Version 1.1. The full text is in
`OFL-Handlee.txt`.

**Geist** and **Geist Mono** (v1.9.2) — Copyright 2026 The Geist Project Authors
(https://github.com/vercel/geist-font). Licensed under the SIL Open Font License, Version 1.1.
The full text is in `OFL-Geist.txt`. Bundled as static TTF files in `app/src/main/res/font/`.

## Audio

**Notification cues** (`cue_ping.ogg`, `cue_chime.ogg`, `cue_alarm.ogg`) — from
"Interface Sounds (1.0)" by Kenney (www.kenney.nl), released under Creative Commons Zero
(CC0 1.0). CC0 places the work in the public domain and requires no attribution; the
provenance is recorded per file in `app/src/main/assets/cue-attribution.txt` anyway, so
a future reader can re-check the terms rather than guess.

**Stamp and crease effects** (`stamp.ogg`, `crease.ogg`) — sourced from Freesound and
used as permitted by the terms under which they were obtained.

## Artwork

The stamp images (`stamp_approved.png`, `stamp_rejected.png`) and the launcher icon are
original artwork by the project author, covered by the project's own Apache-2.0 grant.

## Test data — zsh-syntax-highlighting golden corpus

`shellhl/src/test/resources/zsh-corpus/` contains 271 golden test files copied verbatim from
[zsh-users/zsh-syntax-highlighting](https://github.com/zsh-users/zsh-syntax-highlighting)
(`highlighters/main/test-data`), plus that project's `COPYING.md`.

**BSD 3-Clause.** Copyright (c) 2010-2020 zsh-syntax-highlighting contributors. All rights
reserved. Redistribution and use in source and binary forms, with or without modification, are
permitted provided that the above copyright notice, this list of conditions and the following
disclaimer are retained. Neither the name of the zsh-syntax-highlighting contributors nor the
names of its contributors may be used to endorse or promote products derived from this software
without specific prior written permission. The software is provided "as is", without warranty of
any kind.

Every file already carries that copyright header individually, so the notice travels with the
data; `COPYING.md` in the same directory holds the full text. They are test resources only —
never compiled into the APK — but they are committed and redistributed with the repository,
which is why they are listed here rather than under "tooling".

## Build and test tooling — deliberately not listed

`junit` and `robolectric` are `testImplementation` only. They are not compiled into the
APK, are not distributed, and therefore create no attribution obligation for anyone who
receives the app. Listing them would pad this file with claims that do not apply.

The Android Gradle Plugin and the Kotlin compiler are build tools for the same reason.

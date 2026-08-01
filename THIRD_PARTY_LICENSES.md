# Third-party licences

OllamaMobile itself is MIT licensed, Copyright (c) 2026 Jayson Petersen; see [LICENSE](LICENSE).
This document covers the third-party software the application includes or links against, and it is
the file the app displays in its own licence screen.

**This file is shipped as an application asset.** It is packaged into the APK and rendered in-app so
that the licences are readable on the device, offline, without following a link. Keep it accurate:
if you add a runtime dependency, add it here in the same pull request. A generated report is not
used, because a generated report cannot say which component is conditionally compiled and which is
always present — a distinction that matters here.

Two things are deliberately not covered:

* **Build-time-only tooling** (Gradle plugins, ktlint, detekt, Kover, test frameworks) is not
  distributed in the APK and so is not listed. Where a test dependency is also shipped —
  `:core-llm-testing` is compiled into debug builds — it is this project's own code.
* **Model weights.** GGUF files the app downloads are licensed by their publishers, are not
  redistributed by this project, and are not covered by anything below. The licence that applies to
  a model is the one on the model card you downloaded it from.

## Conditionally included: llama.cpp and ggml

Whether llama.cpp is present depends on how the APK was built. With the default
`-Pollama.nativeSource=none` there is no llama.cpp or ggml code in the artifact at all. With
`build` or `prebuilt`, `libllama` and `libggml` (including the per-CPU-variant backend libraries)
are packaged.

| Component | Upstream | Licence | Copyright |
| --------- | -------- | ------- | --------- |
| llama.cpp | https://github.com/ggml-org/llama.cpp | MIT | Copyright (c) 2023-2024 The ggml authors |
| ggml | https://github.com/ggml-org/ggml | MIT | Copyright (c) 2023-2024 The ggml authors |
| KleidiAI | https://github.com/ARM-software/kleidiai | Apache-2.0 | Copyright (c) Arm Limited and affiliates |

ggml is vendored inside the llama.cpp repository and is consumed through it, at
`third_party/llama.cpp`, pinned to tag `b10150` (commit `dee2a846`). The exact commit is recorded
by the submodule pointer, and the release notes for any build containing native code state which
one was used.

KleidiAI is **not** vendored in the submodule. `-DGGML_CPU_KLEIDIAI=ON` makes ggml's CMake fetch
the KleidiAI source release (v1.24.0 at the pinned llama.cpp tag) at configure time, and an
arm64-v8a build packages the result as `libkleidiai.so`. It is Apache-2.0, which is a different
licence from the MIT text below, and it ships in the APK — so it is listed here rather than left
to the submodule to account for. It is not built for x86_64, where the kernels do not apply.

llama.cpp bundles further components of its own, whose licences travel with the submodule; see
`third_party/llama.cpp/LICENSE` and the licence files inside `third_party/llama.cpp/licenses/`
and its vendored directories in any build that includes native code.

### MIT License

The following applies to llama.cpp and ggml, and is the same licence text under which OllamaMobile
itself is distributed.

```text
MIT License

Copyright (c) 2023-2024 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Always included: runtime dependencies

Every component below is licensed under the **Apache License, Version 2.0**. The verbatim licence
text appears once, at the end of this document.

### AndroidX and Jetpack Compose

Copyright (c) The Android Open Source Project. https://developer.android.com/jetpack/androidx

Includes `androidx.compose` (foundation, material3, ui, tooling), `androidx.core`,
`androidx.activity`, `androidx.lifecycle`, `androidx.navigation`, `androidx.room`,
`androidx.sqlite`, `androidx.datastore`, `androidx.work`, `androidx.biometric`,
`androidx.profileinstaller` and `androidx.core:core-splashscreen`.

### Dagger and Hilt

Copyright (c) Google LLC. https://github.com/google/dagger

Dependency injection, with `androidx.hilt` integrations for Compose navigation and WorkManager.

### OkHttp

Copyright (c) Square, Inc. https://square.github.io/okhttp/

The HTTP client behind the remote Ollama client and the downloader. The app uses exactly one
`OkHttpClient` instance so that the network policy applies to every request.

### Ktor

Copyright (c) JetBrains s.r.o. and contributors. https://ktor.io

The CIO server engine and plugins behind the embedded Ollama-compatible HTTP server.

### kotlinx libraries

Copyright (c) JetBrains s.r.o. and Kotlin Programming Language contributors.
https://github.com/Kotlin

`kotlinx.coroutines`, `kotlinx.serialization` and `kotlinx.collections.immutable`, together with the
Kotlin standard library.

### Other Apache-2.0 components

| Component | Upstream | Copyright |
| --------- | -------- | --------- |
| Timber | https://github.com/JakeWharton/timber | Copyright (c) Jake Wharton |
| Coil 3 | https://github.com/coil-kt/coil | Copyright (c) Coil Contributors |
| multiplatform-markdown-renderer | https://github.com/mikepenz/multiplatform-markdown-renderer | Copyright (c) Mike Penz |
| Highlights | https://github.com/SnipMeDev/Highlights | Copyright (c) SnipMe |
| Android core library desugaring (`desugar_jdk_libs`) | https://github.com/google/desugar_jdk_libs | Copyright (c) The Android Open Source Project |

### Apache License, Version 2.0

```text
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```

## Reporting an attribution problem

If something is missing, mis-attributed or licensed differently from what is stated here, please
open an issue — or email jayson@shoe4africa.org if you would rather not do so publicly. Attribution
errors are treated as bugs and fixed in the next release.

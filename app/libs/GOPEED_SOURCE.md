# Gopeed Core Android binding

`gopeed-core-1.9.3-arm64.aar` is built from Gopeed v1.9.3, commit
`a5cd53f94c18ac65add684b1113fa5f0b47cc4da`, licensed under GPL-3.0.

Only Gopeed's mobile Core binding is included. The Gopeed Flutter UI,
desktop application, web UI, and their assets are not packaged. The binding
contains only the `arm64-v8a` native library.

Upstream source: https://github.com/GopeedLab/gopeed/tree/v1.9.3

The binding was generated with Go 1.26.3, Android NDK r27d, and
`golang.org/x/mobile` commit `6129f5bee9d5` using the same `gomobile bind`
arguments as Gopeed's official Android workflow:

```text
gomobile bind -tags nosqlite \
  -ldflags="-w -s -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384 -X github.com/GopeedLab/gopeed/pkg/base.Version=1.9.3" \
  -target=android/arm64 -androidapi 21 -javapkg=com.gopeed \
  github.com/GopeedLab/gopeed/bind/mobile
```

The external-linker max page size keeps every ELF `LOAD` segment aligned to
`0x4000`, so the ARM64 library is compatible with Android 16 KiB page-size
devices.

AAR SHA-256:
`274392009D8501F072FCC36FDE1D94ADF2855625103F779049BC73D70017BC3D`

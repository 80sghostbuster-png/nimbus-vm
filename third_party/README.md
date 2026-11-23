# Third-party sources

All of these are free and open-source. Tarballs themselves are not committed
(too large); download them with `scripts/fetch-sources.sh`. Hashes for the
dependency tarballs we used are in `SHA256SUMS.deps`.

| Component | Version | License | Role |
| --- | --- | --- | --- |
| QEMU | 10.2.4 | GPL-2.0-only | Virtualization engine |
| zlib | 1.3.1 | zlib | Compression |
| libiconv | 1.17 | LGPL-2.1 | Charset conversion for GLib on Android |
| libffi | 3.4.6 | MIT | GLib / gobject |
| PCRE2 | 10.44 | BSD | GLib |
| GLib | 2.80.5 | LGPL-2.1 | QEMU core dependency |
| pixman | 0.44.2 | MIT | QEMU display |
| libslirp | 4.8.0 | BSD-3-Clause | User-mode networking (internet in the guest) |
| OpenSSL | 3.0.16 | Apache-2.0 | libtpms / swtpm |
| libtpms | 0.9.6 | BSD | Software TPM 2.0 |
| swtpm | 0.8.2 | BSD | TPM emulator QEMU talks to |
| EDK2 / AAVMF / OVMF | Debian 2025.02-8 | BSD-2-Clause-Patent | UEFI firmware |

QEMU 10.2.4 (`qemu-10.2.4.tar.xz`, 141137724 bytes):

- SHA-512 `62a2a71e5bad12741bad264673aaf824152a1b797d3de66582c4a1beb7f76ef75f5d2e0aa705702910287d3906b98610902aa723f9b7d38500d694e39bcf2fd5`
- SHA-256 `821b545b92f165e57dddccac5077d76d4d436a226595b8813ad59306bbfd0746`

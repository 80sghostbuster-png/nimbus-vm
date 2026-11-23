package com.lex.nimbusvm

/**
 * Official arm64 installer images. URLs point at the project's current
 * directories so a "latest" SUMS file can be fetched and verified first.
 */
data class DistroPreset(
    val id: String,
    val name: String,
    val blurb: String,
    val sumsUrl: String,
    val dirUrl: String,
    val isoPattern: Regex,
)

object DistroPresets {
    val all: List<DistroPreset> = listOf(
        DistroPreset(
            id = "debian-netinst-arm64",
            name = "Debian netinst (ARM64)",
            blurb = "Official Debian stable network installer",
            sumsUrl = "https://cdimage.debian.org/cdimage/release/current/arm64/iso-cd/SHA256SUMS",
            dirUrl = "https://cdimage.debian.org/cdimage/release/current/arm64/iso-cd/",
            isoPattern = Regex("""debian-[\d.]+-arm64-netinst\.iso"""),
        ),
        DistroPreset(
            id = "ubuntu-server-arm64",
            name = "Ubuntu Server (ARM64)",
            blurb = "Official Ubuntu LTS live-server image",
            sumsUrl = "https://cdimage.ubuntu.com/releases/24.04/release/SHA256SUMS",
            dirUrl = "https://cdimage.ubuntu.com/releases/24.04/release/",
            isoPattern = Regex("""ubuntu-24\.04[\d.]*-live-server-arm64\.iso"""),
        ),
        DistroPreset(
            id = "ubuntu-desktop-arm64",
            name = "Ubuntu Desktop (ARM64)",
            blurb = "Official Ubuntu LTS desktop image",
            sumsUrl = "https://cdimage.ubuntu.com/releases/24.04/release/SHA256SUMS",
            dirUrl = "https://cdimage.ubuntu.com/releases/24.04/release/",
            isoPattern = Regex("""ubuntu-24\.04[\d.]*-desktop-arm64\.iso"""),
        ),
        DistroPreset(
            id = "debian-mini-gtk-arm64",
            name = "Debian mini.iso gtk (ARM64)",
            blurb = "Netboot graphical installer (~233 MB). Current Debian arm64 mini.iso is 197 MB (text) or 233 MB (gtk), not the historical ~50 MB x86 image.",
            sumsUrl = "https://deb.debian.org/debian/dists/stable/main/installer-arm64/current/images/SHA256SUMS",
            dirUrl = "https://deb.debian.org/debian/dists/stable/main/installer-arm64/current/images/netboot/gtk/",
            isoPattern = Regex("""mini\.iso"""),
        ),
        DistroPreset(
            id = "kali-installer-arm64",
            name = "Kali installer (ARM64)",
            blurb = "Official Kali Linux installer image",
            sumsUrl = "https://cdimage.kali.org/current/SHA256SUMS",
            dirUrl = "https://cdimage.kali.org/current/",
            isoPattern = Regex("""kali-linux-[\d.w]+-installer-arm64\.iso"""),
        ),
    )
}

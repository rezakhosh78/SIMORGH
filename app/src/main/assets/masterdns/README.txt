SIMORGH MasterDNS mode uses the MasterDnsVPN native core.
For arm64-v8a builds, Gradle downloads the ARM64 client asset automatically from:
masterking32/MasterDnsVPN release v2026.06.13.234407-7de2476
Preferred asset: MasterDnsVPN_Client_Linux_ARM64.zip
The extracted ELF is placed as app/src/main/jniLibs/arm64-v8a/libmasterdns.so.

Manual fallback:
Package the core as app/src/main/jniLibs/<abi>/libmasterdns.so or libstormdns.so.
The app starts it in proxy mode (local SOCKS5) or VPN mode (Android VpnService TUN -> tun2socks -> MasterDNS SOCKS5).

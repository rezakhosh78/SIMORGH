# Soar integration in PingNG

Upstream source: https://github.com/getlantern/soar

Pinned upstream revision: `a8af533d82284f5e6ab13b706552596c108bacf2` (`main`, cloned 2026-10-22).

PingNG adds `cmd/pingng-soar` to the upstream Go module. It starts the upstream Soar client and exposes a loopback-only SOCKS5 TCP CONNECT listener for PingNG's existing Xray outbound. Soar does not carry UDP; PingNG routes DNS through its DNS handler, sends TCP through the local SOCKS listener, and blocks other UDP for a Soar profile. The app builds the executable for each Android ABI during Gradle packaging.


The upstream project is pre-release and does not promise wire compatibility yet. The upstream Apache-2.0 license is retained in `LICENSE`.

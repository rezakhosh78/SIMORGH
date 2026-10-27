#!/usr/bin/env bash
# Clones and builds the other DNS tunnels dnsbench compares against, at the commits behind the
# README numbers, into DIR (default ./others). Needs git, Go, Rust (cargo), meson, ninja, CMake,
# pkg-config, a C toolchain, and OpenSSL development headers and libraries.
set -euo pipefail

DIR=${1:-others}
mkdir -p "$DIR/bin"
DIR=$(cd "$DIR" && pwd)

fetch() { # name url commit
	if [ ! -d "$DIR/$1" ]; then
		git clone -q "$2" "$DIR/$1"
	fi
	git -C "$DIR/$1" checkout -q "$3"
	git -C "$DIR/$1" submodule update -q --init --recursive
}

fetch dnstt https://www.bamsoftware.com/git/dnstt.git 0c5c52a57d89
(cd "$DIR/dnstt" && go build -o "$DIR/bin/dnstt-server" ./dnstt-server && go build -o "$DIR/bin/dnstt-client" ./dnstt-client)

fetch vaydns https://github.com/net2share/vaydns a0ff70110d5e
(cd "$DIR/vaydns" && go build -o "$DIR/bin/vaydns-server" ./vaydns-server && go build -o "$DIR/bin/vaydns-client" ./vaydns-client)

fetch MasterDnsVPN https://github.com/masterking32/MasterDnsVPN.git acbf1c61f907
(cd "$DIR/MasterDnsVPN" && go build -o "$DIR/bin/masterdnsvpn-server" ./cmd/server && go build -o "$DIR/bin/masterdnsvpn-client" ./cmd/client)

fetch CottenDNS https://github.com/WhiteDNS/CottenDNS 1f3cc524dddb
(cd "$DIR/CottenDNS" && go build -o "$DIR/bin/cottendns-server" ./cmd/server && go build -o "$DIR/bin/cottendns-client" ./cmd/client)

fetch slipstream https://github.com/EndPositive/slipstream 397850b16e70
(cd "$DIR/slipstream" && meson setup build --buildtype=release >/dev/null && ninja -C build >/dev/null)

fetch soar-rs https://github.com/getlantern/soar-rs 9e653ab9aafc
(cd "$DIR/soar-rs" && cargo build -q --release --bin soar-server --bin soar-client)

fetch slipstream-rust https://github.com/Mygod/slipstream-rust 7de506b222c8
(cd "$DIR/slipstream-rust" && cargo build -q --release)

echo "built into $DIR; run: go run ./cmd/dnsbench -others $DIR"

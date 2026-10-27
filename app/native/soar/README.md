# Soar

**Soar is a DNS tunnel built to be the fastest and most reliable way through networks that censor
everything except DNS.**

Website: **[soar.lantern.io](https://soar.lantern.io)**, with how it works and benchmarks (source in
[getlantern/soar-site](https://github.com/getlantern/soar-site)). A wire-compatible Rust client and
server live in [getlantern/soar-rs](https://github.com/getlantern/soar-rs). Soar is the
last-resort transport in [kindling](https://kindling.lantern.io), from the [Lantern](https://lantern.io) team.

The name comes from the root of every DNS zone: the **SOA** record, *Start of Authority*. Each zone
begins with one, and Soar's server is that authority for its zone, the place every query ends up.
Add an **r** and the authority *soars*, up and over the censor through the resolvers it can't
afford to block.

Soar carries TCP streams through ordinary recursive resolvers (the ISP's, public ones, or
in-country ones it discovers), to a server that is the authoritative name server for a delegated
zone. It is written in Go, with a client library and a server in one module, and is designed for
memory-constrained mobile clients (iOS network extensions included).

## Why another DNS tunnel

Existing tunnels each do one thing well. dnstt and VayDNS are simple but use a single resolver and
recover slowly from loss. slipstream runs QUIC over DNS and copes with loss, but it's archived and
falls over when resolvers truncate, die or rate-limit. MasterDnsVPN and its forks (StormDNS,
CottenDNS) handle resolvers well but take tens of seconds to connect and share one static key
among all clients. Soar was designed from scratch against all of them, in an emulator that models
what censors and resolvers actually do (see [Benchmarks](#benchmarks)).

## Design

- **Packets, not segments.** A session has one packet-number space; stream data travels as
  byte ranges, so a retransmission is re-cut to fit whichever resolver it goes out on. Several
  frames share each packet, and each DNS message carries one packet sized to that path.
- **An answer is an acknowledgement.** Only the server can produce an authenticated answer to a
  query, so an answer proves the query arrived: upstream needs no ACK frames at all. Downstream,
  the client acknowledges packets and also reports *which queries got no answer* (`LOST`), so the
  server resends exactly what was lost instead of waiting out a timer.
- **Fast loss detection** at both ends: per-path packet thresholds on the client, RFC 9002-style
  packet and time thresholds on the server, and the tail of every transfer sent twice, on two
  paths, so the last packet of a request or response doesn't cost a timeout.
- **Every resolver at once.** Each resolver is a path with its own RTT, loss record, name-length
  and answer-size budgets, and congestion window. Windows shrink on *runs* of losses (how resolver
  rate limits drop queries) rather than on scattered random loss, so lossy-but-healthy resolvers
  stay fully used.
- **Held polls.** The server holds idle queries until it has data or the client's hold budget
  runs out, answering the oldest first, so downstream data leaves the moment it's ready.
- **Small upstream overhead.** Some networks drop names longer than ~101 characters, which leaves
  about 50 bytes per query. Soar's upstream header is 4 bytes and its tag 8, and every path starts
  at that safe length, probing up to the full 253.
- **Built for censors.**
  - TCP/53 paths (RFC 7766 pipelining) for networks that intercept UDP/53.
  - Forged UDP answers are ignored: they can't authenticate, so the client keeps waiting for the
    real one.
  - AAAA answers where a resolver refuses TXT.
  - Discovery of working resolvers, vetted by the same authenticated handshake.
- **Crypto.** X25519 ephemeral key exchange with the server's Ed25519 signature over the
  transcript (forward secret, server authenticated); per-direction ChaCha20-Poly1305 with the tag
  truncated to 8 bytes; QUIC-style protection of packet numbers.

### Wire format

The full specification is [SPEC.md](SPEC.md), with test vectors in
[testdata/vectors.json](testdata/vectors.json) for other implementations, such as
[soar-rs](https://github.com/getlantern/soar-rs). In outline:

Upstream, in the query name under the zone (lower-case base32):

```
hello:  sid=0 (2) | client X25519 public key (32)
data:   sid (2) | protected pn (2) | seal( hdr (1) | frames ) | tag (8)
        hdr = hold budget (4 bits, x100ms) | answer size class (4 bits, 512+48k bytes)
```

Downstream, in TXT character-strings (or numbered 15-byte AAAA chunks):

```
hello:  server X25519 public key (32) | sid (2) | Ed25519 signature (64)
data:   protected pn (2) | seal( held (1, x10ms) | backlog (1) | frames ) | tag (8)
```

Frames: `PADDING`, `PING`, `ACK` (ranges), `RESET_STREAM`, `MAX_STREAM_DATA`, `CLOSE`, `LOST`
(upstream pns that got no answer), `OPENED` (the server reached a stream's destination), `STREAM`
(id, offset, length, FIN). A stream's first bytes name its destination.

## Usage

### Server

Delegate a zone (say `t.example.com`) to a host with an `NS` record, then:

```sh
go install github.com/getlantern/soar/cmd/soar-server@latest
(umask 077; soar-server keygen | awk '$1 == "private" { print $2 }' > key)  # private key, mode 600
soar-server pubkey key             # the public key (ship it with clients)
soar-server -zone t.example.com -key-file key -listen :53
```

The server dials destinations on behalf of anyone holding its (public) key, so by default it
refuses non-public addresses (including its own) and only allows ports 80 and 443
(`-allow-ports`).

### Client

```go
c, err := soar.NewClient(soar.Config{
	Zone:      "t.example.com",
	ServerKey: serverPublicKey,
	Resolvers: []string{"8.8.8.8:53", "1.1.1.1:53"},
	Discovery: soar.Discovery{Enabled: true, Country: "IR"},
})
conn, err := c.DialContext(ctx, "tcp", "example.com:443")
```

`Client` also implements kindling's DNS-tunnel interface (`NewRoundTripper`, `MaxLength`,
`RequestTimeout`, `Close`), so it plugs into `kindling.WithDNSTunnel`.

## Benchmarks

Measured with [`dnsbench`](bench/), which runs each tunnel through emulated recursive resolvers with
realistic impairments (delay, jitter, loss, rate limits, truncation, dead resolvers, long-name
filtering, TXT filtering, UDP blocking, answer injection). 100 KB fetch throughput, KB/s:

| scenario | Soar | best other | connect |
|---|---|---|---|
| clean | **476** | 433 (VayDNS) | 0.3s |
| realistic | **124** | 91 (slipstream-rust) | 0.5s |
| lossy, 10% each way | **91** | 57 (slipstream-rust) | 1.4s |
| rate-limited resolvers | **78** | 63 (slipstream-rust) | 0.4s |
| truncating resolver | 168 | 169 (VayDNS), a tie | 0.4s |
| 2 of 5 resolvers dead | **136** | 92 (VayDNS, given a good resolver) | 0.6s |
| Iran-like | **69** | 31 (CottenDNS, 21s to connect) | 0.8s |
| Russia-like (UDP blocked) | **130** | 13 (CottenDNS, 13s to connect); dnstt, VayDNS under 10; slipstream fails | 0.8s |
| China-like (injection, rate limits) | 49 | **74** (CottenDNS); VayDNS, MasterDnsVPN fail | 1.1s |

Each figure is the median of three runs of a 100 KB fetch of incompressible data; runs vary by
about 15%.

These are emulator results; field measurements from inside Iran, Russia and China come next.

## Status

Pre-release. The protocol may still change, and has no compatibility promises yet.

## License

Apache 2.0. The bundled Iran resolver and range lists come from KevinNet DNS (MIT); see
[data/THIRD_PARTY.md](data/THIRD_PARTY.md).

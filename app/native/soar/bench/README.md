# dnsbench

The harness behind the benchmark table in Soar's README. It runs each DNS tunnel through emulated
recursive resolvers on the local machine and measures cold start, small and 100 KB fetch times,
failures, and queries per KB.

- `emu/` emulates the resolvers: a resolver sits between the tunnel client and its authoritative
  server, rewrites transaction IDs, retries upstream and answers SERVFAIL on timeout, as a real
  recursive resolver does. Each one can add delay, jitter, loss in either direction, a per-client
  rate limit, truncation or dropping of large answers, TXT filtering, a QNAME length limit, UDP
  blocking (TCP/53 still works), and forged answers racing the real ones. `emu/` also holds the
  origin the tunnels fetch from; it serves a fixed pseudo-random pattern, so every fetch is checked
  for corruption and tunnels that compress their payload get no head start.
- `bench/scenarios.go` defines the networks. The `iranlike`, `russialike` and `chinalike`
  scenarios are models built from public reports, not measurements.
- `contenders/` runs each tunnel. Soar runs in-process; the others run from their own binaries.

## Running

Soar alone:

```
go run ./cmd/dnsbench
```

With the other tunnels, built from source at the commits the README numbers came from:

```
./build-contenders.sh others
go run ./cmd/dnsbench -others others
```

Use `-scenarios clean,lossy` and `-contenders soar,vaydns` to run a subset, and `-trials` to set the
number of fetches per size (default 5). The published figures are medians of three separate runs
with the defaults, since a single run varies by about 15%. Each contender's server and client logs go to a temporary
directory, printed at start.

On macOS, add loopback aliases so each resolver gets its own IP. Without them, all resolvers
share 127.0.0.1, and clients that dedupe resolvers by IP see only one:

```
for i in 2 3 4 5 6; do sudo ifconfig lo0 alias 127.0.0.$i; done
```

## How the others are run

- **dnstt** and **VayDNS** take a single resolver. They get the healthiest one in each scenario,
  the best choice a user could make by hand.
- **slipstream** (C and Rust) get every resolver.
- **MasterDnsVPN** runs with its shipped defaults, which duplicate each packet 3 times.
- **CottenDNS** runs its `speed` and `survival` presets with `MIN_DOWNLOAD_MTU=200` and
  `DATA_ENCRYPTION_METHOD=3`.
- Everything else uses each project's shipped configuration. The harness overrides only the zone,
  the listen and forward addresses, and the key. MasterDnsVPN and CottenDNS also get
  `PROTOCOL_TYPE = "TCP"`, which forwards plain TCP to the origin instead of running a SOCKS5
  proxy. It changes the local proxy mode, not how the tunnel uses DNS.

If a setting here is unfair to a tunnel, please open an issue or a PR.

package soar

import (
	"bufio"
	"crypto/rand"
	"embed"
	"encoding/binary"
	mrand "math/rand/v2"
	"net/netip"
	"os"
	"strings"
	"time"
)

// Bundled per-country candidate data: data/resolvers/<cc>.txt lists resolvers known to forward
// in that country, data/ranges/<cc>.txt its address ranges for optional sampling, and
// data/resolvers/global.txt public resolvers tried everywhere. Adding a country is adding files.
//
//go:embed data/resolvers/*.txt data/ranges/*.txt
var bundled embed.FS

// Discovery finds resolvers that carry the tunnel when the configured ones don't, or none are
// configured (as on mobile, where the system resolvers aren't readable). Candidates join as
// unverified paths; only an answer authenticated by the server verifies one, so hijacking
// resolvers and DPI that drops tunnel-shaped queries are filtered out by the same test that
// proves the path works. Verified resolvers carry traffic at once; the rest are pruned.
type Discovery struct {
	Enabled bool
	// Country is the ISO 3166-1 alpha-2 code of the network the device is on (e.g. "IR"), if
	// known. It selects the bundled in-country resolvers and ranges.
	Country string
	// Candidates are extra resolvers to vet, tried before the bundled lists.
	Candidates []string
	// SampleRanges is how many addresses to sample from the country's bundled ranges after the
	// lists are exhausted. Off by default: probing many in-country addresses from a user's device
	// is a visible pattern, so enable it deliberately.
	SampleRanges int
	// Want is the target number of healthy paths: discovered candidates in discovery mode, or
	// explicitly configured resolver addresses in manual mode. A verified path that's unusable
	// or on a run of losses doesn't count, so probing resumes when resolvers get blocked. Default 16.
	Want int
	// Concurrency bounds unverified candidate or manual resolver addresses being probed at once.
	// Default 16.
	Concurrency int
	// Pace is the minimum gap between starting candidate or manual resolver probes. Default 20ms.
	Pace time.Duration
	// Cache persists verified resolvers across runs. Discovery tries cached resolvers first;
	// manual mode only prioritizes cache entries still present in the configured resolver list.
	Cache ResolverCache

	noBundled bool // tests: skip the device's and bundled lists
}

// ResolverCache stores resolvers that carried the tunnel on the current network.
type ResolverCache interface {
	Load() []string
	Store(resolvers []string)
}

func (d *Discovery) setDefaults() {
	if d.Want <= 0 {
		d.Want = 16
	}
	if d.Concurrency <= 0 {
		d.Concurrency = 16
	}
	if d.Pace <= 0 {
		d.Pace = 20 * time.Millisecond
	}
	d.Country = strings.ToLower(strings.TrimSpace(d.Country))
}

// systemResolvers is the device's own resolvers; a variable so tests can isolate from the host.
var systemResolvers = func() []string {
	b, err := os.ReadFile("/etc/resolv.conf")
	if err != nil {
		return nil
	}
	var out []string
	for _, line := range strings.Split(string(b), "\n") {
		if f := strings.Fields(line); len(f) >= 2 && f[0] == "nameserver" {
			if a, err := netip.ParseAddr(f[1]); err == nil {
				out = append(out, netip.AddrPortFrom(a, 53).String())
			}
		}
	}
	return out
}

// candidates lists resolvers to vet, in order: cached, extra, the device's own, the country's
// bundled list, global public resolvers, then sampled in-country addresses.
func (d *Discovery) candidates() []string {
	seen := make(map[string]bool)
	var out []string
	add := func(specs []string) {
		for _, s := range specs {
			if ap, err := parseResolver(s); err == nil && !seen[ap.String()] {
				seen[ap.String()] = true
				out = append(out, ap.String())
			}
		}
	}
	if d.Cache != nil {
		add(d.Cache.Load())
	}
	add(d.Candidates)
	if d.noBundled {
		return out
	}
	add(systemResolvers())
	if d.Country != "" {
		add(readList("data/resolvers/" + d.Country + ".txt"))
	}
	add(readList("data/resolvers/global.txt"))
	if d.Country != "" && d.SampleRanges > 0 {
		add(sampleRanges(readList("data/ranges/"+d.Country+".txt"), d.SampleRanges))
	}
	return out
}

// parseResolver accepts "ip" or "ip:port".
func parseResolver(s string) (netip.AddrPort, error) {
	s = strings.TrimSpace(s)
	if ap, err := netip.ParseAddrPort(s); err == nil {
		return ap, nil
	}
	a, err := netip.ParseAddr(s)
	if err != nil {
		return netip.AddrPort{}, err
	}
	return netip.AddrPortFrom(a, 53), nil
}

// readList reads a bundled list, skipping comments and blanks. A missing country is just empty.
func readList(name string) []string {
	f, err := bundled.Open(name)
	if err != nil {
		return nil
	}
	defer f.Close()
	var out []string
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		if line := strings.TrimSpace(sc.Text()); line != "" && !strings.HasPrefix(line, "#") {
			out = append(out, line)
		}
	}
	return out
}

// sampleRanges draws n addresses from the given prefixes as short contiguous runs at random
// offsets, in an order unique to this client so devices don't all probe the same hosts.
func sampleRanges(specs []string, n int) []string {
	var prefixes []netip.Prefix
	for _, s := range specs {
		if p, err := netip.ParsePrefix(s); err == nil && p.Addr().Is4() {
			prefixes = append(prefixes, p.Masked())
		}
	}
	if len(prefixes) == 0 {
		return nil
	}
	var seed [32]byte
	rand.Read(seed[:])
	r := mrand.New(mrand.NewChaCha8(seed))
	const run = 4
	seen := make(map[netip.Addr]bool)
	var out []string
	for attempts := 0; len(out) < n && attempts < n*4; attempts++ {
		p := prefixes[r.IntN(len(prefixes))]
		size := uint32(1) << (32 - p.Bits())
		if size < run+2 {
			continue
		}
		base := binary.BigEndian.Uint32(p.Addr().AsSlice())
		off := 1 + r.Uint32N(size-run-1) // skip the network address
		for k := uint32(0); k < run && len(out) < n; k++ {
			a := netip.AddrFrom4([4]byte(binary.BigEndian.AppendUint32(nil, base+off+k)))
			if !seen[a] {
				seen[a] = true
				out = append(out, netip.AddrPortFrom(a, 53).String())
			}
		}
	}
	return out
}

// storeCache writes verified-resolver snapshots to the cache one at a time, in order, so a
// cache needn't be safe for concurrent use and an older snapshot never lands last.
func (c *Client) storeCache(cache ResolverCache) {
	defer close(c.cacheDone)
	for {
		select {
		case <-c.done:
			select { // flush the newest snapshot, if one is still queued
			case good := <-c.cacheQ:
				cache.Store(good)
			default:
			}
			return
		case good := <-c.cacheQ:
			cache.Store(good)
		}
	}
}

// healthy reports a verified path that works now. A path on a run of losses isn't healthy even
// when it's usable: the client never suspends its last usable path, so that one stays usable
// while failing, and counting it would keep discovery from finding a replacement.
func (p *path) healthy(now time.Time) bool {
	return p.verified && p.usable(now) && p.consec < 5
}

// wantsCandidateLocked reports whether discovery has candidates left and room to probe one.
func (c *Client) wantsCandidateLocked(now time.Time) bool {
	d := &c.cfg.Discovery
	if !d.Enabled || c.candIdx >= len(c.cands) {
		return false
	}
	healthy, probing := 0, 0
	for _, p := range c.paths {
		if p.healthy(now) {
			healthy++
		} else if !p.verified && p.candidate {
			probing++
		}
	}
	return healthy < d.Want && probing < d.Concurrency
}

// discoverLocked adds candidate paths at the configured pace while too few paths are healthy,
// and prunes candidates that failed without ever answering.
func (c *Client) discoverLocked(now time.Time) {
	d := &c.cfg.Discovery
	if !d.Enabled {
		return
	}
	verified, healthy, probing := 0, 0, 0
	kept := c.paths[:0]
	for _, p := range c.paths {
		switch {
		case p.verified:
			verified++
			if p.healthy(now) {
				healthy++
			}
		case p.candidate && (p.outN >= 3 && p.inflight == 0 || p.tcp != nil && now.Sub(p.created) > 10*time.Second):
			if p.tcp != nil {
				p.tcp.stop()
			}
			continue // never answered: drop it
		case p.candidate:
			probing++
		}
		kept = append(kept, p)
	}
	c.paths = kept
	if verified != c.cachedVerified && d.Cache != nil {
		c.cachedVerified = verified
		// Cache every verified resolver, whatever transport verified it: a TCP-only resolver is
		// the usual case where UDP/53 is intercepted, and it gets its TCP path again on load.
		seen := make(map[string]bool)
		var good []string
		for _, p := range c.paths {
			if a := p.addr.String(); p.verified && !seen[a] {
				seen[a] = true
				good = append(good, a)
			}
		}
		// Keep only the newest snapshot queued; one goroutine stores them in order.
		select {
		case <-c.cacheQ:
		default:
		}
		c.cacheQ <- good
	}
	if healthy >= d.Want || probing >= d.Concurrency || now.Sub(c.lastCand) < d.Pace {
		return
	}
	for c.candIdx < len(c.cands) {
		r := c.cands[c.candIdx]
		c.candIdx++
		before := len(c.paths)
		if c.addPathLocked(r) == nil && len(c.paths) > before {
			for _, p := range c.paths[before:] {
				p.candidate = true
			}
			c.lastCand = now
			return
		}
	}
}

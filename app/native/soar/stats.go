package soar

import "time"

// PathStat is a snapshot of one resolver path, for diagnostics: which
// resolvers carry the tunnel on the user's network, and how well.
type PathStat struct {
	Resolver    string        `json:"resolver"`            // host:port of the recursive resolver
	Transport   string        `json:"transport"`           // "udp" or "tcp" (TCP/53, or what Config.DialTCP makes of it)
	Verified    bool          `json:"verified"`            // has carried at least one authenticated answer
	Suspended   bool          `json:"suspended"`           // backed off after a run of losses
	Candidate   bool          `json:"candidate,omitempty"` // added by discovery
	RTT         time.Duration `json:"rtt"`
	Loss        float64       `json:"loss"` // fraction lost over the recent outcome window
	Inflight    int           `json:"inflight"`
	Window      float64       `json:"window"`
	NameLen     int           `json:"name_len"`     // current query-name cap
	AnswerLimit int           `json:"answer_limit"` // answer size cap sizing has accepted for this path; 0 until verified
	RecordType  string        `json:"record_type"`
	Successes   int           `json:"successes"`
	LastSuccess time.Time     `json:"last_success,omitzero"`
}

// PathStats returns a snapshot of every path, in the order paths were added: configured
// resolvers first, then paths added later (TCP siblings under TCP "auto", and discovered
// resolvers).
func (c *Client) PathStats() []PathStat {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := time.Now()
	out := make([]PathStat, 0, len(c.paths))
	for _, p := range c.paths {
		s := PathStat{
			Resolver:    p.addr.String(),
			Transport:   "udp",
			Verified:    p.verified,
			Suspended:   p.suspended.After(now),
			Candidate:   p.candidate,
			Loss:        p.lossRate(),
			Inflight:    p.inflight,
			Window:      p.cwnd,
			NameLen:     p.nameLen,
			RecordType:  "TXT",
			Successes:   p.successes,
			LastSuccess: p.lastSuccess,
		}
		if p.tcp != nil {
			s.Transport = "tcp"
		}
		if p.hasRTT {
			s.RTT = p.srtt
		}
		if p.verified {
			s.AnswerLimit = 512 + 48*p.sizeOK
		}
		if p.qtype == typeAAAA {
			s.RecordType = "AAAA"
		}
		out = append(out, s)
	}
	return out
}

// DiscoveryProgress reports candidates that have finished probing, the candidate pool size, and
// verified discovered resolvers. A candidate with an unverified live path is still in flight.
func (c *Client) DiscoveryProgress() (completed, total, verified int) {
	c.mu.Lock()
	defer c.mu.Unlock()
	total = len(c.cands)
	started := c.candIdx
	active := make(map[string]bool)
	good := make(map[string]bool)
	for _, p := range c.paths {
		if !p.candidate {
			continue
		}
		key := p.addr.String()
		if p.verified {
			good[key] = true
		} else {
			active[key] = true
		}
	}
	verified = len(good)
	completed = started - len(active)
	if completed < 0 {
		completed = 0
	}
	return completed, total, verified
}

// ManualResolverProgress reports probes of explicitly configured resolvers when discovery is off.
// It never includes system, cached, bundled, or discovered resolvers.
func (c *Client) ManualResolverProgress() (completed, total, verified int) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.cfg.Discovery.Enabled {
		return 0, 0, 0
	}
	type resolverState struct {
		attempted bool
		verified  bool
	}
	resolvers := make(map[string]resolverState)
	for _, p := range c.paths {
		if p.candidate {
			continue
		}
		key := p.addr.String()
		state := resolvers[key]
		state.attempted = state.attempted || p.outN > 0
		state.verified = state.verified || p.verified
		resolvers[key] = state
	}
	total = len(resolvers)
	for _, state := range resolvers {
		if state.attempted {
			completed++
		}
		if state.verified {
			verified++
		}
	}
	return completed, total, verified
}

// Established reports whether a session with the server is up.
func (c *Client) Established() bool {
	c.mu.Lock()
	cs := c.sess
	c.mu.Unlock()
	if cs == nil {
		return false
	}
	select {
	case <-c.done:
		return false
	default:
	}
	select {
	case <-cs.ready:
		return cs.session != nil && !cs.isClosed()
	default:
		return false
	}
}

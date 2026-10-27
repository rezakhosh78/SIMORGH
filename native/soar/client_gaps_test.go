package soar

import (
	"context"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/rand"
	"fmt"
	"testing"
	"time"
)

type recordingResolverCache struct {
	entries []string
	stored  [][]string
	loads   int
}

func (c *recordingResolverCache) Load() []string {
	c.loads++
	return append([]string(nil), c.entries...)
}

func (c *recordingResolverCache) Store(resolvers []string) {
	c.stored = append(c.stored, append([]string(nil), resolvers...))
}

// gapClient builds a client that never reaches anything (TEST-NET resolvers), for tests that
// drive its internals under c.mu.
func gapClient(t *testing.T, cfg Config) *Client {
	t.Helper()
	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	cfg.Zone, cfg.ServerKey = testZone, pub
	c, err := NewClient(cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	return c
}

// enableDiscovery turns discovery on with cands, under c.mu. Tests build the client with it off,
// so its run loop can't add a candidate before the test takes the lock.
func enableDiscovery(c *Client, want int, cands ...string) {
	c.cfg.Discovery.Enabled, c.cfg.Discovery.Want, c.cfg.Discovery.noBundled = true, want, true
	c.cfg.Discovery.setDefaults()
	c.cands, c.candIdx = cands, 0
}

func TestManualResolverCacheOnlyPrioritizesConfiguredEntries(t *testing.T) {
	cache := &recordingResolverCache{entries: []string{"192.0.2.99:53", "192.0.2.77:53"}}
	c := gapClient(t, Config{
		Resolvers: []string{"192.0.2.1:53", "192.0.2.99:53"},
		Discovery: Discovery{Enabled: false, Cache: cache},
		TCP:       "off",
	})

	if len(c.paths) != 2 || c.paths[0].addr.String() != "192.0.2.99:53" || c.paths[1].addr.String() != "192.0.2.1:53" {
		t.Fatalf("manual resolver list was changed: %v", c.paths)
	}
	if c.cfg.Discovery.Enabled || len(c.cands) != 0 || c.cacheQ == nil {
		t.Fatal("manual scan should reuse its cache without enabling resolver discovery")
	}
	if cache.loads != 1 || len(cache.stored) != 0 {
		t.Fatalf("manual resolver cache activity: loads=%d stores=%d", cache.loads, len(cache.stored))
	}
}

func TestManualResolverCacheStoresOnlyVerifiedConfiguredResolvers(t *testing.T) {
	cache := &recordingResolverCache{}
	c := gapClient(t, Config{
		Resolvers: []string{"192.0.2.1:53", "192.0.2.2:53"},
		Discovery: Discovery{Enabled: false, Cache: cache},
		TCP:       "off",
	})
	c.mu.Lock()
	c.paths[1].verified = true
	c.queueManualResolverCacheLocked()
	c.mu.Unlock()
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	if len(cache.stored) != 1 || len(cache.stored[0]) != 1 || cache.stored[0][0] != "192.0.2.2:53" {
		t.Fatalf("manual cache stored non-verified or non-manual resolvers: %v", cache.stored)
	}
}

func TestManualResolverProgressTracksOnlyConfiguredResolverPaths(t *testing.T) {
	c := gapClient(t, Config{
		Resolvers: []string{"192.0.2.1:53", "192.0.2.2:53"},
		Discovery: Discovery{Enabled: false},
		TCP:       "off",
	})

	completed, total, verified := c.ManualResolverProgress()
	if completed != 0 || total != 2 || verified != 0 {
		t.Fatalf("initial manual resolver progress = %d/%d valid=%d", completed, total, verified)
	}

	c.mu.Lock()
	now := time.Now()
	c.paths[0].onLoss(now)
	c.paths[1].onSuccess(now, 10*time.Millisecond, c.cfg.MaxPathWindow)
	c.mu.Unlock()

	completed, total, verified = c.ManualResolverProgress()
	if completed != 2 || total != 2 || verified != 1 {
		t.Fatalf("manual resolver progress = %d/%d valid=%d", completed, total, verified)
	}
}

// establish gives c a session as if a handshake had completed.
func establish(c *Client) *clientSession {
	cs := &clientSession{ready: make(chan struct{}), established: true, sid: 1}
	cs.session = newSession(true, streamConfig{StreamWindow: 1 << 16, SendBuffer: 1 << 16, MaxStreams: 4}, &packetCipher{}, &packetCipher{})
	close(cs.ready)
	c.sess = cs
	return cs
}

// A verified path on a run of losses isn't healthy, though as the last usable path it's kept
// usable: discovery must look for a replacement instead of counting it toward Want.
func TestDiscoveryResumesWhenVerifiedPathFails(t *testing.T) {
	c := gapClient(t, Config{Resolvers: []string{"192.0.2.1:53"}, TCP: "off"})
	c.mu.Lock()
	defer c.mu.Unlock()
	enableDiscovery(c, 1, "192.0.2.2:53")
	now := time.Now()
	p := c.paths[0]
	p.verified = true
	c.discoverLocked(now)
	if len(c.paths) != 1 {
		t.Fatal("a healthy path should be enough")
	}
	for range 6 {
		p.onLoss(now)
	}
	p.suspended = time.Time{} // the last usable path is never suspended
	c.discoverLocked(now)
	if len(c.paths) != 2 {
		t.Fatal("discovery didn't resume with the next candidate")
	}
}

// With more resolvers than maxHelloPaths, hello retries rotate through all of them.
func TestHelloRetriesRotate(t *testing.T) {
	var resolvers []string
	for i := 1; i <= 20; i++ {
		resolvers = append(resolvers, fmt.Sprintf("192.0.2.%d:53", i))
	}
	c := gapClient(t, Config{Resolvers: resolvers, TCP: "off"})
	c.mu.Lock()
	defer c.mu.Unlock()
	// Exercise discovery's batched hello behavior; manual resolver scans use the paced scanner.
	c.cfg.Discovery.Enabled = true
	eph, _ := ecdh.X25519().GenerateKey(rand.Reader)
	cs := &clientSession{eph: eph, ready: make(chan struct{})}
	t0 := time.Now()
	c.sendHellosLocked(cs, t0)
	for _, p := range c.paths {
		p.inflight = 0 // the hellos expired unanswered
	}
	t1 := t0.Add(helloRetry + time.Millisecond)
	c.sendHellosLocked(cs, t1)
	for _, p := range c.paths[16:] {
		if !p.lastHello.Equal(t1) {
			t.Fatalf("resolver %s was never tried", p.addr)
		}
	}
}

func TestManualResolverScannerUsesConcurrencyPaceAndHealthyTarget(t *testing.T) {
	c := gapClient(t, Config{
		Resolvers: []string{"192.0.2.1:53", "192.0.2.2:53", "192.0.2.3:53"},
		Discovery: Discovery{Want: 2, Concurrency: 1, Pace: 10 * time.Millisecond},
		TCP:       "off",
	})
	c.mu.Lock()
	defer c.mu.Unlock()
	eph, _ := ecdh.X25519().GenerateKey(rand.Reader)
	cs := &clientSession{eph: eph, ready: make(chan struct{}), established: true, sid: 1}
	c.sess = cs
	t0 := time.Now()
	c.sendManualHelloLocked(cs, t0)
	if len(c.pending) != 1 {
		t.Fatalf("first manual scan sent %d probes, want 1", len(c.pending))
	}
	c.paths[0].inflight = 0
	c.paths[0].onSuccess(t0.Add(5*time.Millisecond), time.Millisecond, c.cfg.MaxPathWindow)
	c.sendManualHelloLocked(cs, t0.Add(5*time.Millisecond))
	if len(c.pending) != 1 {
		t.Fatal("manual scanner ignored the configured inter-probe delay")
	}
	c.sendManualHelloLocked(cs, t0.Add(10*time.Millisecond))
	if len(c.pending) != 2 {
		t.Fatalf("manual scanner didn't start the next probe after the delay: %d", len(c.pending))
	}
	c.paths[1].inflight = 0
	c.paths[1].onSuccess(t0.Add(15*time.Millisecond), time.Millisecond, c.cfg.MaxPathWindow)
	c.sendManualHelloLocked(cs, t0.Add(20*time.Millisecond))
	if len(c.pending) != 2 {
		t.Fatal("manual scanner continued after reaching the healthy-resolver target")
	}
}

// The tail copy of an upload respects MaxInflight.
func TestTailCopyRespectsMaxInflight(t *testing.T) {
	c := gapClient(t, Config{Resolvers: []string{"192.0.2.1:53", "192.0.2.2:53"}, TCP: "off", MaxInflight: 1})
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, p := range c.paths {
		p.verified = true
	}
	cs := establish(c)
	cs.mu.Lock()
	st, _ := cs.openStream()
	st.sbuf = append(st.sbuf, "\x03a:1GET"...)
	st.spending.add(0, uint64(len(st.sbuf)))
	cs.mu.Unlock()
	c.fillLocked(time.Now())
	if n := len(c.pending); n != 1 {
		t.Fatalf("%d queries in flight with MaxInflight 1", n)
	}
}

// One below maxPending, the request's first copy takes the last slot and the tail copy must
// not go past it.
func TestTailCopyRespectsMaxPending(t *testing.T) {
	c := gapClient(t, Config{Resolvers: []string{"192.0.2.1:53", "192.0.2.2:53"}, TCP: "off"})
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, p := range c.paths {
		p.verified = true
	}
	now := time.Now()
	for txn := range maxPending - 1 {
		c.pending[uint16(txn)] = &pendingQuery{p: c.paths[0], expired: true, deadline: now.Add(time.Minute)}
	}
	cs := establish(c)
	cs.mu.Lock()
	st, _ := cs.openStream()
	st.sbuf = append(st.sbuf, "\x03a:1GET"...)
	st.spending.add(0, uint64(len(st.sbuf)))
	cs.mu.Unlock()
	c.fillLocked(now)
	if n := len(c.pending); n != maxPending {
		t.Fatalf("%d pending, want exactly the %d ceiling", n, maxPending)
	}
}

// MaxInflight is capped, and once the DNS ID space is nearly full nothing more is sent, so
// newTxnLocked always finds a free ID.
func TestDNSIDSpaceStaysBounded(t *testing.T) {
	c := gapClient(t, Config{Resolvers: []string{"192.0.2.1:53"}, TCP: "off", MaxInflight: 1_000_000})
	if c.cfg.MaxInflight != maxInflightCap {
		t.Fatalf("MaxInflight %d, want the cap %d", c.cfg.MaxInflight, maxInflightCap)
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	now := time.Now()
	for txn := range maxPending {
		c.pending[uint16(txn)] = &pendingQuery{p: c.paths[0], expired: true, deadline: now.Add(time.Minute)}
	}
	cs := establish(c)
	cs.mu.Lock()
	st, _ := cs.openStream()
	st.sbuf = append(st.sbuf, "\x03a:1"...)
	st.spending.add(0, uint64(len(st.sbuf)))
	cs.mu.Unlock()
	c.paths[0].verified = true
	c.fillLocked(now)
	if len(c.pending) != maxPending {
		t.Fatal("sent with the DNS ID space at its ceiling")
	}
}

// The run loop wakes when discovery's pace allows the next candidate, not a second later.
func TestDiscoveryPaceSchedulesAWakeup(t *testing.T) {
	c := gapClient(t, Config{Resolvers: []string{"192.0.2.1:53"}, TCP: "off"})
	c.mu.Lock()
	defer c.mu.Unlock()
	enableDiscovery(c, 16, "192.0.2.2:53", "192.0.2.3:53", "192.0.2.4:53")
	now := time.Now()
	c.lastCand = now
	if d := c.nextDeadlineLocked(now); d > 2*c.cfg.Discovery.Pace {
		t.Fatalf("next wakeup in %v, want within the %v pace", d, c.cfg.Discovery.Pace)
	}
}

// MaxStreams is capped so that legitimate streams can't overflow the control queue.
func TestMaxStreamsCapped(t *testing.T) {
	c := gapClient(t, Config{Resolvers: []string{"192.0.2.1:53"}, TCP: "off", MaxStreams: 100_000})
	if c.cfg.MaxStreams != maxStreamsCap {
		t.Fatalf("client MaxStreams %d, want %d", c.cfg.MaxStreams, maxStreamsCap)
	}
	sc := ServerConfig{MaxStreams: 100_000}
	sc.setDefaults()
	if sc.MaxStreams != maxStreamsCap {
		t.Fatalf("server MaxStreams %d, want %d", sc.MaxStreams, maxStreamsCap)
	}
	if 3*maxStreamsCap >= maxCtrlQueue {
		t.Fatal("capped streams could still fill the control queue")
	}
}

// An authenticated answer restarts the liveness clock while other queries are still out, so if
// every path then dies the session is still presumed lost.
func TestLivenessClockRestartsWithQueriesOut(t *testing.T) {
	c := gapClient(t, Config{Resolvers: []string{"192.0.2.1:53", "192.0.2.2:53"}, TCP: "off"})
	c.mu.Lock()
	for _, p := range c.paths {
		p.verified = true
	}
	cs := establish(c)
	cs.mu.Lock()
	st, _ := cs.openStream()
	st.sbuf = append(st.sbuf, "\x03a:1"...)
	st.spending.add(0, uint64(len(st.sbuf)))
	cs.mu.Unlock()
	c.fillLocked(time.Now())
	var q *pendingQuery
	for _, p := range c.pending {
		if !p.hello {
			q = p
			break
		}
	}
	if q == nil || len(c.pending) < 2 {
		c.mu.Unlock()
		t.Fatalf("want two queries out, have %d", len(c.pending))
	}
	// Every path then goes dead, so nothing new is sent to restart the clock.
	for _, p := range c.paths {
		p.suspended = time.Now().Add(time.Hour)
	}
	// The clock started well before the answer, so one left running can be told apart from
	// one the answer restarted.
	cs.unanswered = cs.unanswered.Add(-5 * time.Second)
	started := cs.unanswered
	c.mu.Unlock()
	// The server's answer to one of them, sealed with the session's (zero) keys.
	plain := []byte{0, 0, frPing, 0, 0, 0}
	payload := (&packetCipher{}).sealDown(0, q.pn, plain)
	c.onAnswer(appendAnswer(nil, &query{txn: q.txn, question: q.question, qtype: typeTXT}, rcodeOK, payload))
	c.mu.Lock()
	defer c.mu.Unlock()
	if cs.unanswered.IsZero() {
		t.Fatal("the liveness clock stopped with a query still out")
	}
	if cs.unanswered.Before(started.Add(4 * time.Second)) {
		t.Fatal("the liveness clock didn't restart at the answer")
	}
	c.checkLivenessLocked(started.Add(deadSessionAfter + time.Second))
	if cs.isClosed() {
		t.Fatal("the session was presumed lost counting from before the answer")
	}
	c.checkLivenessLocked(time.Now().Add(deadSessionAfter + time.Second))
	if !cs.isClosed() {
		t.Fatal("the session wasn't presumed lost")
	}
}

// When advertising a new peer stream overflows the control queue and ends the session, the
// slot acceptFn reserved for it is given back exactly once.
func TestPeerStreamReturnsReservedSlotOnOverflow(t *testing.T) {
	cfg := streamConfig{StreamWindow: 1 << 16, SendBuffer: 1 << 16, MaxStreams: 4}
	s := newSession(false, cfg, nil, nil)
	slots := int64(1) // another session's stream
	s.acceptFn = func(st *stream) bool {
		if st == nil {
			slots++
		}
		return true
	}
	s.streamGone = func() { slots-- }
	for len(s.ctrlQ) < maxCtrlQueue {
		s.ctrlQ = append(s.ctrlQ, ctrl{typ: frOpened, id: uint64(100 + len(s.ctrlQ))})
	}
	// A new stream: acceptFn reserves a slot, then its window grant overflows the queue.
	s.receive(0, appendStream(nil, 0, 0, []byte("x"), false, false), time.Now())
	if !s.closed || slots != 1 {
		t.Fatalf("closed=%v slots=%d, want closed with 1 (the other session's)", s.closed, slots)
	}
}

// maxPending holds at every send path: hellos to many resolvers, starting one below the ceiling,
// stop at it.
func TestMaxPendingHoldsForHellos(t *testing.T) {
	var resolvers []string
	for i := 1; i <= 20; i++ {
		resolvers = append(resolvers, fmt.Sprintf("192.0.2.%d:53", i))
	}
	c := gapClient(t, Config{Resolvers: resolvers, TCP: "off"})
	c.mu.Lock()
	defer c.mu.Unlock()
	now := time.Now()
	for txn := range maxPending - 1 {
		c.pending[uint16(txn)] = &pendingQuery{p: c.paths[0], expired: true, deadline: now.Add(time.Minute)}
	}
	eph, _ := ecdh.X25519().GenerateKey(rand.Reader)
	c.sendHellosLocked(&clientSession{eph: eph, ready: make(chan struct{})}, now)
	if len(c.pending) > maxPending {
		t.Fatalf("%d pending, past the %d ceiling", len(c.pending), maxPending)
	}
}

// Dial retires a session idle past SessionIdle, but not a younger one or one with a stream open.
func TestDialRetiresOnlyAnIdleSession(t *testing.T) {
	c := gapClient(t, Config{Resolvers: []string{"192.0.2.1:53"}, TCP: "off", SessionIdle: time.Minute})
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	c.mu.Lock()
	cs := establish(c)
	cs.used = true
	cs.lastAuth = time.Now().Add(-59 * time.Second)
	c.mu.Unlock()
	if got, err := c.session(ctx); err != nil || got != cs {
		t.Fatalf("a session not yet idle was retired: %v", err)
	}

	c.mu.Lock()
	cs.lastAuth = time.Now().Add(-2 * time.Minute)
	cs.mu.Lock()
	cs.openStream()
	cs.mu.Unlock()
	c.mu.Unlock()
	if got, err := c.session(ctx); err != nil || got != cs {
		t.Fatalf("a session with a stream open was retired: %v", err)
	}

	c.mu.Lock()
	cs = establish(c)
	cs.lastAuth = time.Now().Add(-2 * time.Minute)
	c.mu.Unlock()
	c.session(ctx) // nothing answers the new hello, so this times out
	c.mu.Lock()
	defer c.mu.Unlock()
	if !cs.isClosed() || c.sess == cs {
		t.Fatal("the idle session was reused")
	}
}

// A session that never carried a stream is retired after unusedSessionIdle, well before
// SessionIdle, since the server ends it 15 s after its hello.
func TestDialRetiresAnUnusedSessionSooner(t *testing.T) {
	c := gapClient(t, Config{Resolvers: []string{"192.0.2.1:53"}, TCP: "off", SessionIdle: time.Minute})
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	c.mu.Lock()
	cs := establish(c)
	cs.lastAuth = time.Now().Add(-unusedSessionIdle + time.Second)
	c.mu.Unlock()
	if got, err := c.session(ctx); err != nil || got != cs {
		t.Fatalf("an unused session not yet idle was retired: %v", err)
	}

	c.mu.Lock()
	cs.lastAuth = time.Now().Add(-unusedSessionIdle - time.Second)
	c.mu.Unlock()
	c.session(ctx) // nothing answers the new hello, so this times out
	c.mu.Lock()
	defer c.mu.Unlock()
	if !cs.isClosed() || c.sess == cs {
		t.Fatal("an unused session past unusedSessionIdle was reused")
	}
}

// The server times an unused session from its hello, and a retried hello gets the same session
// back, so a handshake whose reply came late is judged from the first hello, not the reply. A
// session that has only just come up is spared: a Dial waiting on it is about to use it.
func TestUnusedSessionAgeCountsFromFirstHello(t *testing.T) {
	c := gapClient(t, Config{Resolvers: []string{"192.0.2.1:53"}, TCP: "off", SessionIdle: time.Minute})
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	c.mu.Lock()
	cs := establish(c)
	cs.firstHello = time.Now().Add(-unusedSessionIdle - time.Second)
	cs.lastAuth = time.Now().Add(-freshSessionGrace / 2)
	c.mu.Unlock()
	if got, err := c.session(ctx); err != nil || got != cs {
		t.Fatalf("a session that just came up was retired: %v", err)
	}

	c.mu.Lock()
	cs.lastAuth = time.Now().Add(-3 * time.Second)
	c.mu.Unlock()
	c.session(ctx) // nothing answers the new hello, so this times out
	c.mu.Lock()
	defer c.mu.Unlock()
	if !cs.isClosed() || c.sess == cs {
		t.Fatal("an unused session whose hello is past the limit was reused")
	}
}

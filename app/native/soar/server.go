package soar

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/netip"
	"os"
	"runtime"
	"slices"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

// ServerConfig configures a tunnel server: the authoritative name server for Zone.
type ServerConfig struct {
	Zone       string
	PrivateKey ed25519.PrivateKey
	// Dial connects streams to their destinations. Default: net.Dialer.
	Dial func(ctx context.Context, network, addr string) (net.Conn, error)
	// MaxHold caps how long an idle poll is held waiting for data. Default 1.5s.
	MaxHold time.Duration
	// LossTimeout declares a downstream packet lost if neither acked nor reported. Default 3s.
	LossTimeout time.Duration
	// IdleTimeout ends sessions that send nothing. Default 2m.
	IdleTimeout time.Duration
	// MaxSessions caps concurrent sessions. Default 8192.
	MaxSessions int
	// MaxTotalStreams caps open streams across all sessions. Default 16384.
	MaxTotalStreams int
	// AllowPorts restricts the default dialer to these destination ports. Default 80 and 443.
	// It doesn't apply to a custom Dial.
	AllowPorts []string
	// MaxBytesPerPrefix caps answer bytes per second sent to one source /24 (IPv4) or /48
	// (IPv6), bounding how much a spoofed-source flood can reflect at a victim. Default 8 MB/s.
	MaxBytesPerPrefix int
	StreamWindow      uint64
	SendBuffer        int
	// MaxStreams caps streams per session. Default 128; at most 256, so stream control frames
	// fit the session's queue.
	MaxStreams int
	Logger     *slog.Logger
}

func (c *ServerConfig) setDefaults() {
	if c.Dial == nil {
		d := &net.Dialer{Timeout: 15 * time.Second, Control: PublicOnly}
		ports := c.AllowPorts
		if len(ports) == 0 {
			ports = []string{"80", "443"}
		}
		c.Dial = func(ctx context.Context, network, addr string) (net.Conn, error) {
			if _, port, err := net.SplitHostPort(addr); err != nil || !slices.Contains(ports, port) {
				return nil, fmt.Errorf("soar: destination port not allowed: %s", addr)
			}
			return d.DialContext(ctx, network, addr)
		}
	}
	if c.MaxBytesPerPrefix <= 0 {
		c.MaxBytesPerPrefix = 8 << 20
	}
	if c.MaxSessions <= 0 || c.MaxSessions > 1<<15 {
		c.MaxSessions = 8192
	}
	if c.MaxTotalStreams <= 0 {
		c.MaxTotalStreams = 16384
	}
	if c.MaxHold <= 0 {
		c.MaxHold = 1500 * time.Millisecond
	}
	if c.LossTimeout <= 0 {
		c.LossTimeout = 3 * time.Second
	}
	if c.IdleTimeout <= 0 {
		c.IdleTimeout = 2 * time.Minute
	}
	// Uploads are small; a modest receive window bounds what one session can make us buffer.
	if c.StreamWindow == 0 {
		c.StreamWindow = 64 << 10
	}
	if c.SendBuffer <= 0 {
		c.SendBuffer = 256 << 10
	}
	if c.MaxStreams <= 0 {
		c.MaxStreams = 128
	}
	c.MaxStreams = min(c.MaxStreams, maxStreamsCap)
	if c.Logger == nil {
		c.Logger = slog.New(slog.DiscardHandler)
	}
}

// Server is a running tunnel server.
type Server struct {
	cfg ServerConfig
	z   zone
	pc  net.PacketConn
	ctx context.Context

	mu       sync.Mutex
	sessions map[uint16]*srvSession
	hellos   map[[pubLen]byte]*helloEntry
	streams  atomic.Int64

	signTokens tokenBucket
	helloQ     chan helloJob

	rrlMu       sync.Mutex
	rrl         map[netip.Prefix]*byteBucket
	rrlOverflow *byteBucket
}

type helloEntry struct {
	reply []byte
	at    time.Time
	sid   uint16
}

type srvSession struct {
	*session
	srv       *Server
	sid       uint16
	created   time.Time
	used      atomic.Bool  // a valid data packet arrived: the hello wasn't just noise
	held      []*heldQuery // guarded by session.mu
	lastSeen  time.Time    // guarded by session.mu
	abandoned rangeSet     // upstream pns the client gave up on; guarded by session.mu
}

type heldQuery struct {
	q        *query
	addr     net.Addr
	upPN     uint64
	limit    int
	at       time.Time
	answered bool
	timer    *time.Timer // its hold expiry; stopped when answered early
}

// finish marks hq answered and stops its hold timer. Caller holds ss.mu.
func (hq *heldQuery) finish() {
	hq.answered = true
	if hq.timer != nil {
		hq.timer.Stop()
	}
}

// Serve runs a server on pc until ctx ends.
func Serve(ctx context.Context, pc net.PacketConn, cfg ServerConfig) error {
	// Workers, sweeps and sessions end whenever Serve returns, not only when the caller cancels.
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	cfg.setDefaults()
	if len(cfg.PrivateKey) != ed25519.PrivateKeySize {
		return errors.New("soar: server needs an Ed25519 private key")
	}
	z, err := parseZone(cfg.Zone)
	if err != nil {
		return err
	}
	s := &Server{cfg: cfg, z: z, pc: pc, ctx: ctx, signTokens: tokenBucket{rate: 200, tokens: 200, last: time.Now()},
		helloQ:   make(chan helloJob, 256),
		rrl:      make(map[netip.Prefix]*byteBucket),
		sessions: make(map[uint16]*srvSession), hellos: make(map[[pubLen]byte]*helloEntry)}
	go func() {
		<-ctx.Done()
		pc.Close()
	}()
	go s.sweep()
	// Handshake workers get at most half the cores, so a hello flood can't take the CPU that
	// established sessions' relays need; past that, the bounded queue sheds new hellos.
	for range max(1, runtime.GOMAXPROCS(0)/2) {
		go s.helloWorker()
	}
	buf := make([]byte, 4096)
	for {
		n, addr, err := pc.ReadFrom(buf)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				continue
			}
			return err
		}
		// Held queries outlive this read: give each message its own copy.
		s.handle(append([]byte(nil), buf[:n]...), addr)
	}
}

func (s *Server) reply(q *query, addr net.Addr, rcode byte, payload []byte) {
	s.write(appendAnswer(make([]byte, 0, 512), q, rcode, payload), addr, q.size)
}

func (s *Server) handle(msg []byte, addr net.Addr) {
	q, err := parseQuery(msg, s.z)
	if err != nil {
		return
	}
	if !q.inZone {
		s.reply(q, addr, rcodeRefused, nil)
		return
	}
	if q.payload == nil || (q.qtype != typeTXT && q.qtype != typeAAAA) || len(q.payload) < 2 {
		s.noData(q, addr)
		return
	}
	p := q.payload
	sid := binary.BigEndian.Uint16(p)
	if sid == 0 {
		s.hello(q, addr, p[2:])
		return
	}
	s.mu.Lock()
	ss := s.sessions[sid]
	s.mu.Unlock()
	if ss == nil {
		s.replyUnknown(q, addr)
		return
	}
	if len(p) < 4+minPlain+tagLen {
		s.noData(q, addr)
		return
	}
	ss.onQuery(q, addr, p)
}

// noData answers an in-zone query that carries no usable tunnel packet (see appendNoData).
func (s *Server) noData(q *query, addr net.Addr) {
	s.write(appendNoData(make([]byte, 0, 256), q, s.z), addr, q.size)
}

// replyUnknown tells the client its session is gone, signed over the question so an injector
// can't forge it. Signing is rate-limited, so random session ids can't make us burn CPU; past the
// limit the reply goes unsigned and the client ignores it.
func (s *Server) replyUnknown(q *query, addr net.Addr) {
	if !s.signTokens.take(time.Now()) {
		s.noData(q, addr) // an unsigned "unknown session" would be ignored anyway
		return
	}
	s.reply(q, addr, rcodeNXDomain, ed25519.Sign(s.cfg.PrivateKey, unknownSessionMsg(q.question)))
}

// tokenBucket is a mutex-guarded rate limiter: rate per second, burst of rate.
type tokenBucket struct {
	mu     sync.Mutex
	rate   float64
	tokens float64
	last   time.Time
}

func (b *tokenBucket) take(now time.Time) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.tokens = min(b.rate, b.tokens+now.Sub(b.last).Seconds()*b.rate)
	b.last = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

type helloJob struct {
	q    *query
	addr net.Addr
	key  [pubLen]byte
}

// hello answers a retransmitted hello from the cache at once and hands a new one to the
// handshake workers. The crypto (key generation, ECDH, signature) runs there, outside s.mu, so a
// flood of unique hellos can neither block the receive loop nor stall established sessions; when
// the workers fall behind, the queue drops new hellos (clients retry) instead of growing.
func (s *Server) hello(q *query, addr net.Addr, eph []byte) {
	if len(eph) < pubLen {
		s.noData(q, addr)
		return
	}
	j := helloJob{q: q, addr: addr}
	copy(j.key[:], eph)
	s.mu.Lock()
	h := s.hellos[j.key]
	s.mu.Unlock()
	// Resolvers retry and clients send the hello down several paths: answer each copy alike.
	if h != nil {
		s.reply(q, addr, rcodeOK, h.reply)
		return
	}
	select {
	case s.helloQ <- j:
	default:
		s.reply(q, addr, rcodeRefused, nil)
	}
}

func (s *Server) helloWorker() {
	for {
		select {
		case <-s.ctx.Done():
			return
		case j := <-s.helloQ:
			s.handshake(j)
		}
	}
}

func (s *Server) handshake(j helloJob) {
	q, addr := j.q, j.addr
	var sid uint16
	var reply []byte
	var up, down *packetCipher
	for attempt := 0; ; attempt++ {
		var b [2]byte
		rand.Read(b[:])
		if sid = binary.BigEndian.Uint16(b[:]); sid == 0 {
			continue
		}
		var err error
		if reply, up, down, err = serverHello(s.cfg.PrivateKey, j.key[:], sid); err != nil {
			s.noData(q, addr)
			return
		}
		s.mu.Lock()
		if h := s.hellos[j.key]; h != nil { // another worker finished this hello first
			s.mu.Unlock()
			s.reply(q, addr, rcodeOK, h.reply)
			return
		}
		if len(s.sessions) >= s.cfg.MaxSessions && !s.evictUnusedLocked() {
			s.mu.Unlock()
			s.reply(q, addr, rcodeRefused, nil)
			return
		}
		if s.sessions[sid] == nil {
			break // s.mu held
		}
		s.mu.Unlock() // the sid is taken (the transcript covers it): pick another
		if attempt > 8 {
			s.reply(q, addr, rcodeRefused, nil)
			return
		}
	}
	key := j.key
	cfg := streamConfig{StreamWindow: s.cfg.StreamWindow, SendBuffer: s.cfg.SendBuffer, MaxStreams: s.cfg.MaxStreams}
	ss := &srvSession{session: newSession(false, cfg, down, up), srv: s, sid: sid, lastSeen: time.Now(), created: time.Now()}
	ss.wakeFn = ss.flush
	// Streams count against MaxTotalStreams for as long as the session holds them (buffered,
	// unacked data included), not just while their relay runs.
	ss.acceptFn = func(st *stream) bool {
		if st == nil { // reserve a slot atomically; the stream that follows uses it
			for {
				n := s.streams.Load()
				if n >= int64(s.cfg.MaxTotalStreams) {
					return false
				}
				if s.streams.CompareAndSwap(n, n+1) {
					return true
				}
			}
		}
		st.serving = true // keeps it in the session, and counted, while its dial and relay run
		go func() {
			ss.serveStream(st)
			ss.mu.Lock()
			st.serving = false
			ss.maybeRemove(st)
			ss.mu.Unlock()
		}()
		return true
	}
	ss.streamGone = func() { s.streams.Add(-1) }
	// The client gave up on a query we haven't answered: don't spend data on it, since an answer
	// lost now would never be reported.
	ss.onLostUnknown = func(up uint64) {
		for i, h := range ss.held {
			if h.upPN == up {
				h.finish()
				ss.held = append(ss.held[:i], ss.held[i+1:]...)
				break
			}
		}
		ss.abandoned.add(up, up+1)
		if len(ss.abandoned) > 256 {
			ss.abandoned = ss.abandoned[len(ss.abandoned)-256:]
		}
	}
	s.sessions[sid] = ss
	// Bounded by MaxSessions: entries go with their session (see evictUnusedLocked and sweep).
	s.hellos[key] = &helloEntry{reply: reply, at: time.Now(), sid: sid}
	s.mu.Unlock()
	s.reply(q, addr, rcodeOK, reply) // never write to the network holding s.mu
}

// evictUnusedLocked drops the oldest session that never sent a valid data packet, so a flood of
// hellos can't lock real clients out. Caller holds s.mu.
func (s *Server) evictUnusedLocked() bool {
	var victim *srvSession
	for _, ss := range s.sessions {
		if !ss.used.Load() && (victim == nil || ss.created.Before(victim.created)) {
			victim = ss
		}
	}
	if victim == nil {
		return false
	}
	delete(s.sessions, victim.sid)
	s.forgetHelloLocked(victim.sid)
	go victim.close(ErrClosed)
	return true
}

// forgetHelloLocked drops the cached hello reply for sid. Caller holds s.mu.
func (s *Server) forgetHelloLocked(sid uint16) {
	for k, h := range s.hellos {
		if h.sid == sid {
			delete(s.hellos, k)
			return
		}
	}
}

// onQuery handles a data query: it applies the client's packet, then answers now or holds the
// query until there's data to send.
func (ss *srvSession) onQuery(q *query, addr net.Addr, p []byte) {
	now := time.Now()
	ss.mu.Lock()
	pn, plain, err := ss.open.openUp(p, func(trunc uint16) uint64 {
		return decodePN(ss.largestRecv, ss.haveRecv, trunc)
	})
	if err != nil || len(plain) < 1 {
		ss.mu.Unlock()
		ss.srv.noData(q, addr)
		return
	}
	dbg("srv q pn=%d plain=%x", pn, plain)
	limitClass, holdClass := int(plain[0]&0x0F), int(plain[0]>>4)
	ss.rxData = false
	fresh, err := ss.receive(pn, plain[1:], now)
	if err != nil {
		ss.closeLocked(err)
	}
	// Only a new packet is activity: a replayed one mustn't keep the session alive or mark a
	// hello-only session used. (Duplicates, e.g. resolver retries, are still answered.)
	if fresh {
		ss.lastSeen = now
		ss.used.Store(true)
	}
	hq := &heldQuery{q: q, addr: addr, upPN: pn, limit: min(512+48*limitClass, q.edns), at: now}
	hold := min(time.Duration(holdClass)*100*time.Millisecond, ss.srv.cfg.MaxHold)
	var out []sendItem
	if ss.abandoned.contains(pn) {
		out = append(out, ss.emptyAnswerLocked(hq, now))
	} else if ss.closed || hold == 0 || ss.rxData {
		out = append(out, ss.answerLocked(hq, now))
	} else {
		// A poll joins the queue even when there's data to send: the drain below answers the
		// queue oldest first, at once. Releasing polls in arrival order is what lets the client
		// tell a lost poll from a held one; answering this one first would leave older polls
		// looking lost.
		ss.held = append(ss.held, hq)
		hq.timer = time.AfterFunc(hold, func() { ss.expire(hq) })
		// Bound held queries per session; answer the oldest.
		for len(ss.held) > 128 {
			out = append(out, ss.answerLocked(ss.held[0], now))
		}
	}
	out = append(out, ss.drainLocked(now)...)
	ss.mu.Unlock()
	ss.srv.send(out)
}

type sendItem struct {
	msg   []byte
	addr  net.Addr
	qsize int // size of the query being answered
}

func (s *Server) send(items []sendItem) {
	for _, it := range items {
		s.write(it.msg, it.addr, it.qsize)
	}
}

// write sends one answer if its destination prefix is within its amplification budget. A
// session's queries can carry a spoofed source and answers outweigh queries, so this bounds what
// the server reflects at any one network. Only the amplified bytes (answer minus query) count:
// error replies and small answers cost nothing, so spoofing a busy resolver's prefix with cheap
// queries can't drain the budget its real users depend on.
func (s *Server) write(msg []byte, addr net.Addr, qsize int) {
	if amp := len(msg) - qsize; amp > 0 {
		if ua, ok := addr.(*net.UDPAddr); ok && !s.allowAmplification(ua, amp) {
			return
		}
	}
	s.pc.WriteTo(msg, addr)
}

// maxRRLPrefixes bounds the per-prefix table; prefixes beyond it share one overflow budget, so a
// flood of spoofed sources can neither grow memory nor escape the limit.
const maxRRLPrefixes = 1 << 16

func (s *Server) allowAmplification(ua *net.UDPAddr, n int) bool {
	ip := ua.AddrPort().Addr().Unmap()
	bits := 24
	if ip.Is6() {
		bits = 48
	}
	key, _ := ip.Prefix(bits)
	now := time.Now()
	rate := float64(s.cfg.MaxBytesPerPrefix)
	s.rrlMu.Lock()
	defer s.rrlMu.Unlock()
	b := s.rrl[key]
	if b == nil {
		if len(s.rrl) >= maxRRLPrefixes {
			if s.rrlOverflow == nil {
				s.rrlOverflow = &byteBucket{rate: rate, burst: rate / 4, tokens: rate / 4, last: now}
			}
			return s.rrlOverflow.take(now, n)
		}
		b = &byteBucket{rate: rate, burst: rate / 4, tokens: rate / 4, last: now}
		s.rrl[key] = b
	}
	return b.take(now, n)
}

// byteBucket is a byte-rate limiter. Caller serializes access.
type byteBucket struct {
	rate, burst, tokens float64
	last                time.Time
}

func (b *byteBucket) take(now time.Time, n int) bool {
	b.tokens = min(b.burst, b.tokens+now.Sub(b.last).Seconds()*b.rate)
	b.last = now
	if b.tokens < float64(n) {
		return false
	}
	b.tokens -= float64(n)
	return true
}

// answerLocked builds the answer to hq and removes it from the held list. Caller holds ss.mu.
func (ss *srvSession) answerLocked(hq *heldQuery, now time.Time) sendItem {
	it, _ := ss.answerPktLocked(hq, now)
	return it
}

func (ss *srvSession) answerPktLocked(hq *heldQuery, now time.Time) (sendItem, *sentPkt) {
	hq.finish()
	for i, h := range ss.held {
		if h == hq {
			ss.held = append(ss.held[:i], ss.held[i+1:]...)
			break
		}
	}
	// Plaintext: hold applied (10ms units), backlog (packets queued), frames.
	budget := capacity(hq.q, hq.limit) - 2 - tagLen
	held := min(now.Sub(hq.at)/(10*time.Millisecond), 255)
	plain := []byte{byte(held), 0}
	pn, plain, _, pkt := ss.build(plain, budget-2, now, hq.upPN)
	if budget > 0 {
		plain[1] = byte(min(ss.pendingBytes()/budget, 255))
	}
	dbg("srv answer pn=%d up=%d plain=%x", pn, hq.upPN, plain)
	payload := ss.seal.sealDown(pn, hq.upPN, plain)
	return sendItem{msg: appendAnswer(make([]byte, 0, hq.limit), hq.q, rcodeOK, payload), addr: hq.addr, qsize: hq.q.size}, pkt
}

// emptyAnswerLocked answers hq with a packet carrying no frames.
func (ss *srvSession) emptyAnswerLocked(hq *heldQuery, now time.Time) sendItem {
	hq.finish()
	pn := ss.nextPN
	ss.nextPN++
	payload := ss.seal.sealDown(pn, hq.upPN, make([]byte, 2+minPlain))
	return sendItem{msg: appendAnswer(make([]byte, 0, 128), hq.q, rcodeOK, payload), addr: hq.addr, qsize: hq.q.size}
}

// drainLocked answers held queries, oldest first, while there's data. Caller holds ss.mu.
func (ss *srvSession) drainLocked(now time.Time) []sendItem {
	var out []sendItem
	for len(ss.held) > 0 && ss.hasData() {
		it, pkt := ss.answerPktLocked(ss.held[0], now)
		out = append(out, it)
		// The queue just drained: send the tail again in another held poll.
		if pkt != nil && len(pkt.streams) > 0 && !ss.hasData() && len(ss.held) > 0 && ss.requeue(pkt) {
			it, _ := ss.answerPktLocked(ss.held[0], now)
			out = append(out, it)
		}
	}
	return out
}

func (ss *srvSession) flush() {
	ss.mu.Lock()
	out := ss.drainLocked(time.Now())
	ss.mu.Unlock()
	ss.srv.send(out)
}

func (ss *srvSession) expire(hq *heldQuery) {
	ss.mu.Lock()
	if hq.answered {
		ss.mu.Unlock()
		return
	}
	it := ss.answerLocked(hq, time.Now())
	ss.mu.Unlock()
	ss.srv.send([]sendItem{it})
}

// serveStream reads the destination from the stream's first bytes, dials it, and relays.
func (ss *srvSession) serveStream(st *stream) {
	c := &Conn{st: st}
	c.SetReadDeadline(time.Now().Add(30 * time.Second))
	addr, err := readAddr(c)
	dbg("srv stream %d addr=%q err=%v", st.id, addr, err)
	c.SetReadDeadline(time.Time{})
	if err != nil {
		// No usable destination (malformed, or none in 30 s): refuse the stream as unreachable.
		// A graceful close ends in a FIN and then a "stop sending" RESET, which tells a client
		// waiting for OPENED nothing, so it would wait forever.
		ss.mu.Lock()
		ss.queueCtrl(ctrl{typ: frReset, id: st.id, val: 1})
		st.abort(errors.New("soar: bad destination"))
		ss.maybeRemove(st)
		ss.mu.Unlock()
		ss.flush()
		return
	}
	// A stream the client aborted is never dialed, and an abort cancels a pending dial. A RESET
	// after the client's FIN is not an abort: it means "I won't read the reply", so the upload is
	// still delivered. Either way the stream stays counted until serveStream returns.
	ss.mu.Lock()
	gone := st.reset || st.rclosed
	ss.mu.Unlock()
	if gone {
		c.Close()
		return
	}
	ctx, cancel := context.WithTimeout(ss.srv.ctx, 30*time.Second)
	go func() {
		select {
		case <-st.aborted:
			cancel()
		case <-ctx.Done():
		}
	}()
	up, err := ss.srv.cfg.Dial(ctx, "tcp", addr)
	cancel()
	if err == nil {
		// Tell the client the destination is reachable: a caller that must know a connection
		// is up before sending (kindling's connect phase) waits for this.
		ss.mu.Lock()
		ss.queueCtrl(ctrl{typ: frOpened, id: st.id})
		ss.mu.Unlock()
		ss.flush()
	}
	if err != nil {
		ss.srv.cfg.Logger.Debug("dial failed", "addr", addr, "err", err)
		ss.mu.Lock()
		ss.queueCtrl(ctrl{typ: frReset, id: st.id, val: 1})
		st.abort(err)
		ss.maybeRemove(st)
		ss.mu.Unlock()
		ss.flush()
		return
	}
	relay(c, up)
}

// relay copies both ways and closes both ends when done.
// relay copies both ways and closes both ends when done. A clean EOF half-closes the other side;
// any error (a reset stream, a closed session, a failed destination) closes the destination so
// the opposite copy can't stay blocked on it.
func relay(c *Conn, up net.Conn) {
	// Close the destination when the stream is aborted or the session ends, or, once the client
	// stops reading (the stream dies without an abort), as soon as the upload has been delivered:
	// after a client FIN nothing else would unblock a download copy reading the destination.
	finished := make(chan struct{})
	defer close(finished)
	done := make(chan struct{})
	go func() {
		select {
		case <-c.st.aborted:
		case <-c.st.s.done:
		case <-finished:
		case <-c.st.dead:
			select {
			case <-done:
			case <-c.st.aborted:
			case <-c.st.s.done:
			case <-finished:
			}
		}
		up.Close()
	}()
	go func() {
		if _, err := io.Copy(up, c); err != nil {
			up.Close()
		} else if cw, ok := up.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		}
		close(done)
	}()
	if _, err := io.Copy(c, up); err != nil {
		up.Close()
		c.Close()
	} else {
		c.CloseWrite()
	}
	<-done
	up.Close()
	c.Close()
}

// PublicOnly knows the well-known NAT64 prefix (64:ff9b::/96) but not a network-specific Pref64
// (RFC 6052): on a host behind NAT64 with its own prefix, wrap it with a check for that prefix.
//
// PublicOnly is a net.Dialer Control function that refuses non-public destinations: loopback,
// private, link-local, CGNAT, reserved, and IPv6 forms that embed one (NAT64, 6to4, Teredo,
// IPv4-compatible). Anyone holding the (public) server key can open streams, so the server must
// not become a way into its own network. It checks the address actually dialed, after
// resolution, so DNS rebinding can't get around it. The default dialer uses it; a custom
// ServerConfig.Dial should too.
func PublicOnly(network, address string, _ syscall.RawConn) error {
	ap, err := netip.ParseAddrPort(address)
	if err != nil {
		return err
	}
	a := ap.Addr()
	// A zone survives Unmap and defeats prefix and own-address matching; a public destination
	// never needs one.
	if a.Zone() != "" {
		return fmt.Errorf("soar: destination %s has a zone", a)
	}
	if v4, ok := embeddedV4(a); ok && isLocal(v4) {
		return fmt.Errorf("soar: destination %s is not public", a)
	}
	if !isPublic(a) || isLocal(a) {
		return fmt.Errorf("soar: destination %s is not public", a)
	}
	return nil
}

// embeddedV4 returns the IPv4 destination a NAT64 or 6to4 address translates to.
func embeddedV4(a netip.Addr) (netip.Addr, bool) {
	a = a.Unmap()
	if !a.Is6() {
		return netip.Addr{}, false
	}
	b := a.As16()
	switch {
	case nat64.Contains(a):
		return netip.AddrFrom4([4]byte(b[12:16])), true
	case sixToFour.Contains(a):
		return netip.AddrFrom4([4]byte(b[2:6])), true
	}
	return netip.Addr{}, false
}

var (
	nat64     = netip.MustParsePrefix("64:ff9b::/96")
	sixToFour = netip.MustParsePrefix("2002::/16")
)

// localAddrs caches this host's interface addresses: dialing our own public IP would reach
// services bound to all interfaces from inside, past firewalls that only filter outside traffic.
var localAddrs struct {
	sync.Mutex
	set map[netip.Addr]bool
	at  time.Time
}

func isLocal(a netip.Addr) bool {
	a = a.Unmap()
	localAddrs.Lock()
	defer localAddrs.Unlock()
	if localAddrs.set == nil || time.Since(localAddrs.at) > time.Minute {
		// On failure keep the last good set; with none at all, fail closed.
		if addrs, err := net.InterfaceAddrs(); err == nil {
			set := make(map[netip.Addr]bool)
			for _, ia := range addrs {
				if pfx, err := netip.ParsePrefix(ia.String()); err == nil {
					set[pfx.Addr().Unmap()] = true
				}
			}
			localAddrs.set, localAddrs.at = set, time.Now()
		} else if localAddrs.set == nil {
			return true
		}
	}
	return localAddrs.set[a]
}

var nonPublic = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"),       // "this network"
	netip.MustParsePrefix("100.64.0.0/10"),   // CGNAT
	netip.MustParsePrefix("192.0.0.0/24"),    // IETF protocol assignments
	netip.MustParsePrefix("198.18.0.0/15"),   // benchmarking
	netip.MustParsePrefix("192.88.99.0/24"),  // 6to4 relay anycast (RFC 7526)
	netip.MustParsePrefix("192.0.2.0/24"),    // TEST-NET-1 (RFC 5737)
	netip.MustParsePrefix("198.51.100.0/24"), // TEST-NET-2
	netip.MustParsePrefix("203.0.113.0/24"),  // TEST-NET-3
	netip.MustParsePrefix("240.0.0.0/4"),     // reserved
	netip.MustParsePrefix("::/96"),           // IPv4-compatible (deprecated)
	netip.MustParsePrefix("64:ff9b:1::/48"),  // local-use NAT64
	netip.MustParsePrefix("2001::/32"),       // Teredo: tunnels to arbitrary IPv4
	netip.MustParsePrefix("fec0::/10"),       // site-local (deprecated)
	netip.MustParsePrefix("::ffff:0:0:0/96"), // IPv4-translated (SIIT)
	netip.MustParsePrefix("100::/64"),        // discard-only
	netip.MustParsePrefix("2001:db8::/32"),   // documentation
	netip.MustParsePrefix("2001:2::/48"),     // benchmarking
	netip.MustParsePrefix("2001:10::/28"),    // ORCHID
	netip.MustParsePrefix("2001:20::/28"),    // ORCHIDv2
	netip.MustParsePrefix("3fff::/20"),       // documentation (RFC 9637)
}

func isPublic(a netip.Addr) bool {
	a = a.Unmap()
	if !a.IsGlobalUnicast() || a.IsPrivate() {
		return false
	}
	for _, p := range nonPublic {
		if p.Contains(a) {
			return false
		}
	}
	// NAT64 and 6to4 embed an IPv4 destination: judge that.
	if v4, ok := embeddedV4(a); ok {
		return isPublic(v4)
	}
	return true
}

// readAddr reads a varint length and that many address bytes.
func readAddr(r io.Reader) (string, error) {
	var b [1]byte
	if _, err := io.ReadFull(r, b[:]); err != nil {
		return "", err
	}
	n := int(b[0])
	if n == 0 || n > 255 {
		return "", errors.New("soar: bad destination")
	}
	addr := make([]byte, n)
	if _, err := io.ReadFull(r, addr); err != nil {
		return "", err
	}
	return string(addr), nil
}

// sweep declares stale downstream packets lost and ends idle sessions.
func (s *Server) sweep() {
	t := time.NewTicker(250 * time.Millisecond)
	defer t.Stop()
	for {
		select {
		case <-s.ctx.Done():
			s.mu.Lock()
			for _, ss := range s.sessions {
				ss.close(ErrClosed)
			}
			s.mu.Unlock()
			return
		case now := <-t.C:
			s.mu.Lock()
			for k, h := range s.hellos {
				if now.Sub(h.at) > time.Minute || s.sessions[h.sid] == nil {
					delete(s.hellos, k)
				}
			}
			s.rrlMu.Lock()
			for k, b := range s.rrl {
				if now.Sub(b.last) > 30*time.Second {
					delete(s.rrl, k)
				}
			}
			s.rrlMu.Unlock()
			sessions := make([]*srvSession, 0, len(s.sessions))
			for _, ss := range s.sessions {
				sessions = append(sessions, ss)
			}
			s.mu.Unlock()
			for _, ss := range sessions {
				ss.mu.Lock()
				for pn, p := range ss.sent {
					if now.Sub(p.at) > s.cfg.LossTimeout {
						dbg("srv TIMEOUT-LOSS down=%d up=%d streams=%d", pn, p.replyTo, len(p.streams))
						ss.declareLost(pn)
					}
				}
				idle := now.Sub(ss.lastSeen) > s.cfg.IdleTimeout || ss.closed ||
					(!ss.used.Load() && now.Sub(ss.created) > 15*time.Second)
				out := ss.drainLocked(now)
				if idle {
					// Close under the same lock the decision was made under, so a query that
					// arrives meanwhile can't revive a session we then close.
					ss.closeLocked(ErrClosed)
				}
				ss.mu.Unlock()
				s.send(out)
				if idle {
					s.mu.Lock()
					// The sid may have been evicted and reused meanwhile: only remove this session.
					if s.sessions[ss.sid] == ss {
						delete(s.sessions, ss.sid)
						s.forgetHelloLocked(ss.sid)
					}
					s.mu.Unlock()
				}
			}
		}
	}
}

var debugOn = os.Getenv("SOAR_DEBUG") != ""

func dbg(f string, a ...any) {
	if debugOn {
		fmt.Fprintf(os.Stderr, time.Now().Format("05.000 ")+f+"\n", a...)
	}
}

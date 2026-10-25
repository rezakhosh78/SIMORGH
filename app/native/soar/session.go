package soar

import (
	"errors"
	"fmt"
	"slices"
	"sync"
	"time"
)

// streamConfig bounds per-stream memory.
type streamConfig struct {
	StreamWindow uint64 // receive window per stream
	SendBuffer   int    // bytes the app may buffer per stream
	MaxStreams   int
}

// ctrl is a retransmittable control frame.
type ctrl struct {
	typ byte
	id  uint64
	val uint64
}

func (c ctrl) appendTo(b []byte) []byte {
	b = append(b, c.typ)
	switch c.typ {
	case frClose:
		return appendVarint(b, c.val)
	default:
		b = appendVarint(b, c.id)
		return appendVarint(b, c.val)
	}
}

type sentRange struct {
	id     uint64
	lo, hi uint64
	fin    bool
}

type sentPkt struct {
	pn      uint64
	at      time.Time
	streams []sentRange
	ctrls   []ctrl
	ack     rangeSet // ack ranges this packet conveyed
	lost    []uint64 // lost pns this packet reported
	replyTo uint64   // server: the upstream pn this packet answered
}

// session is the transport-independent half of a tunnel endpoint: streams, packet numbers,
// acknowledgement and retransmission. The client and server drive it with their own carriers.
type session struct {
	mu     sync.Mutex
	cfg    streamConfig
	client bool
	seal   *packetCipher
	open   *packetCipher

	nextPN uint64
	sent   map[uint64]*sentPkt
	ackRTT time.Duration // server: smoothed time from sending a packet to its ack

	recvd       rangeSet // peer pns received, for dedup (trimmed below a window)
	largestRecv uint64
	haveRecv    bool
	toAck       rangeSet // client: downstream pns the server hasn't been told about
	lostQ       []uint64 // client: upstream pns to report lost
	replyOf     map[uint64]uint64
	rxData      bool // the last received packet carried stream or control frames

	streams      map[uint64]*stream
	peerNext     uint64   // server: one past the highest stream id the client has used
	peerGaps     rangeSet // server: ids below peerNext not yet opened (reordered opens)
	nextStreamID uint64
	rr           []uint64 // stream ids in round-robin order
	ctrlQ        []ctrl

	closed   bool
	closeErr error
	done     chan struct{}

	wakeFn   func()
	acceptFn func(*stream) bool // server: a new stream arrived; false refuses it
	// streamGone: a stream the peer opened left the session.
	streamGone func()
	// onLostUnknown: the client reported an upstream pn lost that has no recorded reply.
	onLostUnknown func(up uint64)
}

func newSession(client bool, cfg streamConfig, seal, open *packetCipher) *session {
	return &session{
		cfg: cfg, client: client, seal: seal, open: open,
		sent: make(map[uint64]*sentPkt), replyOf: make(map[uint64]uint64),
		streams: make(map[uint64]*stream), done: make(chan struct{}),
	}
}

func (s *session) wake() {
	if s.wakeFn != nil {
		s.wakeFn()
	}
}

// queueCtrl queues a control frame. Caller holds s.mu.
func (s *session) queueCtrl(c ctrl) {
	if c.typ == frMaxStream {
		for i, q := range s.ctrlQ {
			if q.typ == frMaxStream && q.id == c.id {
				s.ctrlQ[i].val = max(q.val, c.val)
				return
			}
		}
	}
	// The same frame already waiting (a second RESET or OPENED for a stream) adds nothing.
	if slices.Contains(s.ctrlQ, c) {
		return
	}
	// Callers update their state as if the frame will go out (a read raises rmax before its
	// MAX_STREAM_DATA), so dropping one would stall a stream for good. Legitimate traffic stays
	// far below the cap: grants coalesce per stream, and each stream has one OPENED and one RESET
	// at most. Only a misbehaving peer fills it (say, opening stream after stream past the limit,
	// each refused with a RESET), so end the session.
	if len(s.ctrlQ) >= maxCtrlQueue {
		s.closeLocked(errors.New("soar: control frame queue overflow"))
		return
	}
	s.ctrlQ = append(s.ctrlQ, c)
}

const (
	maxCtrlQueue = 1024
	// maxStreamsCap caps MaxStreams, client and server alike. A stream queues at most three
	// control frames (a window grant, OPENED, RESET), so this many streams fit the control
	// queue with room to spare, and only a misbehaving peer can overflow it.
	maxStreamsCap = maxCtrlQueue / 4
	replayWindow  = 1 << 14
)

// openStream creates a client stream. Caller holds s.mu.
func (s *session) openStream() (*stream, error) {
	if s.closed {
		return nil, s.closeErr
	}
	if len(s.streams) >= s.cfg.MaxStreams {
		return nil, errors.New("soar: too many streams")
	}
	st := newStream(s, s.nextStreamID)
	s.nextStreamID++
	st.advertise()
	// Advertising can overflow the control queue and end the session.
	if s.closed {
		return nil, s.closeErr
	}
	s.streams[st.id] = st
	s.rr = append(s.rr, st.id)
	return st, nil
}

// maybeRemove forgets a stream once both directions are finished. Caller holds s.mu.
func (s *session) maybeRemove(st *stream) {
	if _, ok := s.streams[st.id]; !ok || st.serving || !st.doneSending() || !st.doneReceiving() {
		return
	}
	if st.rclosed && !st.rfin && !st.reset {
		// We stopped reading before the peer finished: tell it to stop sending.
		s.queueCtrl(ctrl{typ: frReset, id: st.id})
		// That can overflow the control queue and end the session, which already removed
		// every stream and gave back its slot: don't give this one back twice.
		if _, ok := s.streams[st.id]; !ok {
			return
		}
	}
	delete(s.streams, st.id)
	if !s.client && s.streamGone != nil {
		s.streamGone()
	}
	if i := slices.Index(s.rr, st.id); i >= 0 {
		s.rr = slices.Delete(s.rr, i, i+1)
	}
}

func (s *session) close(err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closeLocked(err)
}

func (s *session) closeLocked(err error) {
	if s.closed {
		return
	}
	s.closed, s.closeErr = true, err
	for id, st := range s.streams {
		st.abort(err)
		delete(s.streams, id)
		if !s.client && s.streamGone != nil {
			s.streamGone()
		}
	}
	close(s.done)
}

// decodePN expands a truncated 16-bit packet number (RFC 9000 §A.3).
func decodePN(largest uint64, have bool, trunc uint16) uint64 {
	expected := uint64(0)
	if have {
		expected = largest + 1
	}
	const win, hwin, mask = 1 << 16, 1 << 15, 1<<16 - 1
	cand := expected&^mask | uint64(trunc)
	switch {
	case cand+hwin <= expected && cand < 1<<62-win:
		return cand + win
	case cand > expected+hwin && cand >= win:
		return cand - win
	}
	return cand
}

// receive processes an authenticated packet's frames. It reports whether the packet was new.
// Caller holds s.mu.
func (s *session) receive(pn uint64, frames []byte, now time.Time) (bool, error) {
	// recvd only remembers the last replayWindow pns, so anything older is refused outright.
	if s.recvd.contains(pn) || (s.haveRecv && pn+replayWindow < s.largestRecv) {
		// A duplicate (a resolver's retry) isn't reapplied, but its frames still say whether it
		// carried data, which decides whether the server may hold it (SPEC §7.2).
		parseFrames(frames, func(f *frame) error {
			if f.typ != frAck && f.typ != frLost && f.typ != frPing {
				s.rxData = true
			}
			return nil
		})
		return false, nil
	}
	s.recvd.add(pn, pn+1)
	if !s.haveRecv || pn > s.largestRecv {
		s.largestRecv, s.haveRecv = pn, true
	}
	if s.largestRecv > replayWindow {
		s.recvd.trimBelow(s.largestRecv - replayWindow)
	}
	eliciting := false
	err := parseFrames(frames, func(f *frame) error {
		if f.typ != frAck && f.typ != frLost {
			eliciting = true
		}
		if f.typ != frAck && f.typ != frLost && f.typ != frPing {
			s.rxData = true
		}
		switch {
		case f.typ == frPing:
		case f.typ == frAck:
			s.onAck(f.ranges, now)
		case f.typ == frLost:
			for _, up := range f.lost {
				if down, ok := s.replyOf[up]; ok {
					delete(s.replyOf, up)
					s.declareLost(down)
				} else if s.onLostUnknown != nil {
					s.onLostUnknown(up)
				}
			}
		case f.typ == frReset:
			if st := s.streams[f.id]; st != nil {
				// The peer stopped: abort our sending; keep data it already delivered readable.
				if st.rfin && st.rcontig() == st.rfinOff {
					st.sbuf, st.spending, st.sacked = nil, nil, nil
					st.sfin, st.saborted = true, true
					st.kill() // ends any pending dial (see serveStream); buffered data stays readable
				} else {
					st.abort(ErrStreamReset)
				}
				s.maybeRemove(st)
			}
		case f.typ == frMaxStream:
			if st := s.peerStream(f.id); st != nil && f.val > st.smax {
				st.smax = f.val
			}
		case f.typ == frOpened:
			if st := s.streams[f.id]; st != nil && s.client && !st.openedClosed {
				st.openedClosed = true
				close(st.opened)
			}
		case f.typ == frClose:
			s.closeLocked(fmt.Errorf("soar: session closed by peer (code %d)", f.val))
		case f.typ&0xF8 == frStream:
			st := s.peerStream(f.id)
			if st == nil {
				return nil
			}
			if err := st.onData(f.off, f.data, f.fin); err != nil {
				return err
			}
			s.maybeRemove(st)
		}
		return nil
	})
	// Ack only packets that carry something (RFC 9000 §13.2.1): acking an empty answer to an idle
	// poll would need a query, whose empty answer would need an ack, and polling would never stop.
	if s.client && eliciting {
		s.toAck.add(pn, pn+1)
		if len(s.toAck) > maxAckRanges {
			s.toAck = s.toAck[len(s.toAck)-maxAckRanges:]
		}
	}
	return true, err
}

// peerStream returns stream id, accepting it if the peer is opening it. Caller holds s.mu.
func (s *session) peerStream(id uint64) *stream {
	if st := s.streams[id]; st != nil {
		return st
	}
	// A closed session already released its streams; accepting more would leak their accounting.
	if s.closed {
		return nil
	}
	// Clients open ids in order, so a window around the highest seen bounds what we track: an id
	// below it opens only if it was skipped (its first packet arrived late), else it's closed.
	if s.client || id >= s.peerNext+uint64(s.cfg.MaxStreams) {
		return nil
	}
	if id < s.peerNext {
		if !s.peerGaps.contains(id) {
			return nil
		}
		s.peerGaps.remove(id, id+1)
	} else {
		s.peerGaps.add(s.peerNext, id)
		s.peerNext = id + 1
		if floor := s.peerNext - min(s.peerNext, 2*uint64(s.cfg.MaxStreams)); floor > 0 {
			s.peerGaps.trimBelow(floor)
		}
	}
	if len(s.streams) >= s.cfg.MaxStreams {
		s.queueCtrl(ctrl{typ: frReset, id: id})
		return nil
	}
	if s.acceptFn != nil && !s.acceptFn(nil) {
		s.queueCtrl(ctrl{typ: frReset, id: id})
		return nil
	}
	st := newStream(s, id)
	st.advertise()
	// Advertising can overflow the control queue and end the session. The slot acceptFn
	// reserved above isn't owned by any stream yet: give it back.
	if s.closed {
		if s.streamGone != nil {
			s.streamGone()
		}
		return nil
	}
	s.streams[id] = st
	s.rr = append(s.rr, id)
	if s.acceptFn != nil {
		s.acceptFn(st)
	}
	return st
}

// onAck handles the client's acknowledgement of downstream packets, then declares lost any packet
// that three later-sent packets overtook by more than 9/8 of the ack RTT (RFC 9002 §6.1).
// Caller holds s.mu.
func (s *session) onAck(rs rangeSet, now time.Time) {
	var largest uint64
	newly := false
	for pn, p := range s.sent {
		if rs.contains(pn) {
			if sample := now.Sub(p.at); s.ackRTT == 0 {
				s.ackRTT = sample
			} else {
				s.ackRTT = (7*s.ackRTT + sample) / 8
			}
			largest = max(largest, pn)
			newly = true
			s.acked(p)
		}
	}
	if !newly {
		return
	}
	for pn, p := range s.sent {
		// RFC 9002's time threshold. Its packet threshold is left out on purpose: answers arrive
		// over several resolvers, where three later acks is ordinary reordering, not loss.
		if pn < largest && now.Sub(p.at) > s.ackRTT*9/8 {
			s.declareLost(pn)
		}
	}
}

// acked applies a delivered packet. Caller holds s.mu.
func (s *session) acked(p *sentPkt) {
	delete(s.sent, p.pn)
	if s.replyOf[p.replyTo] == p.pn {
		delete(s.replyOf, p.replyTo)
	}
	for _, r := range p.streams {
		if st := s.streams[r.id]; st != nil {
			st.onAcked(r.lo, r.hi, r.fin)
		}
	}
	for _, a := range p.ack {
		s.toAck.remove(a.lo, a.hi)
	}
}

// dataInFlight reports whether a sent packet still carries stream or control frames. Ack- and
// lost-only packets are tracked for re-conveying but don't justify a query on their own, or an
// unanswered one would keep an idle session polling. Caller holds s.mu.
func (s *session) dataInFlight() bool {
	for _, p := range s.sent {
		if len(p.streams) > 0 || len(p.ctrls) > 0 {
			return true
		}
	}
	return false
}

// declareLost requeues a lost packet's contents. Caller holds s.mu.
func (s *session) declareLost(pn uint64) {
	p := s.sent[pn]
	if p == nil {
		return
	}
	delete(s.sent, pn)
	if s.replyOf[p.replyTo] == pn {
		delete(s.replyOf, p.replyTo)
	}
	for _, r := range p.streams {
		if st := s.streams[r.id]; st != nil {
			st.onLost(r.lo, r.hi, r.fin)
		}
	}
	for _, c := range p.ctrls {
		if c.typ == frMaxStream {
			if st := s.streams[c.id]; st == nil || st.rmax != c.val {
				continue // superseded
			}
		}
		s.queueCtrl(c)
	}
	// Ack ranges this packet carried still need conveying, if they're recent.
	for _, a := range p.ack {
		if s.recvd.contains(a.lo) {
			s.toAck.add(a.lo, a.hi)
		}
	}
	s.lostQ = append(s.lostQ, p.lost...)
}

// requeue makes a just-built packet's stream data pending again so the next packet carries a
// copy: the tail of a transfer is duplicated rather than waiting out a loss. Caller holds s.mu.
func (s *session) requeue(p *sentPkt) bool {
	any := false
	for _, r := range p.streams {
		st := s.streams[r.id]
		if st == nil || st.reset {
			continue
		}
		if r.lo < r.hi {
			st.spending.add(max(r.lo, st.sbase), r.hi)
			any = true
		}
		if r.fin && !st.sfinAcked {
			st.sfinSent = false
			any = true
		}
	}
	return any
}

// hasData reports whether anything other than acks is waiting to be sent. Caller holds s.mu.
func (s *session) hasData() bool {
	if len(s.ctrlQ) > 0 || len(s.lostQ) > 0 {
		return true
	}
	for _, id := range s.rr {
		if st := s.streams[id]; st != nil && st.wantsSend() {
			return true
		}
	}
	return false
}

// pendingBytes estimates the stream bytes waiting to be sent. Caller holds s.mu.
func (s *session) pendingBytes() int {
	n := 0
	for _, st := range s.streams {
		for _, r := range st.spending {
			if r.lo < st.smax {
				n += int(min(r.hi, st.smax) - r.lo)
			}
		}
	}
	return n
}

// build fills a packet's frames into b (up to budget bytes) and records it as sent. It always
// produces at least minPlain bytes. Caller holds s.mu.
func (s *session) build(b []byte, budget int, now time.Time, replyTo uint64) (pn uint64, out []byte, data bool, pkt *sentPkt) {
	start := len(b)
	room := func() int { return budget - (len(b) - start) }
	p := &sentPkt{pn: s.nextPN, at: now, replyTo: replyTo}
	s.nextPN++

	// Control frames first: they unblock the peer.
	for len(s.ctrlQ) > 0 {
		c := s.ctrlQ[0]
		enc := c.appendTo(nil)
		if len(enc) > room() {
			break
		}
		b = append(b, enc...)
		p.ctrls = append(p.ctrls, c)
		s.ctrlQ = s.ctrlQ[1:]
		data = true
	}
	if len(s.lostQ) > 0 && room() > 4 {
		slices.Sort(s.lostQ)
		s.lostQ = slices.Compact(s.lostQ)
		slices.Reverse(s.lostQ)
		n := min(len(s.lostQ), maxLostPerPkt)
		for n > 0 && len(appendLost(nil, s.lostQ[:n])) > room() {
			n--
		}
		if n > 0 {
			b = appendLost(b, s.lostQ[:n])
			p.lost = append(p.lost, s.lostQ[:n]...)
			s.lostQ = s.lostQ[n:]
		}
	}
	if s.client && len(s.toAck) > 0 {
		rs := s.toAck
		for len(rs) > 0 && ackLen(rs) > room() {
			rs = rs[1:]
		}
		if len(rs) > 0 {
			b = appendAck(b, rs)
			p.ack = slices.Clone(rs)
		}
	}
	// Stream data, round-robin across streams so one bulk transfer can't starve the rest.
	for i := 0; i < len(s.rr) && room() > 3; i++ {
		id := s.rr[0]
		s.rr = append(s.rr[1:], id)
		st := s.streams[id]
		if st == nil || !st.wantsSend() {
			continue
		}
		for st.wantsSend() && room() > 3 {
			if len(st.spending) == 0 {
				// A bare FIN.
				off := st.send()
				if streamHeaderLen(id, off, 0) > room() {
					break
				}
				b = appendStream(b, id, off, nil, true, false)
				st.sfinSent = true
				p.streams = append(p.streams, sentRange{id: id, lo: off, hi: off, fin: true})
				data = true
				break
			}
			r := st.spending[0]
			hi := min(r.hi, st.smax)
			hdr := streamHeaderLen(id, r.lo, int(hi-r.lo))
			if hdr >= room() {
				break
			}
			hi = min(hi, r.lo+uint64(room()-hdr))
			fin := st.sfin && !st.sfinAcked && hi == st.send()
			chunk := st.sbuf[r.lo-st.sbase : hi-st.sbase]
			b = appendStream(b, id, r.lo, chunk, fin, false)
			st.spending.remove(r.lo, hi)
			if fin {
				st.sfinSent = true
			}
			p.streams = append(p.streams, sentRange{id: id, lo: r.lo, hi: hi, fin: fin})
			data = true
		}
	}
	for len(b)-start < minPlain {
		b = append(b, frPadding)
	}
	if data || len(p.ack) > 0 || len(p.lost) > 0 {
		s.sent[p.pn] = p
		if !s.client {
			s.replyOf[p.replyTo] = p.pn
		}
		pkt = p
	}
	return p.pn, b, data, pkt
}

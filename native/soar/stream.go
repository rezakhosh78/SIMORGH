package soar

import (
	"context"
	"errors"
	"io"
	"net"
	"os"
	"sync"
	"time"
)

var (
	ErrStreamReset = errors.New("soar: stream reset by peer")
	ErrClosed      = errors.New("soar: closed")
)

// stream is one tunneled connection. All mutable state is guarded by the session mutex.
type stream struct {
	id uint64
	s  *session

	// Send side. sbuf holds bytes [sbase, sbase+len(sbuf)); everything below sbase is acked.
	sbuf      []byte
	sbase     uint64
	spending  rangeSet // ranges to send or retransmit
	sacked    rangeSet // acked ranges above sbase
	smax      uint64   // the peer's flow-control limit
	sfin      bool     // the app closed its write side
	sfinSent  bool     // a FIN is in flight (pending acks or loss)
	sfinAcked bool
	saborted  bool // the peer reset our sending; acks and losses for old ranges are moot
	scap      int  // bytes the app may buffer

	// Receive side. rbuf holds contiguous bytes from rread; rooo out-of-order segments by offset.
	rbuf    []byte
	rread   uint64
	rooo    map[uint64][]byte // disjoint out-of-order pieces, by offset
	rgot    rangeSet          // the ranges rooo holds
	rfin    bool
	rfinOff uint64
	rhigh   uint64 // highest offset received
	rwin    uint64 // window size
	rmax    uint64 // limit we last advertised
	rclosed bool   // the app closed its read side; arriving data is discarded

	reset    bool // aborted by either side
	resetErr error

	readCh, writeCh chan struct{}
	dead            chan struct{} // closed when the stream is closed locally or aborted
	deadClosed      bool
	aborted         chan struct{} // closed only on a true abort (reset in both directions)
	opened          chan struct{} // client: closed when the server reports its dial succeeded
	openedClosed    bool
	serving         bool // server: serveStream still owns it; it stays counted until done
	rdl, wdl        deadline
}

func newStream(s *session, id uint64) *stream {
	return &stream{
		id: id, s: s,
		smax: initialWindow, scap: s.cfg.SendBuffer,
		rwin: s.cfg.StreamWindow, rmax: initialWindow,
		readCh: make(chan struct{}, 1), writeCh: make(chan struct{}, 1), dead: make(chan struct{}), aborted: make(chan struct{}), opened: make(chan struct{}),
	}
}

// initialWindow is every stream's flow-control limit until the receiver advertises its own.
const initialWindow = 32 << 10

// advertise sends the receive window if it's larger than the initial one. Caller holds s.mu.
func (st *stream) advertise() {
	if st.rwin > st.rmax {
		st.rmax = st.rwin
		st.s.queueCtrl(ctrl{typ: frMaxStream, id: st.id, val: st.rmax})
	}
}

func signal(ch chan struct{}) {
	select {
	case ch <- struct{}{}:
	default:
	}
}

func (st *stream) rcontig() uint64 { return st.rread + uint64(len(st.rbuf)) }

func (st *stream) send() uint64 { return st.sbase + uint64(len(st.sbuf)) }

// onData handles a STREAM frame. Caller holds s.mu.
func (st *stream) onData(off uint64, data []byte, fin bool) error {
	end := off + uint64(len(data))
	if end > st.rmax {
		return errors.New("soar: peer exceeded stream flow control")
	}
	// A FIN fixes the final size: nothing may arrive beyond it, and it can't fall below data
	// already received (either way Read would never reach EOF).
	if st.rfin && end > st.rfinOff {
		return errors.New("soar: stream data beyond its final size")
	}
	if fin {
		if st.rfin && st.rfinOff != end || end < st.rhigh {
			return errors.New("soar: inconsistent stream FIN")
		}
		st.rfin, st.rfinOff = true, end
	}
	st.rhigh = max(st.rhigh, end)
	if st.rclosed || st.reset {
		signal(st.readCh)
		return nil
	}
	c := st.rcontig()
	if end <= c {
		if fin {
			signal(st.readCh)
		}
		return nil
	}
	if off > c {
		// Keep only what's new, so storage never exceeds the window however the peer slices it.
		novel := rangeSet{{off, end}}
		for _, g := range st.rgot {
			novel.remove(g.lo, g.hi)
		}
		if st.rooo == nil && len(novel) > 0 {
			st.rooo = make(map[uint64][]byte)
		}
		for _, n := range novel {
			st.rooo[n.lo] = append([]byte(nil), data[n.lo-off:n.hi-off]...)
			st.rgot.add(n.lo, n.hi)
		}
		// Payload is bounded by the window; these bound the bookkeeping, and with it the cost of
		// each insert. Real reordering leaves at most one gap and a few pieces per packet in
		// flight, far fewer than this.
		if len(st.rgot) > maxGaps || len(st.rooo) > maxPieces {
			return errors.New("soar: stream reassembly too fragmented")
		}
		return nil
	}
	st.rbuf = append(st.rbuf, data[c-off:]...)
	for len(st.rgot) > 0 && st.rgot[0].lo <= st.rcontig() {
		c = st.rcontig()
		g := st.rgot[0]
		for o := g.lo; o < g.hi; {
			d := st.rooo[o]
			if len(d) == 0 {
				break // pieces tile rgot; never spin if that invariant breaks
			}
			delete(st.rooo, o)
			if e := o + uint64(len(d)); e > c {
				st.rbuf = append(st.rbuf, d[max(c, o)-o:]...)
				c = e
			}
			o += uint64(len(d))
		}
		st.rgot = st.rgot[1:]
	}
	if len(st.rgot) > 0 {
		st.rgot.trimBelow(st.rcontig())
	}
	signal(st.readCh)
	return nil
}

const (
	maxGaps   = 1024
	maxPieces = 4096
)

// onAcked records that the peer received [lo, hi) and maybe the FIN. Caller holds s.mu.
func (st *stream) onAcked(lo, hi uint64, fin bool) {
	if st.reset || st.saborted {
		return
	}
	st.sacked.add(lo, hi)
	st.spending.remove(lo, hi)
	if base := st.sacked.coveredFrom(st.sbase); base > st.sbase {
		st.sbuf = st.sbuf[base-st.sbase:]
		if len(st.sbuf) == 0 {
			st.sbuf = nil
		}
		st.sbase = base
		st.sacked.trimBelow(base)
		signal(st.writeCh)
	}
	if fin {
		st.sfinAcked = true
	}
	st.s.maybeRemove(st)
}

// onLost requeues [lo, hi) minus whatever has been acked since. Caller holds s.mu.
func (st *stream) onLost(lo, hi uint64, fin bool) {
	if st.reset || st.saborted {
		return
	}
	lo = max(lo, st.sbase)
	if lo < hi {
		st.spending.add(lo, hi)
		for _, a := range st.sacked {
			st.spending.remove(a.lo, a.hi)
		}
	}
	if fin && !st.sfinAcked {
		st.sfinSent = false
	}
}

// wantsSend reports whether the stream has something sendable now. Caller holds s.mu.
func (st *stream) wantsSend() bool {
	if st.reset || st.saborted {
		return false
	}
	if len(st.spending) > 0 && st.spending[0].lo < st.smax {
		return true
	}
	return st.sfin && !st.sfinSent && !st.sfinAcked && len(st.spending) == 0
}

// doneSending: every byte and the FIN are acked (a FIN can be acked before earlier lost data),
// or sending was aborted.
func (st *stream) doneSending() bool {
	return st.reset || st.saborted || (st.sfin && st.sfinAcked && len(st.sbuf) == 0 && len(st.spending) == 0)
}

func (st *stream) doneReceiving() bool {
	return st.reset || st.rclosed || (st.rfin && st.rread == st.rfinOff)
}

func (st *stream) abort(err error) {
	if !st.reset {
		close(st.aborted)
	}
	st.reset, st.resetErr = true, err
	st.sbuf, st.spending, st.sacked = nil, nil, nil
	st.rooo, st.rgot = nil, nil
	st.kill()
}

// kill wakes every blocked Read and Write for good. Caller holds s.mu.
func (st *stream) kill() {
	if !st.deadClosed {
		st.deadClosed = true
		close(st.dead)
	}
	signal(st.readCh)
	signal(st.writeCh)
}

// waitOpened blocks until the server reports the stream's destination dial succeeded, the
// stream is aborted (the dial failed), or ctx ends.
func (c *Conn) waitOpened(ctx context.Context) error {
	st := c.st
	select {
	case <-st.opened:
		return nil
	case <-st.aborted:
		st.s.mu.Lock()
		err := st.resetErr
		st.s.mu.Unlock()
		return err
	case <-st.s.done:
		return ErrClosed
	case <-ctx.Done():
		return ctx.Err()
	}
}

// Conn wraps a stream as a net.Conn.
type Conn struct {
	st     *stream
	local  net.Addr
	remote net.Addr
}

func (c *Conn) Read(p []byte) (int, error) {
	st, s := c.st, c.st.s
	for {
		if st.rdl.expired() {
			return 0, os.ErrDeadlineExceeded
		}
		s.mu.Lock()
		if len(st.rbuf) > 0 {
			n := copy(p, st.rbuf)
			st.rbuf = st.rbuf[n:]
			if len(st.rbuf) == 0 {
				st.rbuf = nil
			}
			st.rread += uint64(n)
			// Re-advertise once half the window has been consumed. Limits only ever grow: the
			// peer keeps whatever it was granted before.
			grant := false
			if limit := st.rread + st.rwin; limit > st.rmax && limit-st.rmax >= st.rwin/2 && !st.rfin {
				st.rmax = limit
				s.queueCtrl(ctrl{typ: frMaxStream, id: st.id, val: limit})
				grant = true
			}
			// Wakeups coalesce: pass one on to any other blocked reader if more is readable.
			if len(st.rbuf) > 0 || st.rfin && st.rread == st.rfinOff {
				signal(st.readCh)
			}
			s.maybeRemove(st)
			s.mu.Unlock()
			if grant {
				s.wake()
			}
			return n, nil
		}
		if st.rfin && st.rread == st.rfinOff {
			signal(st.readCh)
			s.maybeRemove(st)
			s.mu.Unlock()
			return 0, io.EOF
		}
		if st.reset {
			err := st.resetErr
			s.mu.Unlock()
			return 0, err
		}
		if st.rclosed || st.deadClosed {
			// A dead stream with nothing left to read ends here rather than spinning on dead.
			s.mu.Unlock()
			return 0, net.ErrClosed
		}
		s.mu.Unlock()
		if err := st.rdl.wait(st.readCh, s.done, st.dead); err != nil {
			return 0, err
		}
	}
}

func (c *Conn) Write(p []byte) (int, error) {
	st, s := c.st, c.st.s
	written := 0
	for written < len(p) {
		if st.wdl.expired() {
			return written, os.ErrDeadlineExceeded
		}
		s.mu.Lock()
		if st.reset {
			err := st.resetErr
			s.mu.Unlock()
			return written, err
		}
		if st.sfin || st.deadClosed {
			s.mu.Unlock()
			return written, net.ErrClosed
		}
		if room := st.scap - len(st.sbuf); room > 0 {
			n := min(room, len(p)-written)
			start := st.send()
			st.sbuf = append(st.sbuf, p[written:written+n]...)
			st.spending.add(start, start+uint64(n))
			written += n
			if written == len(p) && len(st.sbuf) < st.scap {
				signal(st.writeCh) // room left: pass the wakeup on to another writer
			}
			s.mu.Unlock()
			s.wake()
			continue
		}
		s.mu.Unlock()
		if err := st.wdl.wait(st.writeCh, s.done, st.dead); err != nil {
			return written, err
		}
	}
	return written, nil
}

// CloseWrite sends a FIN: the peer reads EOF after the data written so far.
func (c *Conn) CloseWrite() error {
	s := c.st.s
	s.mu.Lock()
	c.st.sfin = true
	s.mu.Unlock()
	s.wake()
	return nil
}

// Close closes both directions. Data already written is still delivered.
func (c *Conn) Close() error {
	st, s := c.st, c.st.s
	s.mu.Lock()
	st.sfin = true
	if !st.doneReceiving() {
		st.rclosed = true
		st.rbuf, st.rooo, st.rgot = nil, nil, nil
		// Ask the peer to stop sending, but only once our FIN is acknowledged: a RESET before
		// the peer has the FIN aborts the stream there, and data already written would be lost.
		// An empty sbuf only means the data was acked, not the FIN. Until then the FIN goes
		// out, and maybeRemove sends the RESET when the stream is done.
		if st.doneSending() {
			s.queueCtrl(ctrl{typ: frReset, id: st.id})
			st.abort(net.ErrClosed)
		}
	}
	s.maybeRemove(st)
	st.kill()
	s.mu.Unlock()
	s.wake()
	return nil
}

func (c *Conn) LocalAddr() net.Addr  { return c.local }
func (c *Conn) RemoteAddr() net.Addr { return c.remote }

func (c *Conn) SetDeadline(t time.Time) error {
	c.st.rdl.set(t)
	c.st.wdl.set(t)
	return nil
}

func (c *Conn) SetReadDeadline(t time.Time) error {
	c.st.rdl.set(t)
	return nil
}

func (c *Conn) SetWriteDeadline(t time.Time) error {
	c.st.wdl.set(t)
	return nil
}

// deadline is a settable deadline for blocking waits. Changing it wakes every waiter (by closing
// changed), so each re-reads the new value.
type deadline struct {
	mu      sync.Mutex
	t       time.Time
	changed chan struct{}
}

func (d *deadline) set(t time.Time) {
	d.mu.Lock()
	d.t = t
	if d.changed != nil {
		close(d.changed)
	}
	d.changed = make(chan struct{})
	d.mu.Unlock()
}

func (d *deadline) expired() bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	return !d.t.IsZero() && !time.Now().Before(d.t)
}

// wait blocks until ch is signaled, the deadline passes, or done closes. When dead closes it
// returns nil, so the caller rechecks the stream's state and reports why.
func (d *deadline) wait(ch, done, dead <-chan struct{}) error {
	d.mu.Lock()
	t := d.t
	if d.changed == nil {
		d.changed = make(chan struct{})
	}
	changed := d.changed
	d.mu.Unlock()
	var timeout <-chan time.Time
	if !t.IsZero() {
		dur := time.Until(t)
		if dur <= 0 {
			return os.ErrDeadlineExceeded
		}
		timer := time.NewTimer(dur)
		defer timer.Stop()
		timeout = timer.C
	}
	select {
	case <-ch:
		return nil
	case <-dead:
		return nil
	case <-changed:
		return nil
	case <-done:
		return ErrClosed
	case <-timeout:
		return os.ErrDeadlineExceeded
	}
}

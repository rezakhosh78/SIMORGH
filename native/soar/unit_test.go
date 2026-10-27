package soar

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/rand"
	"errors"
	mrand "math/rand/v2"
	"os"
	"strings"
	"testing"
	"time"
)

func TestRangeSet(t *testing.T) {
	var s rangeSet
	s.add(5, 10)
	s.add(0, 3)
	s.add(3, 5)
	if len(s) != 1 || s[0] != (span{0, 10}) {
		t.Fatalf("merge: %v", s)
	}
	s.add(20, 30)
	s.remove(2, 25)
	if len(s) != 2 || s[0] != (span{0, 2}) || s[1] != (span{25, 30}) {
		t.Fatalf("remove: %v", s)
	}
	if !s.contains(1) || s.contains(2) || !s.contains(29) {
		t.Fatalf("contains: %v", s)
	}
	if s.coveredFrom(0) != 2 {
		t.Fatal("coveredFrom")
	}
}

func TestFramesRoundTrip(t *testing.T) {
	acks := rangeSet{{1, 3}, {5, 6}, {9, 20}}
	var b []byte
	b = appendAck(b, acks)
	b = appendLost(b, []uint64{40, 7, 6, 0})
	b = ctrl{typ: frMaxStream, id: 3, val: 1 << 20}.appendTo(b)
	b = appendStream(b, 2, 100, []byte("hello"), false, false)
	b = appendStream(b, 7, 0, []byte("tail"), true, true)
	var got []frame
	if err := parseFrames(b, func(f *frame) error { got = append(got, *f); return nil }); err != nil {
		t.Fatal(err)
	}
	if len(got) != 5 {
		t.Fatalf("got %d frames", len(got))
	}
	if len(got[0].ranges) != 3 || got[0].ranges[0] != acks[0] || got[0].ranges[2] != acks[2] {
		t.Fatalf("ack: %v", got[0].ranges)
	}
	if l := got[1].lost; len(l) != 4 || l[0] != 40 || l[3] != 0 {
		t.Fatalf("lost: %v", l)
	}
	if got[2].id != 3 || got[2].val != 1<<20 {
		t.Fatal("maxStream")
	}
	if got[3].id != 2 || got[3].off != 100 || string(got[3].data) != "hello" || got[3].fin {
		t.Fatal("stream")
	}
	if got[4].id != 7 || string(got[4].data) != "tail" || !got[4].fin {
		t.Fatal("last stream")
	}
}

func TestHandshakeAndPacket(t *testing.T) {
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	eph, _ := ecdh.X25519().GenerateKey(rand.Reader)
	reply, sUp, sDown, err := serverHello(priv, eph.PublicKey().Bytes(), 0x1234)
	if err != nil {
		t.Fatal(err)
	}
	sid, cUp, cDown, err := clientFinish(pub, eph, reply)
	if err != nil || sid != 0x1234 {
		t.Fatal(sid, err)
	}
	ad := []byte{1, 2, 3, 4}
	sealed := cUp.seal(nil, ad, []byte("abcdefgh"), 77)
	plain, err := sUp.open(ad, sealed, 77)
	if err != nil || string(plain) != "abcdefgh" {
		t.Fatal("open", err)
	}
	if cUp.mask(sealed) != sUp.mask(sealed) {
		t.Fatal("mask")
	}
	sealed[3] ^= 1
	if _, err := sUp.open(ad, sealed, 77); err == nil {
		t.Fatal("tamper accepted")
	}
	if _, err := cDown.open(ad, sDown.seal(nil, ad, []byte("x"), 1), 2); err == nil {
		t.Fatal("wrong pn accepted")
	}
	reply[40] ^= 1
	if _, _, _, err := clientFinish(pub, eph, reply); err == nil {
		t.Fatal("forged hello accepted")
	}
}

func TestDNSRoundTrip(t *testing.T) {
	z, _ := parseZone("t.example.com")
	for _, nameLen := range []int{101, 253} {
		n := z.maxPayload(nameLen)
		payload := make([]byte, n)
		rand.Read(payload)
		msg := appendQuery(nil, 7, payload, z, typeTXT, 1232)
		end, _ := skipName(msg, hdrLen)
		if pres := end - hdrLen - 2; pres > nameLen {
			t.Fatalf("name %d chars > %d", pres, nameLen)
		}
		// Resolvers may randomize case.
		up := bytes.ToUpper(msg[hdrLen:end])
		copy(msg[hdrLen:], up)
		q, err := parseQuery(msg, z)
		if err != nil || !bytes.Equal(q.payload, payload) || q.edns != 1232 {
			t.Fatalf("nameLen %d: payload mismatch (%v)", nameLen, err)
		}
		cap := capacity(q, 512)
		data := make([]byte, cap)
		rand.Read(data)
		ans := appendAnswer(nil, q, rcodeOK, data)
		if len(ans) > 512 {
			t.Fatalf("answer %d > 512", len(ans))
		}
		a, err := parseAnswer(ans)
		if err != nil || !bytes.Equal(a.txt, data) || a.txn != 7 {
			t.Fatal("answer mismatch", err)
		}
	}
}

func TestDecodePN(t *testing.T) {
	if decodePN(0xFFFE, true, 0x0001) != 0x10001 {
		t.Fatal("wrap forward")
	}
	if decodePN(0x10005, true, 0xFFF0) != 0xFFF0 {
		t.Fatal("reorder back")
	}
	if decodePN(0, false, 5) != 5 {
		t.Fatal("first")
	}
}

func TestStreamReassemblyOverlaps(t *testing.T) {
	s := newSession(false, streamConfig{StreamWindow: 1 << 16, SendBuffer: 1 << 16, MaxStreams: 4}, nil, nil)
	st := newStream(s, 0)
	st.rmax = 1 << 16
	data := pattern(5000)
	// Overlapping, out-of-order, re-cut pieces, as retransmission over varied paths produces.
	pieces := [][2]int{{3000, 5000}, {1000, 2500}, {1200, 3200}, {4000, 4500}, {2400, 2600}, {0, 1100}}
	for _, p := range pieces {
		if err := st.onData(uint64(p[0]), data[p[0]:p[1]], p[1] == 5000); err != nil {
			t.Fatal(err)
		}
		held := 0
		for _, d := range st.rooo {
			held += len(d)
		}
		if held > 5000 {
			t.Fatalf("out-of-order storage %d exceeds the data", held)
		}
	}
	if !bytes.Equal(st.rbuf, data) || len(st.rooo) != 0 || len(st.rgot) != 0 {
		t.Fatalf("reassembled %d bytes, rooo=%d rgot=%v", len(st.rbuf), len(st.rooo), st.rgot)
	}
}

func TestPeerStreamReorderedOpens(t *testing.T) {
	s := newSession(false, streamConfig{StreamWindow: 1 << 16, SendBuffer: 1 << 16, MaxStreams: 8}, nil, nil)
	for _, id := range []uint64{0, 3, 1} {
		if s.peerStream(id) == nil {
			t.Fatalf("stream %d refused", id)
		}
	}
	st := s.streams[1]
	st.sfin, st.sfinAcked, st.rfin = true, true, true
	s.maybeRemove(st)
	if s.peerStream(1) != nil {
		t.Fatal("closed stream reopened")
	}
	if s.peerStream(2) == nil {
		t.Fatal("late open of skipped id 2 refused")
	}
	if s.peerStream(100) != nil {
		t.Fatal("id far beyond the window accepted")
	}
}

func TestAAAACarrier(t *testing.T) {
	z, _ := parseZone("t.example.com")
	msg := appendQuery(nil, 9, []byte("hello"), z, typeAAAA, 1232)
	q, err := parseQuery(msg, z)
	if err != nil || q.qtype != typeAAAA {
		t.Fatal(err)
	}
	for _, limit := range []int{512, 1232} {
		n := capacity(q, limit)
		data := make([]byte, n)
		rand.Read(data)
		ans := appendAnswer(nil, q, rcodeOK, data)
		if len(ans) > limit {
			t.Fatalf("limit %d: answer %d bytes", limit, len(ans))
		}
		// Resolvers may reorder the RRset: reverse it.
		a0, _ := parseAnswer(ans)
		_ = a0
		start := hdrLen + len(q.question)
		rrs := ans[start : start+(len(ans)-start-11)]
		var recs [][]byte
		for i := 0; i+aaaaRRLen <= len(rrs); i += aaaaRRLen {
			recs = append(recs, rrs[i:i+aaaaRRLen])
		}
		shuffled := append([]byte(nil), ans[:start]...)
		for i := len(recs) - 1; i >= 0; i-- {
			shuffled = append(shuffled, recs[i]...)
		}
		shuffled = append(shuffled, ans[start+len(rrs):]...)
		a, err := parseAnswer(shuffled)
		if err != nil || !bytes.Equal(a.txt, data) {
			t.Fatalf("limit %d: %v, got %d bytes want %d", limit, err, len(a.txt), len(data))
		}
	}
}

func newTestStream(t *testing.T) (*session, *stream) {
	s := newSession(true, streamConfig{StreamWindow: 1 << 16, SendBuffer: 1 << 16, MaxStreams: 4}, nil, nil)
	st, err := s.openStream()
	if err != nil {
		t.Fatal(err)
	}
	return s, st
}

func TestFinAckedBeforeEarlierDataKeepsStream(t *testing.T) {
	s, st := newTestStream(t)
	st.sbuf = make([]byte, 20)
	st.sfin, st.rfin = true, true
	st.onAcked(10, 20, true) // the FIN's packet arrives; [0,10) is still lost
	if st.doneSending() {
		t.Fatal("send side done with [0,10) unacked")
	}
	s.maybeRemove(st)
	if s.streams[st.id] == nil {
		t.Fatal("stream removed with unacked data")
	}
	st.onLost(0, 10, false)
	if !st.wantsSend() {
		t.Fatal("lost data not requeued")
	}
}

func TestAckAfterPeerResetDoesNotPanic(t *testing.T) {
	s, st := newTestStream(t)
	st.sbuf = make([]byte, 20)
	st.sfin = true
	st.rfin, st.rbuf = true, []byte("unread")
	reset := ctrl{typ: frReset, id: st.id}.appendTo(nil)
	if _, err := s.receive(0, reset, time.Now()); err != nil {
		t.Fatal(err)
	}
	st.onAcked(0, 20, true)
	st.onLost(0, 20, true)
	if st.wantsSend() {
		t.Fatal("sending after reset")
	}
	other := newStream(s, 9)
	other.sbuf = make([]byte, 20)
	other.abort(ErrStreamReset)
	other.onAcked(0, 20, true)
}

func TestCloseWakesAllBlockedReaders(t *testing.T) {
	s, st := newTestStream(t)
	_ = s
	c := &Conn{st: st}
	errs := make(chan error, 4)
	for range 4 {
		go func() {
			_, err := c.Read(make([]byte, 10))
			errs <- err
		}()
	}
	time.Sleep(50 * time.Millisecond)
	c.Close()
	for range 4 {
		select {
		case err := <-errs:
			if err == nil {
				t.Fatal("Read returned no error after Close")
			}
		case <-time.After(2 * time.Second):
			t.Fatal("a blocked Read never woke after Close")
		}
	}
}

// The unknown-session signature folds only ASCII letters, so every implementation signs the same
// bytes whatever the question holds; bytes.ToLower would rewrite invalid UTF-8 (SPEC §4.5).
func TestUnknownSessionFoldsOnlyASCII(t *testing.T) {
	question := []byte{3, 'A', 'b', 0xC0, 0x80, 0xFF, 'Z', 0, 0x10}
	got := unknownSessionMsg(question)[len(unknownContext):]
	want := []byte{3, 'a', 'b', 0xC0, 0x80, 0xFF, 'z', 0, 0x10}
	if !bytes.Equal(got, want) {
		t.Fatalf("folded %x, want %x", got, want)
	}
}

func TestReplayedAnswerFailsForAnotherQuery(t *testing.T) {
	c := &packetCipher{}
	trunc := [2]byte{0, 5}
	sealed := c.seal(nil, downAD(trunc, 10), []byte("answer to query 10"), 5)
	if _, err := c.open(downAD(trunc, 10), sealed, 5); err != nil {
		t.Fatal(err)
	}
	if _, err := c.open(downAD(trunc, 11), sealed, 5); err == nil {
		t.Fatal("answer to query 10 authenticated as an answer to query 11")
	}
}

func TestDeadlines(t *testing.T) {
	_, st := newTestStream(t)
	c := &Conn{st: st}
	errs := make(chan error, 2)
	for range 2 {
		go func() {
			_, err := c.Read(make([]byte, 10))
			errs <- err
		}()
	}
	time.Sleep(50 * time.Millisecond)
	c.SetReadDeadline(time.Now().Add(50 * time.Millisecond))
	for range 2 {
		select {
		case err := <-errs:
			if !errors.Is(err, os.ErrDeadlineExceeded) {
				t.Fatalf("got %v, want deadline exceeded", err)
			}
		case <-time.After(2 * time.Second):
			t.Fatal("a blocked Read ignored the new deadline")
		}
	}
	// With data buffered and space to write, an expired deadline still fails the call.
	st.rbuf = []byte("data")
	if _, err := c.Read(make([]byte, 10)); !errors.Is(err, os.ErrDeadlineExceeded) {
		t.Fatalf("Read after deadline: %v", err)
	}
	c.SetWriteDeadline(time.Now().Add(-time.Second))
	if _, err := c.Write([]byte("x")); !errors.Is(err, os.ErrDeadlineExceeded) {
		t.Fatalf("Write after deadline: %v", err)
	}
	c.SetDeadline(time.Time{})
	if n, err := c.Read(make([]byte, 10)); err != nil || n != 4 {
		t.Fatalf("Read after clearing deadline: %d, %v", n, err)
	}
}

func TestServerStreamCountFollowsSession(t *testing.T) {
	s := newSession(false, streamConfig{StreamWindow: 1 << 16, SendBuffer: 1 << 16, MaxStreams: 8}, nil, nil)
	open := 0
	s.acceptFn = func(st *stream) bool {
		if st != nil {
			open++
		}
		return true
	}
	s.streamGone = func() { open-- }
	st := s.peerStream(0)
	s.peerStream(1)
	st.sbuf = []byte("unacked response")
	st.sfin, st.rfin = true, true
	s.maybeRemove(st)
	if open != 2 {
		t.Fatalf("open = %d with unacked data still buffered, want 2", open)
	}
	s.close(ErrClosed)
	if open != 0 {
		t.Fatalf("open = %d after session close, want 0", open)
	}
}

func TestClosedSessionAcceptsNoStreams(t *testing.T) {
	s := newSession(false, streamConfig{StreamWindow: 1 << 16, SendBuffer: 1 << 16, MaxStreams: 8}, nil, nil)
	open := 0
	s.acceptFn = func(st *stream) bool {
		if st != nil {
			open++
		}
		return true
	}
	s.streamGone = func() { open-- }
	s.peerStream(0)
	s.close(ErrClosed)
	s.mu.Lock()
	got := s.peerStream(1)
	s.mu.Unlock()
	if got != nil || open != 0 {
		t.Fatalf("closed session accepted a stream (open=%d)", open)
	}
}

func TestSmallWindowNeverShrinksGrant(t *testing.T) {
	s := newSession(true, streamConfig{StreamWindow: 8 << 10, SendBuffer: 1 << 16, MaxStreams: 4}, nil, nil)
	st, _ := s.openStream()
	c := &Conn{st: st}
	if err := st.onData(0, make([]byte, 1000), false); err != nil {
		t.Fatal(err)
	}
	c.Read(make([]byte, 1000))
	if st.rmax < initialWindow {
		t.Fatalf("rmax shrank to %d below the initial grant %d", st.rmax, initialWindow)
	}
	if err := st.onData(1000, make([]byte, initialWindow-1000), false); err != nil {
		t.Fatalf("peer within its original allowance rejected: %v", err)
	}
}

func TestConcurrentReadersAllSeeDataAndEOF(t *testing.T) {
	s, st := newTestStream(t)
	c := &Conn{st: st}
	res := make(chan error, 2)
	for range 2 {
		go func() {
			_, err := c.Read(make([]byte, 1))
			res <- err
		}()
	}
	time.Sleep(50 * time.Millisecond)
	s.mu.Lock()
	st.onData(0, []byte("ab"), true)
	s.mu.Unlock()
	for range 2 {
		select {
		case err := <-res:
			if err != nil {
				t.Fatalf("Read: %v", err)
			}
		case <-time.After(2 * time.Second):
			t.Fatal("a concurrent reader stayed blocked with data available")
		}
	}
}

func TestFinalSizeIsEnforced(t *testing.T) {
	_, st := newTestStream(t)
	if err := st.onData(0, make([]byte, 10), true); err != nil {
		t.Fatal(err)
	}
	if err := st.onData(10, make([]byte, 5), false); err == nil {
		t.Fatal("data beyond the final size accepted")
	}
	_, st2 := newTestStream(t)
	st2.onData(20, make([]byte, 10), false)
	if err := st2.onData(0, make([]byte, 5), true); err == nil {
		t.Fatal("FIN below data already received accepted")
	}
}

func TestZoneSyntax(t *testing.T) {
	for _, ok := range []string{"t.example.com", "T.Example.COM.", "_soar.example.com", strings.Repeat("a", 63) + ".x"} {
		if _, err := parseZone(ok); err != nil {
			t.Errorf("%q rejected: %v", ok, err)
		}
	}
	for _, bad := range []string{"", ".", "a..b", strings.Repeat("a", 64) + ".x", "caf\xc3\xa9.example", "a b.example", "tab\t.example", "\u212a.example"} {
		if _, err := parseZone(bad); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
}

func TestZoneTooLongForHandshake(t *testing.T) {
	long := strings.Repeat("a", 60) + "." + strings.Repeat("b", 60) + "." + strings.Repeat("c", 60) + ".abcdefghij.example.com"
	if _, err := parseZone(long); err == nil {
		t.Fatal("zone with no room for a hello accepted")
	}
	if _, err := parseZone("t.example.com"); err != nil {
		t.Fatal(err)
	}
}

func TestByteBucket(t *testing.T) {
	now := time.Now()
	b := &byteBucket{rate: 1000, burst: 1000, tokens: 1000, last: now}
	if !b.take(now, 800) || b.take(now, 800) {
		t.Fatal("burst not enforced")
	}
	if !b.take(now.Add(time.Second), 800) {
		t.Fatal("bucket did not refill")
	}
}

func TestFragmentedReassemblyIsBounded(t *testing.T) {
	_, st := newTestStream(t)
	st.rmax = 1 << 20
	// Disjoint one-byte fragments, each leaving a gap: a hostile peer's pattern.
	var err error
	for i := uint64(1); i < 3*maxGaps && err == nil; i++ {
		err = st.onData(2*i, []byte{1}, false)
	}
	if err == nil {
		t.Fatal("unbounded fragmentation accepted")
	}
	// Many adjacent one-byte pieces in reverse order: bounded, and cheap per insert.
	_, st2 := newTestStream(t)
	st2.rmax = 1 << 20
	start := time.Now()
	err = nil
	for i := uint64(3 * maxPieces); i > 0 && err == nil; i-- {
		err = st2.onData(i, []byte{1}, false)
	}
	if err == nil {
		t.Fatal("unbounded piece count accepted")
	}
	if d := time.Since(start); d > time.Second {
		t.Fatalf("fragment flood took %v", d)
	}
}

// drive sends n queries on p, deciding each one's fate from its class.
func drive(p *path, n int, now time.Time, lost func(class, i int) bool) {
	for i := range n {
		c := p.nextClass()
		p.sizeOutcome(c, lost(c, i), now)
	}
}

func TestAnswerSizeTrialThenDemotionWhenPolicingStarts(t *testing.T) {
	now := time.Now()
	p := &path{sizeCeil: limitClasses, maxClass: limitClasses - 1}
	drive(p, 200, now, func(int, int) bool { return false }) // everything arrives
	if p.sizeOK != limitClasses-1 {
		t.Fatalf("clean path not promoted to the largest size: sizeOK=%d test=%d", p.sizeOK, p.test)
	}
	// Policing starts: large answers vanish, 512-byte canaries still arrive.
	drive(p, 200, now, func(c, _ int) bool { return c > 0 })
	if p.sizeOK != 0 || p.sizeCeil != limitClasses-1 || !p.sizeBlocked.After(now) {
		t.Fatalf("not demoted: sizeOK=%d ceil=%d", p.sizeOK, p.sizeCeil)
	}
}

func TestEvenLossNeverMovesAnswerSize(t *testing.T) {
	now := time.Now()
	// A rate limit drops a third of queries whatever their size: a trial still passes...
	rng := mrand.New(mrand.NewPCG(1, 2))
	p := &path{sizeCeil: limitClasses, maxClass: limitClasses - 1}
	drive(p, 300, now, func(int, int) bool { return rng.Float64() < 0.33 })
	if p.sizeOK != limitClasses-1 {
		t.Fatalf("size-blind loss failed the trial: sizeOK=%d ceil=%d", p.sizeOK, p.sizeCeil)
	}
	// ...and a proven size isn't demoted by it.
	drive(p, 300, now, func(int, int) bool { return rng.Float64() < 0.33 })
	if p.sizeOK != limitClasses-1 {
		t.Fatalf("size-blind loss demoted the proven size to %d", p.sizeOK)
	}
}

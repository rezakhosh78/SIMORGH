package soar

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log/slog"
	mrand "math/rand/v2"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

const testZone = "t.example.com"

// loopbackDial lets tests reach origins on 127.0.0.1, which the default dialer refuses.
var loopbackDial = (&net.Dialer{}).DialContext

func startServer(t *testing.T) (string, ed25519.PublicKey) {
	return startServerWith(t, ServerConfig{Dial: loopbackDial})
}

func startServerWith(t *testing.T, cfg ServerConfig) (string, ed25519.PublicKey) {
	t.Helper()
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	cfg.Zone, cfg.PrivateKey = testZone, priv
	go Serve(ctx, pc, cfg)
	return pc.LocalAddr().String(), pub
}

// startOrigin serves "N\n" → N pattern bytes, or echoes when the request is "echo\n".
func startOrigin(t *testing.T) string {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				var line []byte
				b := make([]byte, 1)
				for {
					if _, err := c.Read(b); err != nil {
						return
					}
					if b[0] == '\n' {
						break
					}
					line = append(line, b[0])
				}
				if string(line) == "echo" {
					io.Copy(c, c)
					return
				}
				var n int
				fmt.Sscan(string(line), &n)
				c.Write(pattern(n))
			}()
		}
	}()
	return ln.Addr().String()
}

func pattern(n int) []byte {
	b := make([]byte, n)
	for i := range b {
		b[i] = byte(i*7 + i>>8)
	}
	return b
}

// lossyForwarder relays UDP to server, dropping and delaying like a bad resolver.
func lossyForwarder(t *testing.T, server string, loss float64, delay, jitter time.Duration) string {
	t.Helper()
	front, _ := net.ListenPacket("udp", "127.0.0.1:0")
	back, _ := net.ListenPacket("udp", "127.0.0.1:0")
	t.Cleanup(func() { front.Close(); back.Close() })
	saddr, _ := net.ResolveUDPAddr("udp", server)
	var mu sync.Mutex
	var client net.Addr
	rng := mrand.New(mrand.NewPCG(1, 2))
	later := func(pc net.PacketConn, b []byte, to net.Addr) {
		mu.Lock()
		drop := rng.Float64() < loss
		d := delay
		if jitter > 0 {
			d += time.Duration(rng.Int64N(int64(jitter)))
		}
		mu.Unlock()
		if drop {
			return
		}
		time.AfterFunc(d, func() { pc.WriteTo(b, to) })
	}
	go func() {
		buf := make([]byte, 4096)
		for {
			n, from, err := front.ReadFrom(buf)
			if err != nil {
				return
			}
			mu.Lock()
			client = from
			mu.Unlock()
			later(back, append([]byte(nil), buf[:n]...), saddr)
		}
	}()
	go func() {
		buf := make([]byte, 4096)
		for {
			n, _, err := back.ReadFrom(buf)
			if err != nil {
				return
			}
			mu.Lock()
			to := client
			mu.Unlock()
			later(front, append([]byte(nil), buf[:n]...), to)
		}
	}()
	return front.LocalAddr().String()
}

func fetch(t *testing.T, c *Client, origin string, n int) time.Duration {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	start := time.Now()
	conn, err := c.DialContext(ctx, "tcp", origin)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(60 * time.Second))
	fmt.Fprintf(conn, "%d\n", n)
	got, err := io.ReadAll(conn)
	if err != nil {
		t.Fatalf("read after %d bytes: %v", len(got), err)
	}
	if !bytes.Equal(got, pattern(n)) {
		t.Fatalf("got %d bytes, want %d (corrupt or short)", len(got), n)
	}
	return time.Since(start)
}

func TestE2EDirect(t *testing.T) {
	srv, pub := startServer(t)
	origin := startOrigin(t)
	c, err := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{srv}})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	fetch(t, c, origin, 10)
	d := fetch(t, c, origin, 1<<20)
	t.Logf("1 MiB direct: %v (%.0f KB/s)", d, 1024/d.Seconds())
}

func TestE2EEcho(t *testing.T) {
	srv, pub := startServer(t)
	origin := startOrigin(t)
	c, _ := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{srv}})
	defer c.Close()
	conn, err := c.DialContext(context.Background(), "tcp", origin)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(30 * time.Second))
	up := make([]byte, 200<<10)
	rand.Read(up)
	go func() {
		conn.Write([]byte("echo\n"))
		conn.Write(up)
	}()
	got := make([]byte, len(up))
	if _, err := io.ReadFull(conn, got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, up) {
		t.Fatal("echo mismatch")
	}
}

func TestE2ELossyMultipath(t *testing.T) {
	srv, pub := startServer(t)
	origin := startOrigin(t)
	var res []string
	for range 3 {
		res = append(res, lossyForwarder(t, srv, 0.10, 40*time.Millisecond, 40*time.Millisecond))
	}
	c, _ := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: res})
	defer c.Close()
	var wg sync.WaitGroup
	for range 4 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			d := fetch(t, c, origin, 100<<10)
			t.Logf("100 KiB over 3 lossy paths: %v", d)
		}()
	}
	wg.Wait()
}

func TestDefaultDialRefusesLoopback(t *testing.T) {
	srv, pub := startServerWith(t, ServerConfig{})
	origin := startOrigin(t)
	c, _ := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{srv}})
	defer c.Close()
	conn, err := c.DialContext(context.Background(), "tcp", origin)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(10 * time.Second))
	fmt.Fprintf(conn, "10\n")
	if _, err := io.ReadAll(conn); err == nil {
		t.Fatal("server dialed a loopback destination")
	}
}

func TestPublicOnly(t *testing.T) {
	for addr, ok := range map[string]bool{
		"127.0.0.1:80": false, "10.1.2.3:443": false, "169.254.169.254:80": false,
		"100.64.0.1:80": false, "[::1]:443": false, "[fd00::1]:80": false, "0.0.0.0:80": false,
		"0.1.2.3:80": false, "[::ffff:127.0.0.1]:80": false, "[64:ff9b::7f00:1]:80": false,
		"[64:ff9b::a9fe:a9fe]:80": false, "[2002:7f00:1::]:80": false, "[2001:0:1::1]:80": false,
		"[::127.0.0.1]:80": false, "[2606:4700::1111%eth0]:443": false, "[64:ff9b::7f00:1%lo0]:80": false, "[::ffff:0:7f00:1]:80": false, "[64:ff9b::808:808]:443": true, "[2002:808:808::]:443": true,
		"192.0.2.1:443": false, "198.51.100.9:80": false, "203.0.113.5:443": false,
		"8.8.8.8:443": true, "[2606:4700::1111]:443": true,
		"192.88.99.1:443": false, "[2001:20::1]:443": false, "[3fff::1]:443": false,
	} {
		if err := PublicOnly("tcp", addr, nil); (err == nil) != ok {
			t.Errorf("%s: allowed=%v, want %v", addr, err == nil, ok)
		}
	}
}

func TestPublicOnlyRefusesOwnAddresses(t *testing.T) {
	addrs, _ := net.InterfaceAddrs()
	for _, ia := range addrs {
		ipn, ok := ia.(*net.IPNet)
		if !ok {
			continue
		}
		addr := net.JoinHostPort(ipn.IP.String(), "443")
		if err := PublicOnly("tcp", addr, nil); err == nil {
			t.Errorf("own address %s allowed", addr)
		}
	}
}

func TestRoundTripper(t *testing.T) {
	srv, pub := startServer(t)
	hs := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write(pattern(30000))
	}))
	t.Cleanup(hs.Close)
	c, _ := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{srv}})
	defer c.Close()
	rt, err := c.NewRoundTripper(context.Background(), strings.TrimPrefix(hs.URL, "http://"))
	if err != nil {
		t.Fatal(err)
	}
	resp, err := (&http.Client{Transport: rt, Timeout: 30 * time.Second}).Get(hs.URL)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if !bytes.Equal(body, pattern(30000)) {
		t.Fatalf("body %d bytes", len(body))
	}
}

// nxInjector forwards to server but first answers every query with an unsigned NXDOMAIN.
func nxInjector(t *testing.T, server string) string {
	front, _ := net.ListenPacket("udp", "127.0.0.1:0")
	t.Cleanup(func() { front.Close() })
	saddr, _ := net.ResolveUDPAddr("udp", server)
	go func() {
		buf := make([]byte, 4096)
		for {
			n, from, err := front.ReadFrom(buf)
			if err != nil {
				return
			}
			msg := append([]byte(nil), buf[:n]...)
			if q, err := parseQuery(msg, zone{}); err == nil {
				front.WriteTo(appendAnswer(nil, q, rcodeNXDomain, nil), from)
			}
			go func() {
				back, _ := net.ListenPacket("udp", "127.0.0.1:0")
				defer back.Close()
				back.WriteTo(msg, saddr)
				back.SetReadDeadline(time.Now().Add(3 * time.Second))
				rb := make([]byte, 4096)
				if m, _, err := back.ReadFrom(rb); err == nil {
					front.WriteTo(rb[:m], from)
				}
			}()
		}
	}()
	return front.LocalAddr().String()
}

func TestForgedNXDomainIgnored(t *testing.T) {
	srv, pub := startServer(t)
	origin := startOrigin(t)
	c, _ := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{nxInjector(t, srv)}})
	defer c.Close()
	for range 3 {
		fetch(t, c, origin, 20<<10)
	}
}

func TestServerRestartSignedUnknownSession(t *testing.T) {
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	pc, _ := net.ListenPacket("udp", "127.0.0.1:0")
	addr := pc.LocalAddr().String()
	ctx1, cancel1 := context.WithCancel(context.Background())
	go Serve(ctx1, pc, ServerConfig{Zone: testZone, PrivateKey: priv, Dial: loopbackDial})
	origin := startOrigin(t)
	c, _ := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{addr}})
	defer c.Close()
	fetch(t, c, origin, 10<<10)

	cancel1() // the server restarts and forgets every session
	time.Sleep(100 * time.Millisecond)
	pc2, err := net.ListenPacket("udp", addr)
	if err != nil {
		t.Fatal(err)
	}
	ctx2, cancel2 := context.WithCancel(context.Background())
	t.Cleanup(cancel2)
	go Serve(ctx2, pc2, ServerConfig{Zone: testZone, PrivateKey: priv, Dial: loopbackDial})

	deadline := time.Now().Add(15 * time.Second)
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		conn, err := c.DialContext(ctx, "tcp", origin)
		cancel()
		if err == nil {
			conn.SetDeadline(time.Now().Add(3 * time.Second))
			fmt.Fprintf(conn, "%d\n", 100)
			got, err := io.ReadAll(conn)
			conn.Close()
			if err == nil && bytes.Equal(got, pattern(100)) {
				return
			}
		}
		if time.Now().After(deadline) {
			t.Fatal("client never recovered from the server restart")
		}
	}
}

func TestServerRestartUnsignedRecovery(t *testing.T) {
	if testing.Short() {
		t.Skip("waits out the liveness timeout")
	}
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	pc, _ := net.ListenPacket("udp", "127.0.0.1:0")
	addr := pc.LocalAddr().String()
	ctx1, cancel1 := context.WithCancel(context.Background())
	go Serve(ctx1, pc, ServerConfig{Zone: testZone, PrivateKey: priv, Dial: loopbackDial})
	origin := startOrigin(t)
	c, _ := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{nxInjector(t, addr)}})
	defer c.Close()
	fetch(t, c, origin, 10<<10)
	cancel1()
	time.Sleep(100 * time.Millisecond)
	// The new server never signs, as if its signing budget were exhausted.
	pc2, err := net.ListenPacket("udp", addr)
	if err != nil {
		t.Fatal(err)
	}
	ctx2, cancel2 := context.WithCancel(context.Background())
	t.Cleanup(cancel2)
	go func() {
		buf := make([]byte, 4096)
		go func() { <-ctx2.Done(); pc2.Close() }()
		for {
			n, from, err := pc2.ReadFrom(buf)
			if err != nil {
				return
			}
			if q, err := parseQuery(buf[:n], zone{}); err == nil {
				pc2.WriteTo(appendAnswer(nil, q, rcodeNXDomain, nil), from)
			}
		}
	}()
	start := time.Now()
	for time.Since(start) < 25*time.Second {
		c.mu.Lock()
		dead := c.sess != nil && c.sess.isClosed()
		c.mu.Unlock()
		if dead {
			t.Logf("session presumed lost after %v", time.Since(start).Round(time.Second))
			return
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatal("session never presumed lost")
}

func TestIdleSessionSurvivesResume(t *testing.T) {
	if testing.Short() {
		t.Skip("idles past the liveness timeout")
	}
	srv, pub := startServer(t)
	origin := startOrigin(t)
	c, _ := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{srv}})
	defer c.Close()
	fetch(t, c, origin, 1000)
	c.mu.Lock()
	sess := c.sess
	c.mu.Unlock()
	time.Sleep(deadSessionAfter + 2*time.Second)
	fetch(t, c, origin, 1000)
	time.Sleep(200 * time.Millisecond)
	c.mu.Lock()
	same := c.sess == sess && !sess.isClosed()
	c.mu.Unlock()
	if !same {
		t.Fatal("resuming after idle reset a healthy session")
	}
}

// A client idle past the server's idle timeout must not dial on the session the server ended:
// the first dial after the pause has to succeed, not fail with "server lost the session".
func TestIdleSessionRetiredBeforeServerForgetsIt(t *testing.T) {
	srv, pub := startServerWith(t, ServerConfig{Dial: loopbackDial, IdleTimeout: time.Second})
	origin := startOrigin(t)
	c, _ := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{srv}, SessionIdle: 500 * time.Millisecond})
	defer c.Close()
	fetch(t, c, origin, 1000)
	c.mu.Lock()
	sess := c.sess
	c.mu.Unlock()
	time.Sleep(2 * time.Second) // the server's sweep has ended the session by now
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	conn, err := c.DialContext(ctx, "tcp", origin)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(10 * time.Second))
	fmt.Fprintf(conn, "%d\n", 1000)
	got, err := io.ReadAll(conn)
	if err != nil || !bytes.Equal(got, pattern(1000)) {
		t.Fatalf("first dial after idling failed: %v (%d bytes)", err, len(got))
	}
	c.mu.Lock()
	fresh := c.sess != sess
	c.mu.Unlock()
	if !fresh {
		t.Fatal("the idle session was reused")
	}
}

// gatedResolver relays queries to server but holds the answers until release is called, so a
// test can end a Dial while its hello is answered but not yet delivered.
func gatedResolver(t *testing.T, server string) (addr string, held func() int, release func()) {
	front, _ := net.ListenPacket("udp", "127.0.0.1:0")
	t.Cleanup(func() { front.Close() })
	saddr, _ := net.ResolveUDPAddr("udp", server)
	var mu sync.Mutex
	gated := true
	type answer struct {
		msg []byte
		to  net.Addr
	}
	var queue []answer
	deliver := func(a answer) {
		mu.Lock()
		if gated {
			queue = append(queue, a)
			mu.Unlock()
			return
		}
		mu.Unlock()
		front.WriteTo(a.msg, a.to)
	}
	go func() {
		buf := make([]byte, 4096)
		for {
			n, from, err := front.ReadFrom(buf)
			if err != nil {
				return
			}
			msg := append([]byte(nil), buf[:n]...)
			go func() {
				back, _ := net.ListenPacket("udp", "127.0.0.1:0")
				defer back.Close()
				back.WriteTo(msg, saddr)
				back.SetReadDeadline(time.Now().Add(3 * time.Second))
				rb := make([]byte, 4096)
				if m, _, err := back.ReadFrom(rb); err == nil {
					deliver(answer{append([]byte(nil), rb[:m]...), from})
				}
			}()
		}
	}()
	held = func() int {
		mu.Lock()
		defer mu.Unlock()
		return len(queue)
	}
	release = func() {
		mu.Lock()
		gated = false
		q := queue
		queue = nil
		mu.Unlock()
		for _, a := range q {
			front.WriteTo(a.msg, a.to)
		}
	}
	return front.LocalAddr().String(), held, release
}

// A Dial whose context ends mid-handshake, as when another transport wins a race, leaves a
// session that never carries a stream once the hello's answer arrives. The server ends it 15 s
// after the hello, so a later Dial must handshake afresh rather than fail with "server lost the
// session".
func TestCanceledDialDoesNotStrandASession(t *testing.T) {
	if testing.Short() {
		t.Skip("waits out the server's unused-session limit")
	}
	srv, pub := startServer(t)
	origin := startOrigin(t)
	resolver, held, release := gatedResolver(t, srv)
	c, _ := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{resolver}, TCP: "off"})
	defer c.Close()

	ctx, cancel := context.WithCancel(context.Background())
	dialed := make(chan error, 1)
	go func() {
		conn, err := c.DialContext(ctx, "tcp", origin)
		if err == nil {
			conn.Close()
		}
		dialed <- err
	}()
	for deadline := time.Now().Add(5 * time.Second); held() == 0; time.Sleep(10 * time.Millisecond) {
		if time.Now().After(deadline) {
			t.Fatal("the server never answered the hello")
		}
	}
	cancel()
	if err := <-dialed; err == nil {
		t.Fatal("the dial succeeded with its hello answer held back")
	}
	release()

	var unused bool
	for deadline := time.Now().Add(5 * time.Second); !unused; time.Sleep(10 * time.Millisecond) {
		if time.Now().After(deadline) {
			t.Fatal("the abandoned handshake never left an established, unused session")
		}
		c.mu.Lock()
		if cs := c.sess; cs != nil && cs.established {
			cs.mu.Lock()
			unused = !cs.used && !cs.closed
			cs.mu.Unlock()
		}
		c.mu.Unlock()
	}
	time.Sleep(16 * time.Second) // past the server's 15 s limit for a session with no data
	fetch(t, c, origin, 1000)
}

func TestPublicOnlyRefusesOwnAddressesViaNAT64(t *testing.T) {
	addrs, _ := net.InterfaceAddrs()
	for _, ia := range addrs {
		ipn, ok := ia.(*net.IPNet)
		if !ok || ipn.IP.To4() == nil {
			continue
		}
		v4 := ipn.IP.To4()
		for _, wrapped := range []string{
			fmt.Sprintf("[64:ff9b::%x%02x:%x%02x]:443", v4[0], v4[1], v4[2], v4[3]),
			fmt.Sprintf("[2002:%x%02x:%x%02x::1]:443", v4[0], v4[1], v4[2], v4[3]),
		} {
			if err := PublicOnly("tcp", wrapped, nil); err == nil {
				t.Errorf("own address %s allowed as %s", v4, wrapped)
			}
		}
	}
}

func TestResetStreamIsNeverDialed(t *testing.T) {
	var dials atomic.Int32
	srv := &Server{ctx: context.Background(), cfg: ServerConfig{Dial: func(ctx context.Context, network, addr string) (net.Conn, error) {
		dials.Add(1)
		return nil, errors.New("unreachable")
	}, Logger: slog.New(slog.DiscardHandler)}}
	ss := &srvSession{session: newSession(false, streamConfig{StreamWindow: 1 << 16, SendBuffer: 1 << 16, MaxStreams: 4}, nil, nil), srv: srv}
	ss.mu.Lock()
	st := ss.peerStream(0)
	dest := "example.com:443"
	st.onData(0, append([]byte{byte(len(dest))}, dest...), false)
	st.abort(ErrStreamReset) // a RESET in the same packet as the destination
	ss.mu.Unlock()
	ss.serveStream(st)
	if n := dials.Load(); n != 0 {
		t.Fatalf("dialed a reset stream %d times", n)
	}
}

func TestResetAfterFinStillDialsAndStaysCounted(t *testing.T) {
	var dials atomic.Int32
	srv := &Server{ctx: context.Background(), cfg: ServerConfig{Dial: func(ctx context.Context, network, addr string) (net.Conn, error) {
		dials.Add(1)
		return nil, errors.New("unreachable")
	}, Logger: slog.New(slog.DiscardHandler)}}
	ss := &srvSession{session: newSession(false, streamConfig{StreamWindow: 1 << 16, SendBuffer: 1 << 16, MaxStreams: 4}, nil, nil), srv: srv}
	ss.acceptFn = func(st *stream) bool {
		if st != nil {
			st.serving = true
		}
		return true
	}
	dest := "example.com:443"
	var pkt []byte
	pkt = appendStream(pkt, 0, 0, append([]byte{byte(len(dest))}, dest...), true, false)
	pkt = ctrl{typ: frReset, id: 0}.appendTo(pkt)
	ss.mu.Lock()
	if _, err := ss.receive(0, pkt, time.Now()); err != nil {
		t.Fatal(err)
	}
	st := ss.streams[0]
	ss.mu.Unlock()
	if st == nil {
		t.Fatal("stream released while serveStream still owns it")
	}
	ss.serveStream(st)
	if n := dials.Load(); n != 1 {
		t.Fatalf("FIN then RESET (client closed after writing): %d dials, want 1", n)
	}
}

func TestWriteThenCloseDeliversRequest(t *testing.T) {
	srv, pub := startServer(t)
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	defer ln.Close()
	got := make(chan []byte, 1)
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		b, _ := io.ReadAll(c)
		got <- b
	}()
	c, _ := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{srv}})
	defer c.Close()
	conn, err := c.DialContext(context.Background(), "tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	req := []byte("fire and forget\n")
	conn.Write(req)
	conn.Close() // Close promises written data is still delivered
	select {
	case b := <-got:
		if !bytes.Equal(b, req) {
			t.Fatalf("destination got %q, want %q", b, req)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("request written before Close never reached the destination")
	}
}

// nameLimiter forwards only queries whose name is at most max characters, like resolvers in
// networks that drop long names; longer ones get SERVFAIL.
func nameLimiter(t *testing.T, server string, max int) string {
	front, _ := net.ListenPacket("udp", "127.0.0.1:0")
	t.Cleanup(func() { front.Close() })
	saddr, _ := net.ResolveUDPAddr("udp", server)
	z, _ := parseZone(testZone)
	go func() {
		buf := make([]byte, 4096)
		for {
			n, from, err := front.ReadFrom(buf)
			if err != nil {
				return
			}
			msg := append([]byte(nil), buf[:n]...)
			q, err := parseQuery(msg, z)
			if err != nil {
				continue
			}
			if end, _ := skipName(msg, hdrLen); end-hdrLen-2 > max {
				front.WriteTo(appendAnswer(nil, q, rcodeServFail, nil), from)
				continue
			}
			go func() {
				back, _ := net.ListenPacket("udp", "127.0.0.1:0")
				defer back.Close()
				back.WriteTo(msg, saddr)
				back.SetReadDeadline(time.Now().Add(3 * time.Second))
				rb := make([]byte, 4096)
				if m, _, err := back.ReadFrom(rb); err == nil {
					front.WriteTo(rb[:m], from)
				}
			}()
		}
	}()
	return front.LocalAddr().String()
}

func TestNameProbeRespectsShortNameLimit(t *testing.T) {
	srv, pub := startServer(t)
	origin := startOrigin(t)
	c, _ := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{nameLimiter(t, srv, 101)}})
	defer c.Close()
	for range 5 {
		fetch(t, c, origin, 2000)
	}
	// An upload large enough to need full-size queries must still get through.
	conn, err := c.DialContext(context.Background(), "tcp", origin)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(20 * time.Second))
	up := make([]byte, 20<<10)
	rand.Read(up)
	go func() {
		conn.Write([]byte("echo\n"))
		conn.Write(up)
	}()
	got := make([]byte, len(up))
	if _, err := io.ReadFull(conn, got); err != nil {
		t.Fatalf("upload through a 101-char resolver: %v", err)
	}
	c.mu.Lock()
	nameLen := c.paths[0].nameLen
	c.mu.Unlock()
	if nameLen > 101 {
		t.Fatalf("path promoted to %d-char names through a 101-char resolver", nameLen)
	}
}

func TestRelayClosesDestinationWhenStreamDies(t *testing.T) {
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	defer ln.Close()
	closed := make(chan struct{})
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		c.Read(make([]byte, 1)) // returns once the relay closes its end
		close(closed)
		c.Close()
	}()
	up, _ := net.Dial("tcp", ln.Addr().String())
	s := newSession(false, streamConfig{StreamWindow: 1 << 16, SendBuffer: 1 << 16, MaxStreams: 4}, nil, nil)
	s.mu.Lock()
	st := s.peerStream(0)
	st.onData(0, nil, true) // the client half-closed at once
	s.mu.Unlock()
	go relay(&Conn{st: st}, up)
	time.Sleep(100 * time.Millisecond)
	s.mu.Lock()
	st.abort(ErrStreamReset)
	s.mu.Unlock()
	select {
	case <-closed:
	case <-time.After(3 * time.Second):
		t.Fatal("destination left open after the stream died")
	}
}

func TestResetAfterGappedFinDoesNotSpin(t *testing.T) {
	s, st := newTestStream(t)
	s.client = false
	st.onData(10, []byte("tail"), true) // FIN beyond a gap at [0,10)
	reset := ctrl{typ: frReset, id: st.id}.appendTo(nil)
	s.mu.Lock()
	s.receive(0, reset, time.Now())
	s.mu.Unlock()
	done := make(chan error, 1)
	go func() {
		_, err := (&Conn{st: st}).Read(make([]byte, 10))
		done <- err
	}()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("Read returned data from a gap")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Read never returned after a reset with a gap before FIN")
	}
}

func TestIdleClientStopsPolling(t *testing.T) {
	srv, pub := startServer(t)
	origin := startOrigin(t)
	fwd, count := countingForwarder(t, srv)
	c, _ := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{fwd}})
	defer c.Close()
	fetch(t, c, origin, 2000)
	time.Sleep(2 * time.Second) // let acks and closes settle
	before := count.Load()
	time.Sleep(3 * time.Second)
	if n := count.Load() - before; n > 0 {
		t.Fatalf("idle client sent %d queries in 3s", n)
	}
}

func countingForwarder(t *testing.T, server string) (string, *atomic.Int64) {
	var n atomic.Int64
	front, _ := net.ListenPacket("udp", "127.0.0.1:0")
	t.Cleanup(func() { front.Close() })
	saddr, _ := net.ResolveUDPAddr("udp", server)
	back, _ := net.ListenPacket("udp", "127.0.0.1:0")
	t.Cleanup(func() { back.Close() })
	var mu sync.Mutex
	var client net.Addr
	go func() {
		buf := make([]byte, 4096)
		for {
			m, from, err := front.ReadFrom(buf)
			if err != nil {
				return
			}
			n.Add(1)
			mu.Lock()
			client = from
			mu.Unlock()
			back.WriteTo(buf[:m], saddr)
		}
	}()
	go func() {
		buf := make([]byte, 4096)
		for {
			m, _, err := back.ReadFrom(buf)
			if err != nil {
				return
			}
			mu.Lock()
			to := client
			mu.Unlock()
			front.WriteTo(buf[:m], to)
		}
	}()
	return front.LocalAddr().String(), &n
}

func TestHelloFloodDoesNotStallSessions(t *testing.T) {
	srv, pub := startServer(t)
	origin := startOrigin(t)
	c, _ := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{srv}})
	defer c.Close()
	fetch(t, c, origin, 1000)
	z, _ := parseZone(testZone)
	flood, _ := net.ListenPacket("udp", "127.0.0.1:0")
	defer flood.Close()
	saddr, _ := net.ResolveUDPAddr("udp", srv)
	stop := make(chan struct{})
	// A fixed 5000 hellos/s, far beyond what the server can handshake under -race. An unpaced
	// flood from this process would instead measure how fast the host reads packets, and on a
	// small CI runner crowd out the client's queries in the kernel.
	go func() {
		eph := make([]byte, 2+pubLen)
		tick := time.NewTicker(10 * time.Millisecond)
		defer tick.Stop()
		for i := uint16(1); ; {
			select {
			case <-stop:
				return
			case <-tick.C:
			}
			for range 50 {
				rand.Read(eph[2:])
				flood.WriteTo(appendQuery(nil, i, eph, z, typeTXT, 1232), saddr)
				i++
			}
		}
	}()
	defer close(stop)
	time.Sleep(200 * time.Millisecond)
	if d := fetch(t, c, origin, 50<<10); d > 10*time.Second {
		t.Fatalf("50 KiB took %v during a hello flood", d)
	}
}

func TestDefaultDialAllowsOnlyWebPorts(t *testing.T) {
	cfg := ServerConfig{}
	cfg.setDefaults()
	if _, err := cfg.Dial(context.Background(), "tcp", "8.8.8.8:25"); err == nil || !strings.Contains(err.Error(), "port not allowed") {
		t.Fatalf("port 25: %v", err)
	}
}

type orderCache struct {
	mu     sync.Mutex
	stores [][]string
}

func (o *orderCache) Load() []string { return nil }
func (o *orderCache) Store(r []string) {
	time.Sleep(5 * time.Millisecond) // a slow cache must still see snapshots in order
	o.mu.Lock()
	o.stores = append(o.stores, slices.Clone(r))
	o.mu.Unlock()
}

func TestDiscoveryCachesTCPVerifiedResolvers(t *testing.T) {
	srv, pub := startServer(t)
	origin := startOrigin(t)
	// A resolver reachable only over TCP: UDP to it goes nowhere.
	tcpOnly := tcpOnlyResolver(t, srv)
	cache := &orderCache{}
	c, err := NewClient(Config{Zone: testZone, ServerKey: pub, TCP: "on", Discovery: Discovery{
		Enabled: true, Candidates: []string{tcpOnly}, Cache: cache, noBundled: true,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	fetch(t, c, origin, 2000)
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		cache.mu.Lock()
		n := len(cache.stores)
		last := []string(nil)
		if n > 0 {
			last = cache.stores[n-1]
		}
		cache.mu.Unlock()
		if slices.Contains(last, tcpOnly) {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("TCP-verified resolver never cached")
}

// tcpOnlyResolver accepts RFC 7766 TCP queries and forwards them to server over UDP.
func tcpOnlyResolver(t *testing.T, server string) string {
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	t.Cleanup(func() { ln.Close() })
	saddr, _ := net.ResolveUDPAddr("udp", server)
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				var wmu sync.Mutex
				var hdr [2]byte
				for {
					if _, err := io.ReadFull(c, hdr[:]); err != nil {
						return
					}
					msg := make([]byte, int(hdr[0])<<8|int(hdr[1]))
					if _, err := io.ReadFull(c, msg); err != nil {
						return
					}
					go func() {
						back, _ := net.ListenPacket("udp", "127.0.0.1:0")
						defer back.Close()
						back.WriteTo(msg, saddr)
						back.SetReadDeadline(time.Now().Add(3 * time.Second))
						rb := make([]byte, 4096)
						m, _, err := back.ReadFrom(rb)
						if err != nil {
							return
						}
						wmu.Lock()
						c.Write(append([]byte{byte(m >> 8), byte(m)}, rb[:m]...))
						wmu.Unlock()
					}()
				}
			}()
		}
	}()
	return ln.Addr().String()
}

func TestAmplificationBudget(t *testing.T) {
	s := &Server{cfg: ServerConfig{MaxBytesPerPrefix: 4000}, rrl: make(map[netip.Prefix]*byteBucket)}
	victim := &net.UDPAddr{IP: net.ParseIP("192.0.2.7"), Port: 53}
	// Unamplified replies never draw on the budget, so spoofed junk can't drain it.
	for range 1000 {
		if !s.allowAmplification(victim, 0) {
			t.Fatal("zero-amplification reply refused")
		}
	}
	allowed := 0
	for range 100 {
		if s.allowAmplification(victim, 500) {
			allowed++
		}
	}
	if allowed > 2 {
		t.Fatalf("%d amplified answers allowed against a 1000-byte burst", allowed)
	}
	// A full table sends new prefixes to the shared overflow budget, not unlimited.
	for i := range maxRRLPrefixes - len(s.rrl) {
		s.rrl[netip.PrefixFrom(netip.AddrFrom4([4]byte{10, byte(i >> 8), byte(i), 0}), 24)] = &byteBucket{}
	}
	other := &net.UDPAddr{IP: net.ParseIP("198.51.100.1"), Port: 53}
	n := 0
	for range 100 {
		if s.allowAmplification(other, 500) {
			n++
		}
	}
	if n > 2 || len(s.rrl) > maxRRLPrefixes {
		t.Fatalf("overflow: %d allowed, table %d", n, len(s.rrl))
	}
}

func TestSafeQNameMustFitHello(t *testing.T) {
	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	zone := strings.Repeat("z", 40) + ".example.com" // a hello needs ~55 chars, plus this
	_, err := NewClient(Config{Zone: zone, ServerKey: pub, Resolvers: []string{"127.0.0.1:53"}})
	if err == nil {
		t.Fatal("SafeQName 101 accepted for a zone that pushes the hello past it")
	}
	c, err := NewClient(Config{Zone: zone, ServerKey: pub, Resolvers: []string{"127.0.0.1:53"}, SafeQName: 140})
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
}

func TestCloseFlushesNewestCacheSnapshot(t *testing.T) {
	srv, pub := startServer(t)
	origin := startOrigin(t)
	good := lossyForwarder(t, srv, 0, 5*time.Millisecond, 0)
	cache := &orderCache{}
	c, _ := NewClient(Config{Zone: testZone, ServerKey: pub, Discovery: Discovery{
		Enabled: true, Candidates: []string{good}, Cache: cache, noBundled: true,
	}})
	fetch(t, c, origin, 1000)
	c.Close()
	cache.mu.Lock()
	defer cache.mu.Unlock()
	if n := len(cache.stores); n == 0 || !slices.Contains(cache.stores[n-1], good) {
		t.Fatalf("after Close the cache holds %v, want the verified %s", cache.stores, good)
	}
}

func TestNewRoundTripperFailsWhenDestinationUnreachable(t *testing.T) {
	srv, pub := startServer(t)
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	dead := ln.Addr().String()
	ln.Close() // nothing listens here: the server's dial is refused
	c, _ := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{srv}})
	defer c.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if _, err := c.NewRoundTripper(ctx, dead); err == nil {
		t.Fatal("NewRoundTripper succeeded though the destination is unreachable")
	}
}

// errInjector forwards to server but first answers every query with a forged error.
func errInjector(t *testing.T, server string, rcode byte, tc bool) string {
	front, _ := net.ListenPacket("udp", "127.0.0.1:0")
	t.Cleanup(func() { front.Close() })
	saddr, _ := net.ResolveUDPAddr("udp", server)
	go func() {
		buf := make([]byte, 4096)
		for {
			n, from, err := front.ReadFrom(buf)
			if err != nil {
				return
			}
			msg := append([]byte(nil), buf[:n]...)
			if q, err := parseQuery(msg, zone{}); err == nil {
				forged := appendAnswer(nil, q, rcode, nil)
				if tc {
					forged[2] |= 0x02
				}
				front.WriteTo(forged, from)
			}
			go func() {
				back, _ := net.ListenPacket("udp", "127.0.0.1:0")
				defer back.Close()
				back.WriteTo(msg, saddr)
				back.SetReadDeadline(time.Now().Add(3 * time.Second))
				rb := make([]byte, 4096)
				if m, _, err := back.ReadFrom(rb); err == nil {
					front.WriteTo(rb[:m], from)
				}
			}()
		}
	}()
	return front.LocalAddr().String()
}

func TestForgedErrorsDoNotCutQueriesShort(t *testing.T) {
	for _, tc := range []struct {
		name  string
		rcode byte
		trunc bool
	}{{"refused", rcodeRefused, false}, {"servfail", rcodeServFail, false}, {"truncated", rcodeOK, true}} {
		t.Run(tc.name, func(t *testing.T) {
			srv, pub := startServer(t)
			origin := startOrigin(t)
			c, _ := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{errInjector(t, srv, tc.rcode, tc.trunc)}})
			defer c.Close()
			if d := fetch(t, c, origin, 50<<10); d > 5*time.Second {
				t.Fatalf("50 KiB took %v under forged %s answers", d, tc.name)
			}
			c.mu.Lock()
			p := c.paths[0]
			qtype, ceil := p.qtype, p.sizeCeil
			c.mu.Unlock()
			if qtype != typeTXT || ceil != limitClasses {
				t.Fatalf("forged answers changed the path: qtype=%d sizeCeil=%d", qtype, ceil)
			}
		})
	}
}

// qminResolver mimics a resolver doing QNAME minimisation (RFC 9156): before each query it asks
// for every ancestor under the zone with type A, one label at a time, and gives up with that
// answer's rcode unless it's NOERROR.
func qminResolver(t *testing.T, server string) string {
	front, _ := net.ListenPacket("udp", "127.0.0.1:0")
	t.Cleanup(func() { front.Close() })
	saddr, _ := net.ResolveUDPAddr("udp", server)
	z, _ := parseZone(testZone)
	ask := func(msg []byte) ([]byte, error) {
		back, _ := net.ListenPacket("udp", "127.0.0.1:0")
		defer back.Close()
		back.WriteTo(msg, saddr)
		back.SetReadDeadline(time.Now().Add(3 * time.Second))
		rb := make([]byte, 4096)
		n, _, err := back.ReadFrom(rb)
		return rb[:n], err
	}
	go func() {
		buf := make([]byte, 4096)
		for {
			n, from, err := front.ReadFrom(buf)
			if err != nil {
				return
			}
			msg := append([]byte(nil), buf[:n]...)
			go func() {
				q, err := parseQuery(msg, z)
				if err != nil {
					return
				}
				// Labels left of the zone, rightmost first.
				var labels [][]byte
				for i := hdrLen; msg[i] != 0; i += 1 + int(msg[i]) {
					labels = append(labels, msg[i+1:i+1+int(msg[i])])
				}
				zl := strings.Count(testZone, ".") + 1
				for k := 1; k < len(labels)-zl+1; k++ {
					var name []byte
					for _, l := range labels[len(labels)-zl-k:] {
						name = append(name, byte(len(l)))
						name = append(name, l...)
					}
					m := []byte{0, 7, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0}
					m = append(append(m, name...), 0, 0, 1, 0, 1) // type A
					a, err := ask(m)
					if err != nil || a[3]&0x0F != rcodeOK {
						code := byte(rcodeServFail)
						if err == nil {
							code = a[3] & 0x0F
						}
						front.WriteTo(appendAnswer(nil, q, code, nil), from)
						return
					}
				}
				if a, err := ask(msg); err == nil {
					front.WriteTo(a, from)
				}
			}()
		}
	}()
	return front.LocalAddr().String()
}

func TestTunnelThroughQNAMEMinimisingResolver(t *testing.T) {
	srv, pub := startServer(t)
	origin := startOrigin(t)
	c, _ := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{qminResolver(t, srv)}})
	defer c.Close()
	fetch(t, c, origin, 20<<10)
}

func TestApexSOAAndNoData(t *testing.T) {
	z, _ := parseZone(testZone)
	for _, tc := range []struct {
		name  string
		qtype uint16
		an    int
	}{{testZone, typeSOA, 1}, {testZone, 2 /* NS */, 0}, {"abc." + testZone, 1 /* A */, 0}} {
		var name []byte
		for _, l := range strings.Split(tc.name, ".") {
			name = append(append(name, byte(len(l))), l...)
		}
		m := append([]byte{0, 9, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0}, name...)
		m = append(m, 0, byte(tc.qtype>>8), byte(tc.qtype), 0, 1)
		q, err := parseQuery(m, z)
		if err != nil || !q.inZone {
			t.Fatalf("%s: inZone=%v err=%v", tc.name, q != nil && q.inZone, err)
		}
		ans := appendNoData(nil, q, z)
		a, err := parseAnswer(ans)
		if err != nil || a.rcode != rcodeOK || ans[2]&0x04 == 0 || int(ans[7]) != tc.an {
			t.Fatalf("%s type %d: rcode=%d aa=%v an=%d err=%v", tc.name, tc.qtype, a.rcode, ans[2]&0x04 != 0, ans[7], err)
		}
	}
}

func TestTCPOnlyThroughCustomDialer(t *testing.T) {
	srv, pub := startServer(t)
	origin := startOrigin(t)
	res := tcpOnlyResolver(t, srv)
	var dials atomic.Int32
	c, err := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{res}, TCP: "only",
		DialTCP: func(ctx context.Context, network, addr string) (net.Conn, error) {
			dials.Add(1)
			return (&net.Dialer{}).DialContext(ctx, network, addr)
		}})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	fetch(t, c, origin, 30<<10)
	if dials.Load() == 0 {
		t.Fatal("custom TCP dialer unused")
	}
}

// An idle session must go quiet, even with a resolver that never answers: acking, LOST reports
// and probes of unverified paths mustn't sustain each other.
func TestIdleSessionGoesQuiet(t *testing.T) {
	if testing.Short() {
		t.Skip("idles for several seconds")
	}
	srv, pub := startServer(t)
	origin := startOrigin(t)
	good, sent := countingForwarder(t, srv)
	var deadSent atomic.Int64
	dead, _ := net.ListenPacket("udp", "127.0.0.1:0")
	defer dead.Close()
	go func() {
		b := make([]byte, 65536)
		for {
			if _, _, err := dead.ReadFrom(b); err != nil {
				return
			}
			deadSent.Add(1)
		}
	}()
	c, _ := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{good, dead.LocalAddr().String()}})
	defer c.Close()
	fetch(t, c, origin, 20000)
	time.Sleep(3 * time.Second) // let acks, LOST reports and the dead path's probes settle
	a, d := sent.Load(), deadSent.Load()
	time.Sleep(6 * time.Second)
	if n := sent.Load() - a + deadSent.Load() - d; n > 0 {
		t.Fatalf("idle session sent %d queries", n)
	}
	fetch(t, c, origin, 20000)
}

// Polls the server holds are overtaken by data queries it answers at once. That's no sign of
// loss: an echo after a bulk fetch (which leaves polls held) must keep the full name length.
func TestHeldPollsAreNotLost(t *testing.T) {
	srv, pub := startServer(t)
	origin := startOrigin(t)
	c, err := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{srv}})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	fetch(t, c, origin, 1<<20)
	conn, err := c.DialContext(context.Background(), "tcp", origin)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(30 * time.Second))
	up := make([]byte, 200<<10)
	rand.Read(up)
	go func() {
		conn.Write([]byte("echo\n"))
		conn.Write(up)
	}()
	got := make([]byte, len(up))
	if _, err := io.ReadFull(conn, got); err != nil || !bytes.Equal(got, up) {
		t.Fatal("echo failed", err)
	}
	for _, p := range c.PathStats() {
		if p.NameLen != maxName {
			t.Errorf("name length demoted to %d on a lossless path", p.NameLen)
		}
	}
}

// The server releases held polls in arrival order, which the client relies on to tell a lost
// poll from a held one. A later poll whose LOST frame makes data pending again must not be
// answered ahead of an older held poll.
// rawSession handshakes with a fresh server over UDP by hand, for tests that need to control
// exactly which queries the server sees. It returns functions to send a query, collect the
// answers that arrive within a duration, and seal the next upstream packet (hold 500 ms), and
// the address of an origin the server can reach.
func rawSession(t *testing.T) (send func(uint16, []byte), recv func(time.Duration) []*answer, packet func([]byte) []byte, origin string, down *packetCipher) {
	t.Helper()
	srv, pub := startServer(t)
	origin = startOrigin(t)
	z, _ := parseZone(testZone)
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	saddr, _ := net.ResolveUDPAddr("udp", srv)
	send = func(txn uint16, payload []byte) {
		pc.WriteTo(appendQuery(nil, txn, payload, z, typeTXT, 1232), saddr)
	}
	// recv collects the answers that arrive within d, in order.
	recv = func(d time.Duration) []*answer {
		var out []*answer
		buf := make([]byte, 4096)
		pc.SetReadDeadline(time.Now().Add(d))
		for {
			n, _, err := pc.ReadFrom(buf)
			if err != nil {
				return out
			}
			if a, err := parseAnswer(buf[:n]); err == nil {
				out = append(out, a)
			}
		}
	}
	eph, _ := ecdh.X25519().GenerateKey(rand.Reader)
	send(1, append([]byte{0, 0}, eph.PublicKey().Bytes()...))
	hello := recv(500 * time.Millisecond)
	if len(hello) == 0 {
		t.Fatal("no hello reply")
	}
	sid, up, down, err := clientFinish(pub, eph, hello[0].txt)
	if err != nil {
		t.Fatal(err)
	}
	pn := uint64(0)
	packet = func(frames []byte) []byte {
		plain := append([]byte{0x50}, frames...) // hold 500 ms
		for len(plain) < 5 {
			plain = append(plain, frPadding)
		}
		pn++
		var hdr [4]byte
		binary.BigEndian.PutUint16(hdr[:], sid)
		binary.BigEndian.PutUint16(hdr[2:], uint16(pn-1))
		sealed := up.seal(nil, hdr[:], plain, pn-1)
		m := up.mask(sealed)
		return append([]byte{hdr[0], hdr[1], hdr[2] ^ m[0], hdr[3] ^ m[1]}, sealed...)
	}
	return send, recv, packet, origin, down
}

// A resolver's retry of a query that carried data is a duplicate packet. The server doesn't
// reapply it, but must still answer it at once, as it did the original, not hold it like a poll.
func TestDuplicateDataQueryAnsweredAtOnce(t *testing.T) {
	send, recv, packet, origin, _ := rawSession(t)
	open := append([]byte{byte(len(origin))}, origin...)
	data := packet(appendStream(nil, 0, 0, open, false, false)) // pn 0: opens a stream
	send(2, data)
	send(3, packet([]byte{frPing})) // pn 1: collects OPENED, so nothing is left to send
	recv(300 * time.Millisecond)
	send(4, data) // the retry
	if got := recv(200 * time.Millisecond); len(got) != 1 || got[0].txn != 4 {
		t.Fatalf("the retried data query wasn't answered at once (%d answers in 200ms)", len(got))
	}
}

func TestHeldPollsReleasedOldestFirst(t *testing.T) {
	send, recv, packet, origin, _ := rawSession(t)
	open := append([]byte{byte(len(origin))}, origin...)
	open = append(open, "echo\n"...)
	send(2, packet(appendStream(nil, 0, 0, open, false, false))) // pn 0: opens the stream
	send(3, packet([]byte{frPing}))                              // pn 1: a poll for OPENED
	recv(300 * time.Millisecond)
	// pn 2 sends "hello". Its answer goes out at once, before the relay even forwards the bytes,
	// so the echo is pending, with no poll held, by the time pn 3 arrives.
	send(4, packet(appendStream(nil, 0, uint64(len(open)), []byte("hello"), false, false)))
	recv(200 * time.Millisecond)
	send(5, packet([]byte{frPing})) // pn 3: carries the echoed "hello"
	if got := recv(300 * time.Millisecond); len(got) != 1 || got[0].txn != 5 {
		t.Fatalf("pn 3 should carry the echo: got %d answers", len(got))
	}
	send(6, packet([]byte{frPing})) // pn 4: held, nothing left to send
	recv(100 * time.Millisecond)
	send(7, packet(appendLost(nil, []uint64{3}))) // pn 5: reports pn 3's answer lost
	got := recv(300 * time.Millisecond)
	if len(got) == 0 || got[0].txn != 6 {
		var txns []uint16
		for _, a := range got {
			txns = append(txns, a.txn)
		}
		t.Fatalf("answers %v: the poll held first (txn 6) must be answered before the later one (txn 7)", txns)
	}
}

// A stream whose destination prefix is malformed is refused with RESET_STREAM code 1, so a client
// waiting for OPENED gets an answer; a graceful close would leave it waiting forever.
func TestMalformedDestinationIsRefused(t *testing.T) {
	send, recv, packet, _, down := rawSession(t)
	upPN := uint64(0)
	largest, have := uint64(0), false
	frames := appendStream(nil, 0, 0, []byte{0}, false, false) // a zero-length destination
	for i := range 8 {
		send(uint16(10+i), packet(frames))
		frames = []byte{frPing}
		for _, a := range recv(700 * time.Millisecond) {
			pn, plain, err := down.openDown(a.txt, upPN, func(tr uint16) uint64 { return decodePN(largest, have, tr) })
			if err != nil {
				continue
			}
			largest, have = max(largest, pn), true
			found := false
			parseFrames(plain[2:], func(f *frame) error {
				if f.typ == frReset && f.id == 0 {
					if f.val != 1 {
						t.Fatalf("reset code %d, want 1 (refused as unreachable)", f.val)
					}
					found = true
				}
				return nil
			})
			if found {
				return
			}
		}
		upPN++
	}
	t.Fatal("the server never refused the malformed stream")
}

// Closing a stream whose data is acked but whose FIN isn't must still deliver the FIN, not a
// RESET that would abort the stream at the server and lose the request.
func TestCloseAfterDataAckedStillSendsFin(t *testing.T) {
	cfg := streamConfig{StreamWindow: 1 << 16, SendBuffer: 1 << 16, MaxStreams: 4}
	c, s := newSession(true, cfg, nil, nil), newSession(false, cfg, nil, nil)
	now := time.Now()
	c.mu.Lock()
	st, _ := c.openStream()
	st.sbuf = append(st.sbuf, "\x03a:1request"...)
	st.spending.add(0, uint64(len(st.sbuf)))
	pn, b, _, _ := c.build(nil, 1000, now, 0)
	c.acked(c.sent[pn]) // the data is acknowledged; the FIN isn't sent yet
	c.mu.Unlock()
	s.receive(pn, b, now)
	(&Conn{st: st}).Close()
	c.mu.Lock()
	pn, b, _, _ = c.build(nil, 1000, now, 0)
	c.mu.Unlock()
	s.receive(pn, b, now)
	if ss := s.streams[0]; ss == nil || !ss.rfin || ss.reset {
		t.Fatal("the server got a RESET instead of the FIN")
	}
}

// A control queue that overflows ends the session rather than silently dropping frames that
// callers already counted as sent, and the removed stream's slot is given back only once.
func TestControlQueueOverflow(t *testing.T) {
	cfg := streamConfig{StreamWindow: 1 << 16, SendBuffer: 1 << 16, MaxStreams: 4}
	now := time.Now()
	s := newSession(false, cfg, nil, nil)
	for id := range uint64(2000) {
		s.receive(id, appendStream(nil, id, 0, []byte("x"), false, false), now)
		if s.closed {
			break
		}
	}
	if !s.closed {
		t.Fatal("the full queue dropped frames instead of ending the session")
	}

	// Slot accounting: one stream holds a slot, plus one held by another session.
	s = newSession(false, cfg, nil, nil)
	slots := int64(1)
	s.acceptFn = func(st *stream) bool {
		if st == nil {
			slots++
		}
		return true
	}
	s.streamGone = func() { slots-- }
	s.receive(0, appendStream(nil, 0, 0, []byte("x"), false, false), now)
	for len(s.ctrlQ) < maxCtrlQueue {
		s.ctrlQ = append(s.ctrlQ, ctrl{typ: frOpened, id: uint64(100 + len(s.ctrlQ))})
	}
	st := s.streams[0]
	st.rclosed, st.saborted, st.serving = true, true, false
	s.maybeRemove(st) // its RESET overflows the queue and ends the session
	if !s.closed || slots != 1 {
		t.Fatalf("closed=%v slots=%d, want closed with 1 (the other session's)", s.closed, slots)
	}
}

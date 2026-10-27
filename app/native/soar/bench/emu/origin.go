package emu

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"math/rand/v2"
	"net"
	"strconv"
	"strings"
	"time"
)

// StartOrigin starts a TCP origin: a client sends "N\n" and receives the first N bytes of payload,
// then the connection closes. It measures time-to-first-byte and throughput without HTTP or TLS.
func StartOrigin(ctx context.Context) (string, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	go func() {
		<-ctx.Done()
		ln.Close()
	}()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go serveOrigin(c)
		}
	}()
	return ln.Addr().String(), nil
}

func serveOrigin(c net.Conn) {
	defer c.Close()
	c.SetDeadline(time.Now().Add(10 * time.Minute))
	line, err := bufio.NewReader(c).ReadString('\n')
	if err != nil {
		return
	}
	n, err := strconv.Atoi(strings.TrimSpace(line))
	if err != nil || n < 0 {
		return
	}
	src := payload()
	buf := make([]byte, 16<<10)
	for sent := 0; sent < n; {
		k := min(len(buf), n-sent)
		src.Read(buf[:k])
		if _, err := c.Write(buf[:k]); err != nil {
			return
		}
		sent += k
	}
}

// payload returns the byte stream every origin response is a prefix of: seeded pseudo-random
// bytes that never repeat, so they don't compress, as real (encrypted) traffic doesn't, and tunnels
// that compress their payload get no head start. The fetcher regenerates it to verify integrity.
func payload() *rand.ChaCha8 { return rand.NewChaCha8([32]byte{}) }

// Fetch requests n bytes over conn and returns time to first byte and total time; it verifies the
// bytes against payload.
func Fetch(conn net.Conn, n int, deadline time.Time) (ttfb, total time.Duration, err error) {
	defer conn.Close()
	conn.SetDeadline(deadline)
	start := time.Now()
	if _, err := fmt.Fprintf(conn, "%d\n", n); err != nil {
		return 0, 0, err
	}
	buf := make([]byte, 32<<10)
	want := make([]byte, len(buf))
	src := payload()
	got := 0
	for got < n {
		k, err := conn.Read(buf)
		if k > 0 && got == 0 {
			ttfb = time.Since(start)
		}
		src.Read(want[:k])
		for j := 0; j < k; j++ {
			if buf[j] != want[j] {
				return ttfb, time.Since(start), fmt.Errorf("corrupt byte at %d", got+j)
			}
		}
		got += k
		if err != nil {
			if err == io.EOF && got == n {
				break
			}
			return ttfb, time.Since(start), fmt.Errorf("after %d/%d bytes: %w", got, n, err)
		}
	}
	return ttfb, time.Since(start), nil
}

package soar

import (
	"bufio"
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

// TestClientMemory reports the client's heap under load with the server in another process.
// SOAR_SERVER_BIN=path/to/soar-server to run.
func TestClientMemory(t *testing.T) {
	bin := os.Getenv("SOAR_SERVER_BIN")
	if bin == "" {
		t.Skip("set SOAR_SERVER_BIN")
	}
	out, err := exec.Command(bin, "keygen").Output()
	if err != nil {
		t.Fatal(err)
	}
	var priv, pubs string
	for _, l := range strings.Split(string(out), "\n") {
		if f := strings.Fields(l); len(f) == 2 {
			if f[0] == "private" {
				priv = f[1]
			} else {
				pubs = f[1]
			}
		}
	}
	keyFile := filepath.Join(t.TempDir(), "key")
	os.WriteFile(keyFile, []byte(priv), 0o600)
	cmd := exec.Command(bin, "-zone", testZone, "-key-file", keyFile, "-listen", "127.0.0.1:0", "-allow-ports", "any", "-allow-private")
	stderr, _ := cmd.StderrPipe()
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { cmd.Process.Kill(); cmd.Wait() })
	var srv string
	sc := bufio.NewScanner(stderr)
	for sc.Scan() {
		if i := strings.Index(sc.Text(), "listen="); i >= 0 {
			srv = strings.Fields(sc.Text()[i+len("listen="):])[0]
			break
		}
	}
	go io.Copy(io.Discard, stderr)
	pubRaw, _ := base64.StdEncoding.DecodeString(pubs)
	origin := startOrigin(t)

	heap := func() uint64 {
		runtime.GC()
		var m runtime.MemStats
		runtime.ReadMemStats(&m)
		return m.HeapAlloc
	}
	base := heap()
	c, err := NewClient(Config{Zone: testZone, ServerKey: ed25519.PublicKey(pubRaw), Resolvers: []string{srv}})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	var peakLive, totalAlloc uint64
	stop := make(chan struct{})
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		for {
			select {
			case <-stop:
				return
			case <-time.After(200 * time.Millisecond):
				peakLive = max(peakLive, heap())
			}
		}
	}()
	var m0 runtime.MemStats
	runtime.ReadMemStats(&m0)
	start := time.Now()
	var fw sync.WaitGroup
	for range 8 {
		fw.Add(1)
		go func() {
			defer fw.Done()
			conn, err := c.DialContext(context.Background(), "tcp", origin)
			if err != nil {
				t.Error(err)
				return
			}
			defer conn.Close()
			fmt.Fprintf(conn, "%d\n", 20<<20/8)
			io.Copy(io.Discard, conn)
		}()
	}
	fw.Wait()
	elapsed := time.Since(start)
	var m1 runtime.MemStats
	runtime.ReadMemStats(&m1)
	totalAlloc = m1.TotalAlloc - m0.TotalAlloc
	close(stop)
	wg.Wait()
	time.Sleep(2 * time.Second)
	idle := heap()
	t.Logf("20 MiB over 8 streams in %v; live heap: baseline %d KiB, peak %d KiB, idle %d KiB; allocated %.1f bytes per payload byte; goroutines %d",
		elapsed.Round(time.Millisecond), base>>10, peakLive>>10, idle>>10, float64(totalAlloc)/float64(20<<20), runtime.NumGoroutine())
}

package soar

import (
	"net"
	"slices"
	"sync"
	"testing"
	"time"
)

type memCache struct {
	mu  sync.Mutex
	got []string
}

func (m *memCache) Load() []string { return nil }
func (m *memCache) Store(r []string) {
	m.mu.Lock()
	m.got = slices.Clone(r)
	m.mu.Unlock()
}

// hijacker answers every query with a forged NOERROR A record, as a lying resolver does.
func hijacker(t *testing.T) string {
	pc, _ := net.ListenPacket("udp", "127.0.0.1:0")
	t.Cleanup(func() { pc.Close() })
	go func() {
		buf := make([]byte, 4096)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			q, err := parseQuery(buf[:n], zone{})
			if err != nil {
				continue
			}
			resp := appendAnswer(nil, q, rcodeOK, nil)
			resp[7] = 1
			resp = append(resp[:hdrLen+len(q.question)], 0xC0, hdrLen, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 10, 0, 0, 1)
			pc.WriteTo(resp, from)
		}
	}()
	return pc.LocalAddr().String()
}

func TestDiscoveryVetsCandidates(t *testing.T) {
	srv, pub := startServer(t)
	origin := startOrigin(t)
	good := lossyForwarder(t, srv, 0, 10*time.Millisecond, 0)
	dead, _ := net.ListenPacket("udp", "127.0.0.1:0")
	deadAddr := dead.LocalAddr().String()
	dead.Close()
	cache := &memCache{}
	c, err := NewClient(Config{Zone: testZone, ServerKey: pub, Discovery: Discovery{
		Enabled: true, Candidates: []string{deadAddr, hijacker(t), good}, Cache: cache, noBundled: true,
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	fetch(t, c, origin, 50<<10)
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		cache.mu.Lock()
		got := slices.Clone(cache.got)
		cache.mu.Unlock()
		if len(got) == 1 && got[0] == good {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("cache = %v, want only %s", cache.got, good)
}

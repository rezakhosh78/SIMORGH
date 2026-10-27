package soar

import (
	"crypto/ed25519"
	"strings"
	"sync/atomic"
	"testing"
)

func TestPathStatsSnapshot(t *testing.T) {
	pub, _, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		tcp, transport string
	}{{"off", "udp"}, {"only", "tcp"}} {
		c, err := NewClient(Config{
			Zone:      "t.example.org",
			ServerKey: pub,
			Resolvers: []string{"127.0.0.1:1", "[::1]:1"},
			TCP:       tc.tcp,
		})
		if err != nil {
			t.Fatal(err)
		}
		st := c.PathStats()
		c.Close()
		if len(st) != 2 || st[0].Resolver != "127.0.0.1:1" || st[1].Resolver != "[::1]:1" {
			t.Fatalf("TCP=%s: paths in configuration order: %+v", tc.tcp, st)
		}
		for _, s := range st {
			if s.Transport != tc.transport || s.Verified || s.AnswerLimit != 0 || s.NameLen <= 0 || s.RecordType != "TXT" {
				t.Fatalf("TCP=%s: fresh path: %+v", tc.tcp, s)
			}
		}
		if c.Established() {
			t.Fatal("no session without a server")
		}
	}
}

func TestStreamOnlyGetsTCPPathOnly(t *testing.T) {
	pub, _, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}
	c, err := NewClient(Config{
		Zone:       "t.example.org",
		ServerKey:  pub,
		Resolvers:  []string{"127.0.0.1:1", "127.0.0.1:853"},
		TCP:        "on", // even with UDP+TCP for everyone else
		StreamOnly: func(r string) bool { return r == "127.0.0.1:853" },
	})
	if err != nil {
		t.Fatal(err)
	}
	st := c.PathStats()
	c.Close()
	var got []string
	for _, s := range st {
		got = append(got, s.Resolver+"/"+s.Transport)
	}
	want := "127.0.0.1:1/udp 127.0.0.1:1/tcp 127.0.0.1:853/tcp"
	if g := strings.Join(got, " "); g != want {
		t.Fatalf("paths %q, want %q", g, want)
	}
}

func TestEstablishedDuringHandshake(t *testing.T) {
	srv, pub := startServer(t)
	origin := startOrigin(t)
	c, err := NewClient(Config{Zone: testZone, ServerKey: pub, Resolvers: []string{srv}})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	var stop atomic.Bool
	done := make(chan struct{})
	go func() {
		defer close(done)
		for !stop.Load() {
			c.Established()
		}
	}()
	fetch(t, c, origin, 10)
	stop.Store(true)
	<-done
	if !c.Established() {
		t.Fatal("not established after a fetch")
	}
	c.Close()
	if c.Established() {
		t.Fatal("established after Close")
	}
}

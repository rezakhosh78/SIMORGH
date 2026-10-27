package emu

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"testing"
	"time"
)

// query is a TXT query for name with an EDNS0 OPT advertising a 4096-byte payload.
func query(id uint16, name string) []byte {
	q := binary.BigEndian.AppendUint16(nil, id)
	q = append(q, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 1)
	for _, l := range []string{name, "test"} {
		q = append(q, byte(len(l)))
		q = append(q, l...)
	}
	q = append(q, 0, 0, 16, 0, 1)
	return append(q, 0, 0, 41, 0x10, 0, 0, 0, 0, 0, 0, 0)
}

// bigServer answers every query with its header and question plus size bytes of padding.
func bigServer(t *testing.T, ctx context.Context, size int) string {
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go func() { <-ctx.Done(); pc.Close() }()
	go func() {
		buf := make([]byte, 4096)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			resp := header(buf[:n])
			resp[2] |= 0x80
			pc.WriteTo(append(resp, make([]byte, size)...), from)
		}
	}()
	return pc.LocalAddr().String()
}

func TestTCPAnswersSkipUDPLimits(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	r, err := StartResolver(ctx, "127.0.0.1", bigServer(t, ctx, 2000), Impairment{MaxAnswer: 512})
	if err != nil {
		t.Fatal(err)
	}

	u, err := net.Dial("udp", r.Addr)
	if err != nil {
		t.Fatal(err)
	}
	defer u.Close()
	u.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := u.Write(query(1, "udp")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 4096)
	n, err := u.Read(buf)
	if err != nil {
		t.Fatal(err)
	}
	if buf[2]&0x02 == 0 || n > 512 {
		t.Fatalf("UDP answer of %d bytes, TC=%v: want truncated", n, buf[2]&0x02 != 0)
	}

	c, err := net.Dial("tcp", r.Addr)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(3 * time.Second))
	q := query(2, "tcp")
	if _, err := c.Write(append(binary.BigEndian.AppendUint16(nil, uint16(len(q))), q...)); err != nil {
		t.Fatal(err)
	}
	var hdr [2]byte
	if _, err := io.ReadFull(c, hdr[:]); err != nil {
		t.Fatal(err)
	}
	resp := make([]byte, binary.BigEndian.Uint16(hdr[:]))
	if _, err := io.ReadFull(c, resp); err != nil {
		t.Fatal(err)
	}
	if resp[2]&0x02 != 0 || len(resp) < 2000 {
		t.Fatalf("TCP answer of %d bytes, TC=%v: want the full answer", len(resp), resp[2]&0x02 != 0)
	}
	if got := binary.BigEndian.Uint16(resp); got != 2 {
		t.Fatalf("TCP answer ID %d, want 2", got)
	}
}

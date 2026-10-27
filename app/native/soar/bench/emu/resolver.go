// Package emu emulates the network a DNS tunnel crosses: recursive resolvers with realistic
// impairments between a tunnel client and its authoritative server, and an origin to fetch from.
package emu

import (
	"context"
	"encoding/binary"
	"io"
	"math/rand/v2"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

// Impairment describes how one emulated recursive resolver mistreats traffic.
type Impairment struct {
	// Delay is the one-way delay added in each direction (client→resolver→server, and back).
	Delay time.Duration
	// Jitter adds up to this much random extra delay per packet (so packets reorder).
	Jitter time.Duration
	// LossUp and LossDown drop UDP queries and answers on the path between the client and the
	// resolver, so the resolver never sees a lost query and never retries it. TCP recovers its own
	// losses, so they don't apply to it.
	LossUp, LossDown float64
	// QPS rate-limits queries forwarded for the client; excess queries are dropped silently, as
	// public resolvers do. 0 means no limit.
	QPS int
	// MaxAnswer truncates answers larger than this many bytes: the client gets a TC=1 header with
	// no records, as a resolver honoring a small UDP limit would send. 0 means no limit.
	MaxAnswer int
	// DropOversize drops oversized answers instead of truncating them (some middleboxes do).
	DropOversize bool
	// FilterTXT answers TXT queries with REFUSED, as some resolvers in Iran do.
	FilterTXT bool
	// Dup duplicates this fraction of answers.
	Dup float64
	// Dead drops everything.
	Dead bool
	// MaxQName rejects (SERVFAIL) queries whose name is longer than this many characters, as some
	// Iranian resolvers do (~101). 0 means no limit.
	MaxQName int
	// UpstreamTimeout is how long the resolver waits for the server before answering SERVFAIL;
	// it retransmits the query once at half that. Default 2s, as typical recursive resolvers.
	UpstreamTimeout time.Duration
	// BlockUDP drops every UDP query; TCP still works (Russia's interception of UDP/53).
	BlockUDP bool
	// DropLarge silently drops this fraction of answers over 512 bytes, as networks that police
	// large DNS responses (they look like amplification) do. Seen live through Linode's resolvers.
	DropLarge float64
	// DropLargeAfter delays DropLarge until this many large answers have passed, as policing
	// that reacts to volume does (seen live: probes pass, then large answers vanish).
	DropLargeAfter int
	// Inject answers each UDP query at once with a forged A record before the real answer, as
	// China's GFW does on UDP/53. The real answer still follows.
	Inject bool
}

// Stats counts what a resolver saw.
type Stats struct {
	Queries, Forwarded, Answers, Delivered, Truncated, Dropped atomic.Int64
}

// Resolver is one emulated recursive resolver: it accepts queries from clients, forwards them to
// the authoritative server from its own socket (rewriting transaction IDs, as a real resolver's
// upstream query does), and relays answers back with the configured impairments.
type Resolver struct {
	Addr  string
	Stats Stats

	imp      Impairment
	client   net.PacketConn
	upstream net.PacketConn
	server   net.Addr

	mu        sync.Mutex
	pending   map[uint16]pendingQuery
	nextID    uint16
	nextSeq   uint64
	tokens    float64
	largeSeen int64
	last      time.Time
	rng       *rand.Rand
}

type pendingQuery struct {
	reply func([]byte) // delivers a response to the client
	id    uint16
	at    time.Time
	seq   uint64 // tells this query apart from a later one that reuses its wrapped ID
	tcp   bool   // came over TCP, so UDP size limits don't apply
	edns  int    // the client's advertised EDNS payload size (512 without OPT)
	msg   []byte // the upstream query, for retransmission
}

// StartResolver starts an emulated resolver on listenIP forwarding to server (host:port) until
// ctx ends. Distinct resolvers should get distinct IPs: some clients dedupe resolvers by IP.
func StartResolver(ctx context.Context, listenIP, server string, imp Impairment) (*Resolver, error) {
	client, err := net.ListenPacket("udp", net.JoinHostPort(listenIP, "0"))
	if err != nil {
		return nil, err
	}
	upstream, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		client.Close()
		return nil, err
	}
	saddr, err := net.ResolveUDPAddr("udp", server)
	if err != nil {
		client.Close()
		upstream.Close()
		return nil, err
	}
	r := &Resolver{
		Addr: client.LocalAddr().String(), imp: imp, client: client, upstream: upstream, server: saddr,
		pending: make(map[uint16]pendingQuery), tokens: float64(imp.QPS), last: time.Now(),
		rng: rand.New(rand.NewPCG(rand.Uint64(), rand.Uint64())),
	}
	go func() {
		<-ctx.Done()
		client.Close()
		upstream.Close()
	}()
	tl, err := net.Listen("tcp", r.Addr)
	if err != nil {
		client.Close()
		upstream.Close()
		return nil, err
	}
	go func() {
		<-ctx.Done()
		tl.Close()
	}()
	go r.fromClients()
	go r.fromServer()
	go r.acceptTCP(tl)
	return r, nil
}

// acceptTCP serves RFC 7766 TCP clients: length-prefixed, pipelined queries.
func (r *Resolver) acceptTCP(l net.Listener) {
	for {
		c, err := l.Accept()
		if err != nil {
			return
		}
		go func() {
			defer c.Close()
			var wmu sync.Mutex
			reply := func(msg []byte) {
				time.AfterFunc(r.delay(), func() {
					wmu.Lock()
					defer wmu.Unlock()
					c.Write(append(binary.BigEndian.AppendUint16(nil, uint16(len(msg))), msg...))
				})
			}
			var hdr [2]byte
			for {
				if _, err := io.ReadFull(c, hdr[:]); err != nil {
					return
				}
				msg := make([]byte, binary.BigEndian.Uint16(hdr[:]))
				if _, err := io.ReadFull(c, msg); err != nil {
					return
				}
				r.Stats.Queries.Add(1)
				r.handle(msg, reply, true)
			}
		}()
	}
}

func (r *Resolver) delay() time.Duration {
	d := r.imp.Delay
	if r.imp.Jitter > 0 {
		r.mu.Lock()
		d += time.Duration(r.rng.Int64N(int64(r.imp.Jitter)))
		r.mu.Unlock()
	}
	return d
}

func (r *Resolver) chance(p float64) bool {
	if p <= 0 {
		return false
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.rng.Float64() < p
}

func (r *Resolver) fromClients() {
	buf := make([]byte, 4096)
	for {
		n, from, err := r.client.ReadFrom(buf)
		if err != nil {
			return
		}
		r.Stats.Queries.Add(1)
		if r.imp.BlockUDP {
			r.Stats.Dropped.Add(1)
			continue
		}
		msg := append([]byte(nil), buf[:n]...)
		if r.imp.Inject && n >= 12 {
			r.client.WriteTo(forged(msg), from)
		}
		r.handle(msg, func(b []byte) { r.sendLater(r.client, b, from) }, false)
	}
}

// handle applies the impairments to a client query and forwards it.
func (r *Resolver) handle(msg []byte, reply func([]byte), tcp bool) {
	{
		if len(msg) < 12 || r.imp.Dead || !tcp && r.chance(r.imp.LossUp) || !r.allow() {
			r.Stats.Dropped.Add(1)
			return
		}
		if r.imp.FilterTXT && qtype(msg) == 16 {
			reply(rcode(msg, 5)) // REFUSED
			return
		}
		if r.imp.MaxQName > 0 && qnameLen(msg) > r.imp.MaxQName {
			reply(rcode(msg, 2)) // SERVFAIL
			return
		}
		edns := ednsSize(msg)
		if tcp {
			edns = 65535
		}
		r.mu.Lock()
		r.nextID++
		id := r.nextID
		orig := binary.BigEndian.Uint16(msg)
		binary.BigEndian.PutUint16(msg, id)
		r.nextSeq++
		seq := r.nextSeq
		r.pending[id] = pendingQuery{reply: reply, id: orig, seq: seq, tcp: tcp, at: time.Now(), edns: edns, msg: msg}
		if len(r.pending) > 8192 {
			for k, v := range r.pending {
				if time.Since(v.at) > 10*time.Second {
					delete(r.pending, k)
				}
			}
		}
		r.mu.Unlock()
		r.Stats.Forwarded.Add(1)
		r.sendLater(r.upstream, msg, r.server)
		r.armTimeout(id, seq)
	}
}

// forged is an injected answer: the query's question with a bogus A record.
func forged(q []byte) []byte {
	resp := header(q)
	resp[2] |= 0x80
	resp[7] = 1 // ANCOUNT
	return append(resp, 0xC0, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 203, 0, 113, 7)
}

// armTimeout retransmits an unanswered query once at half the upstream timeout, then gives up
// with SERVFAIL, as recursive resolvers do.
func (r *Resolver) armTimeout(id uint16, seq uint64) {
	to := r.imp.UpstreamTimeout
	if to <= 0 {
		to = 2 * time.Second
	}
	time.AfterFunc(to/2, func() {
		r.mu.Lock()
		pq, ok := r.pending[id]
		r.mu.Unlock()
		if ok && pq.seq == seq {
			r.Stats.Forwarded.Add(1)
			r.sendLater(r.upstream, pq.msg, r.server)
		}
	})
	time.AfterFunc(to, func() {
		r.mu.Lock()
		pq, ok := r.pending[id]
		ok = ok && pq.seq == seq
		if ok {
			delete(r.pending, id)
		}
		r.mu.Unlock()
		if ok {
			resp := rcode(pq.msg, 2)
			binary.BigEndian.PutUint16(resp, pq.id)
			pq.reply(resp)
		}
	})
}

func (r *Resolver) fromServer() {
	buf := make([]byte, 65536)
	for {
		n, _, err := r.upstream.ReadFrom(buf)
		if err != nil {
			return
		}
		if n < 12 {
			continue
		}
		msg := append([]byte(nil), buf[:n]...)
		r.mu.Lock()
		pq, ok := r.pending[binary.BigEndian.Uint16(msg)]
		delete(r.pending, binary.BigEndian.Uint16(msg))
		r.mu.Unlock()
		if !ok {
			continue
		}
		r.Stats.Answers.Add(1)
		binary.BigEndian.PutUint16(msg, pq.id)
		large := !pq.tcp && len(msg) > 512 // policing targets UDP amplification
		r.mu.Lock()
		if large {
			r.largeSeen++
		}
		policed := large && r.largeSeen > int64(r.imp.DropLargeAfter)
		r.mu.Unlock()
		if r.imp.Dead || !pq.tcp && r.chance(r.imp.LossDown) || policed && r.chance(r.imp.DropLarge) {
			r.Stats.Dropped.Add(1)
			continue
		}
		limit := min(pq.edns, 1232)
		if r.imp.MaxAnswer > 0 {
			limit = min(limit, r.imp.MaxAnswer)
		}
		if !pq.tcp && len(msg) > limit {
			if r.imp.DropOversize {
				r.Stats.Dropped.Add(1)
				continue
			}
			r.Stats.Truncated.Add(1)
			msg = header(msg)
			msg[2] |= 0x02 // TC
		}
		r.Stats.Delivered.Add(1)
		pq.reply(msg)
		if r.chance(r.imp.Dup) {
			pq.reply(msg)
		}
	}
}

func (r *Resolver) sendLater(pc net.PacketConn, msg []byte, to net.Addr) {
	d := r.delay()
	if d <= 0 {
		pc.WriteTo(msg, to)
		return
	}
	time.AfterFunc(d, func() { pc.WriteTo(msg, to) })
}

// allow applies the per-client rate limit (a token bucket with one second of burst).
func (r *Resolver) allow() bool {
	if r.imp.QPS <= 0 {
		return true
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	now := time.Now()
	r.tokens = min(float64(r.imp.QPS), r.tokens+now.Sub(r.last).Seconds()*float64(r.imp.QPS))
	r.last = now
	if r.tokens < 1 {
		return false
	}
	r.tokens--
	return true
}

// header returns a message's header and first question only, with no records counted.
func header(msg []byte) []byte {
	end := 12
	for end < len(msg) && msg[end] != 0 {
		if msg[end]&0xC0 != 0 {
			end++
			break
		}
		end += int(msg[end]) + 1
	}
	end = min(end+1+4, len(msg))
	out := append([]byte(nil), msg[:end]...)
	out[4], out[5] = 0, 1
	for i := 6; i < 12; i++ {
		out[i] = 0
	}
	return out
}

// rcode returns a response to msg carrying only its question and the given RCODE.
func rcode(msg []byte, code byte) []byte {
	resp := header(msg)
	resp[2] |= 0x80 // QR
	resp[3] = resp[3]&0xF0 | code
	return resp
}

// qnameLen is the presentation length of the first question's name.
func qnameLen(msg []byte) int {
	n, i := 0, 12
	for i < len(msg) && msg[i] != 0 && msg[i]&0xC0 == 0 {
		n += int(msg[i]) + 1
		i += int(msg[i]) + 1
	}
	return max(n-1, 0)
}

// ednsSize is the EDNS0 UDP payload size a query advertises, or 512 without an OPT record.
func ednsSize(msg []byte) int {
	if len(msg) < 12 || binary.BigEndian.Uint16(msg[10:12]) == 0 {
		return 512
	}
	i := 12
	for i < len(msg) && msg[i] != 0 && msg[i]&0xC0 == 0 {
		i += int(msg[i]) + 1
	}
	i += 1 + 4 // root, QTYPE, QCLASS
	// Additional section: we only look at a leading OPT RR with a root owner.
	if i+5 <= len(msg) && msg[i] == 0 && binary.BigEndian.Uint16(msg[i+1:]) == 41 {
		if sz := int(binary.BigEndian.Uint16(msg[i+3:])); sz >= 512 {
			return sz
		}
	}
	return 512
}

// qtype returns the first question's type, or 0.
func qtype(msg []byte) uint16 {
	i := 12
	for i < len(msg) && msg[i] != 0 {
		i += int(msg[i]) + 1
	}
	if i+3 > len(msg) {
		return 0
	}
	return binary.BigEndian.Uint16(msg[i+1:])
}

// LoopbackIP returns a distinct loopback address for resolver i (127.0.0.2, .3, ...) when the host
// has those addresses, else 127.0.0.1. On macOS they must be added: ifconfig lo0 alias 127.0.0.N.
func LoopbackIP(i int) string {
	ip := net.IPv4(127, 0, 0, byte(2+i)).String()
	pc, err := net.ListenPacket("udp", net.JoinHostPort(ip, "0"))
	if err != nil {
		return "127.0.0.1"
	}
	pc.Close()
	return ip
}

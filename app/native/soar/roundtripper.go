package soar

import (
	"context"
	"net"
	"net/http"
	"sync"
	"time"
)

// NewRoundTripper connects to addr through the tunnel (bounded by ctx) and returns an HTTP
// transport whose first dial to addr uses that connection; later dials ride the tunnel too. It
// returns only once the server confirms it reached addr: kindling treats this as the connect
// phase and falls back to another transport only when connecting fails. TLS runs end to end, so
// the server only relays ciphertext. With Close, MaxLength and RequestTimeout, this satisfies
// kindling's DNS-tunnel transport (dnstt.DNSTT).
func (c *Client) NewRoundTripper(ctx context.Context, addr string) (http.RoundTripper, error) {
	var pre *preDialed
	if addr != "" {
		conn, err := c.DialContext(ctx, "tcp", addr)
		if err != nil {
			return nil, err
		}
		if err := conn.(*Conn).waitOpened(ctx); err != nil {
			conn.Close()
			return nil, err
		}
		pre = &preDialed{conn: conn, addr: addr}
	} else if _, err := c.session(ctx); err != nil {
		return nil, err
	}
	return &http.Transport{
		DialContext: func(ctx context.Context, network, a string) (net.Conn, error) {
			if conn := pre.take(a); conn != nil {
				return conn, nil
			}
			return c.DialContext(ctx, network, a)
		},
		MaxIdleConns:        4,
		MaxIdleConnsPerHost: 2,
		// Idle keep-alives hold a stream open, and an open stream keeps a poll outstanding.
		IdleConnTimeout:     5 * time.Second,
		TLSHandshakeTimeout: 30 * time.Second,
	}, nil
}

// preDialed hands a confirmed connection to the first dial for its address.
type preDialed struct {
	mu   sync.Mutex
	conn net.Conn
	addr string
}

func (p *preDialed) take(addr string) net.Conn {
	if p == nil {
		return nil
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.conn == nil || addr != p.addr {
		return nil
	}
	conn := p.conn
	p.conn = nil
	return conn
}

// MaxLength caps request bodies: uploads ride ~50-byte queries, so big ones belong elsewhere.
func (c *Client) MaxLength() int { return 64 << 10 }

// RequestTimeout bounds a request through the tunnel, including session setup.
func (c *Client) RequestTimeout() time.Duration { return 90 * time.Second }

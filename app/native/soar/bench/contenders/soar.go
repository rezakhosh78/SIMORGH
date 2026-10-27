package contenders

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"net"

	"github.com/getlantern/soar"
	"github.com/getlantern/soar/bench/bench"
)

// Soar is the new protocol: client and server in-process.
type Soar struct {
	pub ed25519.PublicKey
}

func (s *Soar) Name() string  { return "soar" }
func (s *Soar) Notes() string { return "new protocol (all resolvers)" }

func (s *Soar) StartServer(ctx context.Context, env *bench.Env) error {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	s.pub = pub
	pc, err := net.ListenPacket("udp", env.ServerAddr)
	if err != nil {
		return err
	}
	go soar.Serve(ctx, pc, soar.ServerConfig{Zone: env.Zone, PrivateKey: priv, Dial: (&net.Dialer{}).DialContext})
	return nil
}

func (s *Soar) StartClient(ctx context.Context, env *bench.Env) (bench.Dialer, error) {
	c, err := soar.NewClient(soar.Config{Zone: env.Zone, ServerKey: s.pub, Resolvers: env.Resolvers})
	if err != nil {
		return nil, err
	}
	go func() { <-ctx.Done(); c.Close() }()
	return func(ctx context.Context) (net.Conn, error) {
		return c.DialContext(ctx, "tcp", env.Origin)
	}, nil
}

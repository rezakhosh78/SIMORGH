package contenders

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/getlantern/soar/bench/bench"
)

func start(ctx context.Context, env *bench.Env, dir, bin string, args ...string) error {
	cmd := exec.CommandContext(ctx, bin, args...)
	cmd.Dir = dir
	cmd.Stdout, cmd.Stderr = env.Log, env.Log
	fmt.Fprintf(env.Log, "+ %s %s\n", bin, strings.Join(args, " "))
	if err := cmd.Start(); err != nil {
		return err
	}
	go cmd.Wait() // reap it once the run's context kills it
	return nil
}

func freeTCP() string {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "127.0.0.1:0"
	}
	defer ln.Close()
	return ln.Addr().String()
}

func waitTCP(ctx context.Context, addr string, d time.Duration) error {
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		if c, err := net.DialTimeout("tcp", addr, 200*time.Millisecond); err == nil {
			c.Close()
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(100 * time.Millisecond):
		}
	}
	return fmt.Errorf("client never listened on %s", addr)
}

func forwardDialer(addr string) bench.Dialer {
	return func(ctx context.Context) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, "tcp", addr)
	}
}

func port(addr string) string { _, p, _ := net.SplitHostPort(addr); return p }

// Dnstt is David Fifield's dnstt (Turbo Tunnel: KCP + smux + Noise). One resolver only.
type Dnstt struct{ Server, Client string }

func (d *Dnstt) Name() string  { return "dnstt" }
func (d *Dnstt) Notes() string { return "single resolver (best-case pick)" }
func (d *Dnstt) StartServer(ctx context.Context, env *bench.Env) error {
	if out, err := exec.Command(d.Server, "-gen-key", "-privkey-file", filepath.Join(env.WorkDir, "k"), "-pubkey-file", filepath.Join(env.WorkDir, "p")).CombinedOutput(); err != nil {
		return fmt.Errorf("gen-key: %v %s", err, out)
	}
	return start(ctx, env, env.WorkDir, d.Server, "-udp", env.ServerAddr, "-privkey-file", "k", env.Zone, env.Origin)
}
func (d *Dnstt) StartClient(ctx context.Context, env *bench.Env) (bench.Dialer, error) {
	l := freeTCP()
	if err := start(ctx, env, env.WorkDir, d.Client, "-udp", env.BestResolver, "-pubkey-file", "p", env.Zone, l); err != nil {
		return nil, err
	}
	return forwardDialer(l), waitTCP(ctx, l, 10*time.Second)
}

// VayDNS is a dnstt fork with a bigger queue and configurable record types. One resolver only.
type VayDNS struct{ Server, Client string }

func (v *VayDNS) Name() string  { return "vaydns" }
func (v *VayDNS) Notes() string { return "single resolver (best-case pick)" }
func (v *VayDNS) StartServer(ctx context.Context, env *bench.Env) error {
	if out, err := exec.Command(v.Server, "-gen-key", "-privkey-file", filepath.Join(env.WorkDir, "k"), "-pubkey-file", filepath.Join(env.WorkDir, "p")).CombinedOutput(); err != nil {
		return fmt.Errorf("gen-key: %v %s", err, out)
	}
	return start(ctx, env, env.WorkDir, v.Server, "-udp", env.ServerAddr, "-privkey-file", "k", "-domain", env.Zone, "-upstream", env.Origin)
}
func (v *VayDNS) StartClient(ctx context.Context, env *bench.Env) (bench.Dialer, error) {
	l := freeTCP()
	if err := start(ctx, env, env.WorkDir, v.Client, "-udp", env.BestResolver, "-pubkey-file", "p", "-domain", env.Zone, "-listen", l); err != nil {
		return nil, err
	}
	return forwardDialer(l), waitTCP(ctx, l, 10*time.Second)
}

// SlipstreamRust is Mygod's slipstream-rust (multipath QUIC over DNS on picoquic). Archived 2026-08.
type SlipstreamRust struct{ Server, Client string }

func (s *SlipstreamRust) Name() string  { return "slipstream-rust" }
func (s *SlipstreamRust) Notes() string { return "multipath QUIC; archived" }
func (s *SlipstreamRust) StartServer(ctx context.Context, env *bench.Env) error {
	return start(ctx, env, env.WorkDir, s.Server, "--dns-listen-host", "127.0.0.1", "--dns-listen-port", port(env.ServerAddr),
		"--target-address", env.Origin, "--domain", env.Zone, "--cert", "c.pem", "--key", "k.pem", "--reset-seed", "seed")
}
func (s *SlipstreamRust) StartClient(ctx context.Context, env *bench.Env) (bench.Dialer, error) {
	l := freeTCP()
	args := []string{"--tcp-listen-host", "127.0.0.1", "-l", port(l), "-d", env.Zone}
	for _, r := range env.Resolvers {
		args = append(args, "-r", r)
	}
	if err := start(ctx, env, env.WorkDir, s.Client, args...); err != nil {
		return nil, err
	}
	return forwardDialer(l), waitTCP(ctx, l, 10*time.Second)
}

// SlipstreamC is EndPositive's original slipstream.
type SlipstreamC struct{ Server, Client, Cert, Key string }

func (s *SlipstreamC) Name() string  { return "slipstream-c" }
func (s *SlipstreamC) Notes() string { return "multipath QUIC (original)" }
func (s *SlipstreamC) StartServer(ctx context.Context, env *bench.Env) error {
	return start(ctx, env, env.WorkDir, s.Server, "-l", port(env.ServerAddr), "-a", env.Origin, "-d", env.Zone, "-c", s.Cert, "-k", s.Key)
}
func (s *SlipstreamC) StartClient(ctx context.Context, env *bench.Env) (bench.Dialer, error) {
	l := freeTCP()
	args := []string{"-l", port(l), "-d", env.Zone}
	for _, r := range env.Resolvers {
		args = append(args, "-r", r)
	}
	if err := start(ctx, env, env.WorkDir, s.Client, args...); err != nil {
		return nil, err
	}
	return forwardDialer(l), waitTCP(ctx, l, 10*time.Second)
}

// MasterDnsVPN runs MasterDnsVPN-family tunnels (MasterDnsVPN, CottenDNS) from a config pair.
// MasterDnsVPN runs with its shipped defaults (3x packet duplication), as people use it.
type MasterDnsVPN struct {
	Server, Client, ServerConfig, ClientConfig string
	ID, Note                                   string
	Extra                                      map[string]string // applied to both configs
}

func (m *MasterDnsVPN) Name() string {
	if m.ID != "" {
		return m.ID
	}
	return "masterdnsvpn"
}

func (m *MasterDnsVPN) Notes() string {
	if m.Note != "" {
		return m.Note
	}
	return "defaults: 3x duplication"
}

func merge(a, b map[string]string) map[string]string {
	out := map[string]string{}
	for k, v := range a {
		out[k] = v
	}
	for k, v := range b {
		out[k] = v
	}
	return out
}

func setTOML(src string, kv map[string]string) string {
	for k, v := range kv {
		re := regexp.MustCompile(`(?m)^` + k + `\s*=.*$`)
		if re.MatchString(src) {
			src = re.ReplaceAllString(src, k+" = "+v)
		} else {
			src += "\n" + k + " = " + v + "\n"
		}
	}
	return src
}

func (m *MasterDnsVPN) StartServer(ctx context.Context, env *bench.Env) error {
	dir := filepath.Join(env.WorkDir, "srv")
	os.MkdirAll(dir, 0o755)
	tmpl, err := os.ReadFile(m.ServerConfig)
	if err != nil {
		return err
	}
	h, p, _ := net.SplitHostPort(env.ServerAddr)
	oh, op, _ := net.SplitHostPort(env.Origin)
	cfg := setTOML(string(tmpl), merge(m.Extra, map[string]string{"DOMAIN": `["` + env.Zone + `"]`, "UDP_HOST": `"` + h + `"`, "UDP_PORT": p,
		"PROTOCOL_TYPE": `"TCP"`, "FORWARD_IP": `"` + oh + `"`, "FORWARD_PORT": op}))
	if err := os.WriteFile(filepath.Join(dir, "server_config.toml"), []byte(cfg), 0o644); err != nil {
		return err
	}
	if err := start(ctx, env, dir, m.Server, "-config", "server_config.toml"); err != nil {
		return err
	}
	for range 50 {
		if _, err := os.Stat(filepath.Join(dir, "encrypt_key.txt")); err == nil {
			return nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	return fmt.Errorf("server never wrote encrypt_key.txt")
}

func (m *MasterDnsVPN) StartClient(ctx context.Context, env *bench.Env) (bench.Dialer, error) {
	dir := filepath.Join(env.WorkDir, "cli")
	os.MkdirAll(dir, 0o755)
	key, err := os.ReadFile(filepath.Join(env.WorkDir, "srv", "encrypt_key.txt"))
	if err != nil {
		return nil, err
	}
	tmpl, err := os.ReadFile(m.ClientConfig)
	if err != nil {
		return nil, err
	}
	l := freeTCP()
	cfg := setTOML(string(tmpl), merge(m.Extra, map[string]string{"DOMAINS": `["` + env.Zone + `"]`, "ENCRYPTION_KEY": `"` + strings.TrimSpace(string(key)) + `"`,
		"PROTOCOL_TYPE": `"TCP"`, "LISTEN_IP": `"127.0.0.1"`, "LISTEN_PORT": port(l)}))
	if err := os.WriteFile(filepath.Join(dir, "client_config.toml"), []byte(cfg), 0o644); err != nil {
		return nil, err
	}
	if err := os.WriteFile(filepath.Join(dir, "client_resolvers.txt"), []byte(strings.Join(env.Resolvers, "\n")+"\n"), 0o644); err != nil {
		return nil, err
	}
	if err := start(ctx, env, dir, m.Client, "-config", "client_config.toml"); err != nil {
		return nil, err
	}
	return forwardDialer(l), waitTCP(ctx, l, 120*time.Second) // it tests every resolver before listening
}

// SoarRS is the Rust implementation of Soar (getlantern/soar-rs): the same protocol, run from its
// soar-server and soar-client binaries.
type SoarRS struct{ Server, Client string }

func (s *SoarRS) Name() string  { return "soar-rs" }
func (s *SoarRS) Notes() string { return "Rust implementation (all resolvers)" }
func (s *SoarRS) StartServer(ctx context.Context, env *bench.Env) error {
	// The keys come on stdout; stderr is kept apart so a failure says why.
	cmd := exec.CommandContext(ctx, s.Server, "keygen")
	var stderr strings.Builder
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if err != nil {
		return fmt.Errorf("keygen: %v %s", err, stderr.String())
	}
	for _, line := range strings.Split(string(out), "\n") {
		if f := strings.Fields(line); len(f) == 2 {
			if err := os.WriteFile(filepath.Join(env.WorkDir, f[0]), []byte(f[1]), 0o600); err != nil {
				return err
			}
		}
	}
	return start(ctx, env, env.WorkDir, s.Server, "-zone", env.Zone, "-key-file", "private",
		"-listen", env.ServerAddr, "-allow-private", "-allow-ports", "any")
}
func (s *SoarRS) StartClient(ctx context.Context, env *bench.Env) (bench.Dialer, error) {
	pub, err := os.ReadFile(filepath.Join(env.WorkDir, "public"))
	if err != nil {
		return nil, err
	}
	l := freeTCP()
	if err := start(ctx, env, env.WorkDir, s.Client, "-zone", env.Zone, "-pubkey", string(pub),
		"-resolver", strings.Join(env.Resolvers, ","), "-listen", l, "-forward", env.Origin); err != nil {
		return nil, err
	}
	return forwardDialer(l), waitTCP(ctx, l, 10*time.Second)
}

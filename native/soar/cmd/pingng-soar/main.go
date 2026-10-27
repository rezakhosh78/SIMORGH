// pingng-soar adapts the upstream SoarDNS DNS tunnel client to PingNG's local
// SOCKS5 outbound contract. The listener is loopback-only and supports TCP
// CONNECT; the SoarDNS protocol does not carry UDP datagrams.
package main

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/getlantern/soar"
)

type runtimeConfig struct {
	Zone                  string   `json:"zone"`
	ServerKey             string   `json:"serverKey"`
	Resolvers             []string `json:"resolvers"`
	DiscoveryEnabled      bool     `json:"discoveryEnabled"`
	DiscoveryCountry      string   `json:"discoveryCountry"`
	DiscoveryCandidates   []string `json:"discoveryCandidates"`
	DiscoverySampleRanges int      `json:"discoverySampleRanges"`
	DiscoveryWant         int      `json:"discoveryWant"`
	DiscoveryConcurrency  int      `json:"discoveryConcurrency"`
	DiscoveryPaceMs       int      `json:"discoveryPaceMs"`
	DiscoveryCacheEnabled bool     `json:"discoveryCacheEnabled"`
	DiscoveryCachePath    string   `json:"discoveryCachePath"`
	TCP                   string   `json:"tcp"`
	Listen                string   `json:"listen"`
	SafeQName             int      `json:"safeQName"`
	MaxQName              int      `json:"maxQName"`
	HoldMs                int      `json:"holdMs"`
	MaxInflight           int      `json:"maxInflight"`
	MaxPathWindow         int      `json:"maxPathWindow"`
	MaxAnswer             int      `json:"maxAnswer"`
	StreamWindowKB        int      `json:"streamWindowKB"`
	SendBufferKB          int      `json:"sendBufferKB"`
	MaxStreams            int      `json:"maxStreams"`
	SessionIdleSeconds    int      `json:"sessionIdleSeconds"`
}

func main() {
	configPath := flag.String("config", "", "path to PingNG SoarDNS client JSON")
	flag.Parse()
	if *configPath == "" {
		fatal(errors.New("--config is required"))
	}

	raw, err := os.ReadFile(*configPath)
	if err != nil {
		fatal(fmt.Errorf("read configuration: %w", err))
	}
	var cfg runtimeConfig
	if err := json.Unmarshal(raw, &cfg); err != nil {
		fatal(fmt.Errorf("decode configuration: %w", err))
	}
	cfg.Zone = strings.TrimSuffix(strings.TrimSpace(cfg.Zone), ".")
	cfg.DiscoveryCountry = strings.ToUpper(strings.TrimSpace(cfg.DiscoveryCountry))
	if cfg.TCP == "" {
		cfg.TCP = "auto"
	}
	if cfg.Listen == "" {
		cfg.Listen = "127.0.0.1:18002"
	}
	cfg.Resolvers = normalizeResolvers(cfg.Resolvers)
	cfg.DiscoveryCandidates = normalizeResolvers(cfg.DiscoveryCandidates)
	if err := requireLoopback(cfg.Listen); err != nil {
		fatal(err)
	}
	key, err := decodePublicKey(cfg.ServerKey)
	if err != nil {
		fatal(err)
	}

	discovery := soar.Discovery{
		Enabled: cfg.DiscoveryEnabled, Country: cfg.DiscoveryCountry,
		Candidates: cfg.DiscoveryCandidates, SampleRanges: cfg.DiscoverySampleRanges,
		Want: cfg.DiscoveryWant, Concurrency: cfg.DiscoveryConcurrency,
	}
	if cfg.DiscoveryPaceMs > 0 {
		discovery.Pace = time.Duration(cfg.DiscoveryPaceMs) * time.Millisecond
	}
	if cfg.DiscoveryCacheEnabled && cfg.DiscoveryCachePath != "" {
		discovery.Cache = resolverFileCache{path: cfg.DiscoveryCachePath}
	}
	client, err := soar.NewClient(soar.Config{
		Zone: cfg.Zone, ServerKey: key, Resolvers: cfg.Resolvers, Discovery: discovery,
		SafeQName: cfg.SafeQName, MaxQName: cfg.MaxQName,
		Hold: time.Duration(cfg.HoldMs) * time.Millisecond,
		MaxInflight: cfg.MaxInflight, MaxPathWindow: cfg.MaxPathWindow,
		MaxAnswer: cfg.MaxAnswer, StreamWindow: uint64(cfg.StreamWindowKB) * 1024,
		SendBuffer: cfg.SendBufferKB * 1024, MaxStreams: cfg.MaxStreams,
		SessionIdle: time.Duration(cfg.SessionIdleSeconds) * time.Second,
		TCP: cfg.TCP,
		Logger: slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo})),
	})
	if err != nil {
		fatal(fmt.Errorf("initialize SoarDNS client: %w", err))
	}
	defer client.Close()

	listener, err := net.Listen("tcp", cfg.Listen)
	if err != nil {
		fatal(fmt.Errorf("open local SOCKS5 listener: %w", err))
	}
	defer listener.Close()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		_ = listener.Close()
	}()
	fmt.Printf("SOAR_SOCKS_LISTENING %s\n", listener.Addr())
	go reportResolverProgress(ctx, client, cfg.DiscoveryEnabled)
	go func() {
		if err := client.WarmUp(ctx); err != nil && ctx.Err() == nil {
			log.Printf("initial DNS resolver probe did not establish a session: %v", err)
		}
	}()

	for {
		conn, err := listener.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("accept SOCKS connection: %v", err)
			continue
		}
		go serveSocks(ctx, conn, client)
	}
}

type resolverFileCache struct{ path string }

func (c resolverFileCache) Load() []string {
	raw, err := os.ReadFile(c.path)
	if err != nil {
		return nil
	}
	var resolvers []string
	for _, line := range strings.Split(string(raw), "\n") {
		if value := strings.TrimSpace(line); value != "" && !strings.HasPrefix(value, "#") {
			resolvers = append(resolvers, normalizeResolver(value))
		}
	}
	return resolvers
}

func (c resolverFileCache) Store(resolvers []string) {
	if err := os.MkdirAll(filepath.Dir(c.path), 0o700); err != nil {
		return
	}
	tmp := c.path + ".tmp"
	if err := os.WriteFile(tmp, []byte(strings.Join(resolvers, "\n")+"\n"), 0o600); err != nil {
		return
	}
	_ = os.Rename(tmp, c.path)
}

func reportResolverProgress(ctx context.Context, client *soar.Client, discoveryEnabled bool) {
	ticker := time.NewTicker(250 * time.Millisecond)
	defer ticker.Stop()
	var previous string
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			var completed, total, valid int
			if discoveryEnabled {
				completed, total, valid = client.DiscoveryProgress()
			} else {
				completed, total, valid = client.ManualResolverProgress()
			}
			mode := "manual"
			if discoveryEnabled {
				mode = "discovery"
			}
			line := fmt.Sprintf("SOAR_DNS_SCAN %s %d %d %d", mode, completed, total, valid)
			if line != previous {
				fmt.Println(line)
				previous = line
			}
		}
	}
}

func normalizeResolvers(values []string) []string {
	result := make([]string, 0, len(values))
	for _, value := range values {
		value = normalizeResolver(value)
		if value != "" {
			result = append(result, value)
		}
	}
	return result
}

// Soar expects every resolver as host:port. Bare IPv4/IPv6 addresses use DNS port 53.
func normalizeResolver(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return ""
	}
	if strings.HasPrefix(value, "[") {
		if strings.HasSuffix(value, "]") {
			return net.JoinHostPort(strings.TrimSuffix(strings.TrimPrefix(value, "["), "]"), "53")
		}
		if host, port, err := net.SplitHostPort(value); err == nil {
			if port == "" {
				return net.JoinHostPort(host, "53")
			}
			return value
		}
		return value
	}
	if ip := net.ParseIP(value); ip != nil {
		return net.JoinHostPort(value, "53")
	}
	if host, port, err := net.SplitHostPort(value); err == nil {
		if port == "" {
			return net.JoinHostPort(host, "53")
		}
		return value
	}
	if !strings.Contains(value, ":") {
		return net.JoinHostPort(value, "53")
	}
	return value
}

func decodePublicKey(value string) (ed25519.PublicKey, error) {
	value = strings.TrimSpace(value)
	var decoded []byte
	var err error
	switch {
	case len(value) == ed25519.PublicKeySize*2:
		decoded, err = hex.DecodeString(value)
	default:
		for _, decoder := range []*base64.Encoding{
			base64.StdEncoding, base64.RawStdEncoding,
			base64.URLEncoding, base64.RawURLEncoding,
		} {
			decoded, err = decoder.DecodeString(value)
			if err == nil {
				break
			}
		}
	}
	if err != nil || len(decoded) != ed25519.PublicKeySize {
		return nil, fmt.Errorf("server key must be a 32-byte Ed25519 public key in base64 or 64-character hex")
	}
	return ed25519.PublicKey(decoded), nil
}

func requireLoopback(address string) error {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return fmt.Errorf("invalid local SOCKS5 address: %w", err)
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		return errors.New("local SOCKS5 listener must bind to a loopback IP")
	}
	return nil
}

func serveSocks(ctx context.Context, conn net.Conn, client *soar.Client) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(30 * time.Second))
	reader := make([]byte, 2)
	if _, err := io.ReadFull(conn, reader); err != nil || reader[0] != 5 || reader[1] == 0 {
		return
	}
	methods := make([]byte, int(reader[1]))
	if _, err := io.ReadFull(conn, methods); err != nil {
		return
	}
	noAuth := false
	for _, method := range methods {
		if method == 0 {
			noAuth = true
			break
		}
	}
	if !noAuth {
		_, _ = conn.Write([]byte{5, 0xff})
		return
	}
	if _, err := conn.Write([]byte{5, 0}); err != nil {
		return
	}

	request := make([]byte, 4)
	if _, err := io.ReadFull(conn, request); err != nil || request[0] != 5 || request[2] != 0 {
		return
	}
	if request[1] != 1 {
		writeSocksReply(conn, 7)
		return
	}
	host, err := readSocksHost(conn, request[3])
	if err != nil {
		writeSocksReply(conn, 8)
		return
	}
	portBytes := make([]byte, 2)
	if _, err := io.ReadFull(conn, portBytes); err != nil {
		return
	}
	address := net.JoinHostPort(host, strconv.Itoa(int(portBytes[0])<<8|int(portBytes[1])))
	_ = conn.SetDeadline(time.Now().Add(90 * time.Second))
	dialCtx, cancel := context.WithTimeout(ctx, 90*time.Second)
	remote, err := client.DialContext(dialCtx, "tcp", address)
	cancel()
	if err != nil {
		writeSocksReply(conn, 5)
	log.Printf("SoarDNS stream open failed: %v", err)
		return
	}
	defer remote.Close()
	if err := writeSocksReply(conn, 0); err != nil {
		return
	}
	_ = conn.SetDeadline(time.Time{})

	finished := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(remote, conn)
		finished <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(conn, remote)
		finished <- struct{}{}
	}()
	<-finished
	_ = conn.Close()
	_ = remote.Close()
	<-finished
}

func readSocksHost(conn net.Conn, addressType byte) (string, error) {
	switch addressType {
	case 1:
		value := make([]byte, 4)
		if _, err := io.ReadFull(conn, value); err != nil {
			return "", err
		}
		return net.IP(value).String(), nil
	case 3:
		length := []byte{0}
		if _, err := io.ReadFull(conn, length); err != nil || length[0] == 0 {
			return "", errors.New("invalid SOCKS domain")
		}
		value := make([]byte, int(length[0]))
		if _, err := io.ReadFull(conn, value); err != nil {
			return "", err
		}
		return string(value), nil
	case 4:
		value := make([]byte, 16)
		if _, err := io.ReadFull(conn, value); err != nil {
			return "", err
		}
		return net.IP(value).String(), nil
	default:
		return "", errors.New("unsupported SOCKS address type")
	}
}

func writeSocksReply(conn net.Conn, reply byte) error {
	_, err := conn.Write([]byte{5, reply, 0, 1, 0, 0, 0, 0, 0, 0})
	return err
}

func fatal(err error) {
	log.Printf("PingNG SoarDNS: %v", err)
	os.Exit(1)
}


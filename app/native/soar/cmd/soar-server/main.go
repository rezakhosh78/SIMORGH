// Command soar-server is the authoritative name server for a soar tunnel zone.
//
//	soar-server keygen                      # prints a private and public key
//	soar-server pubkey key                  # prints the public key for a private key file
//	soar-server -zone t.example.com -key-file key -listen :53
package main

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"slices"
	"strings"
	"syscall"

	"github.com/getlantern/soar"
)

func main() {
	if len(os.Args) > 2 && os.Args[1] == "pubkey" {
		raw, err := os.ReadFile(os.Args[2])
		if err != nil {
			fatal(err)
		}
		seed, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(raw)))
		if err != nil || len(seed) != ed25519.SeedSize {
			fatal(fmt.Errorf("key file must hold a base64 %d-byte seed", ed25519.SeedSize))
		}
		fmt.Println(base64.StdEncoding.EncodeToString(ed25519.NewKeyFromSeed(seed).Public().(ed25519.PublicKey)))
		return
	}
	if len(os.Args) > 1 && os.Args[1] == "keygen" {
		pub, priv, err := ed25519.GenerateKey(rand.Reader)
		if err != nil {
			fatal(err)
		}
		fmt.Println("private", base64.StdEncoding.EncodeToString(priv.Seed()))
		fmt.Println("public ", base64.StdEncoding.EncodeToString(pub))
		return
	}
	zone := flag.String("zone", "", "tunnel zone delegated to this server")
	keyFile := flag.String("key-file", "", "file holding the base64 private key (from keygen)")
	listen := flag.String("listen", ":53", "UDP address to serve DNS on")
	ports := flag.String("allow-ports", "80,443", `destination ports streams may reach, or "any"`)
	private := flag.Bool("allow-private", false, "allow non-public destinations (lab use only)")
	verbose := flag.Bool("v", false, "debug logging")
	flag.Parse()
	if *zone == "" || *keyFile == "" {
		flag.Usage()
		os.Exit(2)
	}
	raw, err := os.ReadFile(*keyFile)
	if err != nil {
		fatal(err)
	}
	seed, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(raw)))
	if err != nil || len(seed) != ed25519.SeedSize {
		fatal(fmt.Errorf("key file must hold a base64 %d-byte seed", ed25519.SeedSize))
	}
	level := slog.LevelInfo
	if *verbose {
		level = slog.LevelDebug
	}
	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: level}))

	dialer := &net.Dialer{Control: soar.PublicOnly}
	if *private {
		dialer.Control = nil
	}
	allowed := strings.Split(*ports, ",")
	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		_, port, err := net.SplitHostPort(addr)
		if err != nil {
			return nil, err
		}
		if *ports != "any" && !slices.Contains(allowed, port) {
			return nil, fmt.Errorf("port %s not allowed", port)
		}
		return dialer.DialContext(ctx, network, addr)
	}

	pc, err := net.ListenPacket("udp", *listen)
	if err != nil {
		fatal(err)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	priv := ed25519.NewKeyFromSeed(seed)
	// Log hygiene: never the zone or the key. Either one in a leaked log hands a censor the
	// string it needs to block this server; `soar-server pubkey` prints the key on demand.
	log.Info("serving", "listen", pc.LocalAddr())
	if err := soar.Serve(ctx, pc, soar.ServerConfig{Zone: *zone, PrivateKey: priv, Dial: dial, Logger: log}); err != nil {
		fatal(err)
	}
}

func fatal(err error) {
	fmt.Fprintln(os.Stderr, "soar-server:", err)
	os.Exit(1)
}

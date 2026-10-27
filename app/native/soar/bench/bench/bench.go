// Package bench runs DNS-tunnel contenders through emulated resolver networks and measures them.
package bench

import (
	"context"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"slices"
	"sort"
	"strings"
	"time"

	"github.com/getlantern/soar/bench/emu"
)

// Env is what a contender needs to run: the tunnel zone, where its server must listen, the
// emulated resolvers its client must use, and the origin its tunnel must reach.
type Env struct {
	Zone       string
	ServerAddr string // UDP host:port the server must listen on
	Resolvers  []string
	// BestResolver is the healthiest resolver in the scenario, for single-resolver contenders: the
	// most favorable choice they could make by hand.
	BestResolver string
	Origin       string // TCP host:port the tunnel must deliver connections to
	WorkDir      string
	Log          io.Writer
}

// Dialer opens a connection to the origin through the contender's tunnel.
type Dialer func(ctx context.Context) (net.Conn, error)

// Contender is one DNS tunnel implementation under test.
type Contender interface {
	Name() string
	// Notes describes deviations, e.g. "uses only the first resolver".
	Notes() string
	StartServer(ctx context.Context, env *Env) error
	StartClient(ctx context.Context, env *Env) (Dialer, error)
}

// Scenario is a set of emulated resolvers.
type Scenario struct {
	Name      string
	Resolvers []emu.Impairment
}

// Result is one contender's measurements in one scenario.
type Result struct {
	Contender, Scenario, Notes string
	ColdStart                  time.Duration // client start → first small fetch complete
	ColdErr                    string
	Small, Medium              []time.Duration // completed fetch times
	SmallFail, MediumFail      int
	Queries                    int64 // queries the resolvers received from the client
	Bytes                      int64 // payload bytes fetched successfully
}

const (
	SmallSize  = 2 << 10
	MediumSize = 100 << 10
)

// Run measures one contender in one scenario.
func Run(ctx context.Context, c Contender, sc Scenario, trials int, timeout time.Duration, logw io.Writer) Result {
	res := Result{Contender: c.Name(), Scenario: sc.Name, Notes: c.Notes()}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	origin, err := emu.StartOrigin(ctx)
	if err != nil {
		res.ColdErr = err.Error()
		return res
	}
	server, err := freeUDP()
	if err != nil {
		res.ColdErr = err.Error()
		return res
	}
	dir, err := os.MkdirTemp("", "dnsbench-"+c.Name()+"-")
	if err != nil {
		res.ColdErr = err.Error()
		return res
	}
	defer os.RemoveAll(dir)
	env := &Env{Zone: "t.bench.test", ServerAddr: server, Origin: origin, WorkDir: dir, Log: logw}
	if err := c.StartServer(ctx, env); err != nil {
		res.ColdErr = "server: " + err.Error()
		return res
	}
	time.Sleep(300 * time.Millisecond)
	var resolvers []*emu.Resolver
	for i, imp := range sc.Resolvers {
		ip := emu.LoopbackIP(i)
		if ip == "127.0.0.1" && i == 0 {
			fmt.Fprintln(logw, "WARNING: resolvers share 127.0.0.1; clients that dedupe by IP see one resolver")
		}
		r, err := emu.StartResolver(ctx, ip, server, imp)
		if err != nil {
			res.ColdErr = err.Error()
			return res
		}
		resolvers = append(resolvers, r)
		env.Resolvers = append(env.Resolvers, r.Addr)
	}
	env.BestResolver = env.Resolvers[BestResolver(sc.Resolvers)]

	start := time.Now()
	dial, err := c.StartClient(ctx, env)
	if err != nil {
		res.ColdErr = "client: " + err.Error()
		return res
	}
	if err := fetch(ctx, dial, SmallSize, timeout); err != nil {
		res.ColdErr = err.Error()
	} else {
		res.ColdStart = time.Since(start)
		res.Bytes += SmallSize
	}
	for range trials {
		t0 := time.Now()
		if err := fetch(ctx, dial, SmallSize, timeout); err != nil {
			res.SmallFail++
		} else {
			res.Small = append(res.Small, time.Since(t0))
			res.Bytes += SmallSize
		}
	}
	for range trials {
		t0 := time.Now()
		if err := fetch(ctx, dial, MediumSize, timeout); err != nil {
			res.MediumFail++
		} else {
			res.Medium = append(res.Medium, time.Since(t0))
			res.Bytes += MediumSize
		}
	}
	for _, r := range resolvers {
		res.Queries += r.Stats.Queries.Load()
	}
	return res
}

// BestResolver is the index of the first resolver a single-resolver UDP client could use without
// impairments that break tunnels outright, or 0 if there is none.
func BestResolver(imps []emu.Impairment) int {
	for i, imp := range imps {
		if !imp.Dead && !imp.BlockUDP && !imp.FilterTXT && imp.MaxQName == 0 && imp.MaxAnswer == 0 {
			return i
		}
	}
	return 0
}

func fetch(ctx context.Context, dial Dialer, n int, timeout time.Duration) error {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	conn, err := dial(ctx)
	if err != nil {
		return err
	}
	deadline, _ := ctx.Deadline()
	_, _, err = emu.Fetch(conn, n, deadline)
	return err
}

func freeUDP() (string, error) {
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	defer pc.Close()
	return pc.LocalAddr().String(), nil
}

// Table renders results as a markdown table.
func Table(rs []Result) string {
	var b strings.Builder
	b.WriteString("| scenario | contender | cold start | small p50 / p95 | 100KB p50 / p95 | 100KB KB/s | fails (small/100KB) | queries/KB | notes |\n")
	b.WriteString("|---|---|---|---|---|---|---|---|---|\n")
	for _, r := range rs {
		cold := "FAIL"
		if r.ColdErr == "" {
			cold = fmtDur(r.ColdStart)
		}
		kbps := "—"
		if p := pct(r.Medium, 50); p > 0 {
			kbps = fmt.Sprintf("%.1f", float64(MediumSize)/1024/p.Seconds())
		}
		qpk := "—"
		if r.Bytes > 0 {
			qpk = fmt.Sprintf("%.1f", float64(r.Queries)/(float64(r.Bytes)/1024))
		}
		fmt.Fprintf(&b, "| %s | %s | %s | %s / %s | %s / %s | %s | %d/%d | %s | %s |\n",
			r.Scenario, r.Contender, cold, fmtDur(pct(r.Small, 50)), fmtDur(pct(r.Small, 95)),
			fmtDur(pct(r.Medium, 50)), fmtDur(pct(r.Medium, 95)), kbps, r.SmallFail, r.MediumFail, qpk, r.Notes)
	}
	return b.String()
}

func pct(ds []time.Duration, p int) time.Duration {
	if len(ds) == 0 {
		return 0
	}
	s := slices.Clone(ds)
	sort.Slice(s, func(i, j int) bool { return s[i] < s[j] })
	return s[min(len(s)-1, (len(s)*p+99)/100-1)]
}

func fmtDur(d time.Duration) string {
	if d == 0 {
		return "—"
	}
	if d < time.Second {
		return fmt.Sprintf("%dms", d.Milliseconds())
	}
	return fmt.Sprintf("%.1fs", d.Seconds())
}

// LogFile returns a writer for a contender's subprocess output under dir; the caller closes it.
func LogFile(dir, name string) io.WriteCloser {
	f, err := os.Create(filepath.Join(dir, name+".log"))
	if err != nil {
		return nopCloser{io.Discard}
	}
	return f
}

type nopCloser struct{ io.Writer }

func (nopCloser) Close() error { return nil }

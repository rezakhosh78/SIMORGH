// Command dnsbench runs DNS-tunnel contenders through emulated resolver networks.
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"time"

	"github.com/getlantern/soar/bench/bench"
	"github.com/getlantern/soar/bench/contenders"
)

func main() {
	only := flag.String("contenders", "", "comma-separated contenders (default all available)")
	scen := flag.String("scenarios", "", "comma-separated scenarios (default all)")
	trials := flag.Int("trials", 5, "fetches per size")
	timeout := flag.Duration("timeout", 60*time.Second, "per-fetch timeout")
	others := flag.String("others", os.Getenv("DNSBENCH_OTHERS"), "directory built by build-contenders.sh (default: soar only)")
	flag.Parse()

	all := []bench.Contender{&contenders.Soar{}}
	if *others != "" {
		// Contenders run with a scratch working directory, so their paths must be absolute.
		R, err := filepath.Abs(*others)
		if err != nil {
			fmt.Fprintln(os.Stderr, "others dir:", err)
			os.Exit(1)
		}
		all = append(all,
			&contenders.Dnstt{Server: R + "/bin/dnstt-server", Client: R + "/bin/dnstt-client"},
			&contenders.VayDNS{Server: R + "/bin/vaydns-server", Client: R + "/bin/vaydns-client"},
			&contenders.SoarRS{Server: R + "/soar-rs/target/release/soar-server", Client: R + "/soar-rs/target/release/soar-client"},
			&contenders.SlipstreamRust{Server: R + "/slipstream-rust/target/release/slipstream-server", Client: R + "/slipstream-rust/target/release/slipstream-client"},
			&contenders.SlipstreamC{Server: R + "/slipstream/build/slipstream-server", Client: R + "/slipstream/build/slipstream-client",
				Cert: R + "/slipstream/certs/cert.pem", Key: R + "/slipstream/certs/key.pem"},
			&contenders.MasterDnsVPN{Server: R + "/bin/masterdnsvpn-server", Client: R + "/bin/masterdnsvpn-client",
				ServerConfig: R + "/MasterDnsVPN/server_config.toml.simple", ClientConfig: R + "/MasterDnsVPN/client_config.toml.simple"},
			&contenders.MasterDnsVPN{ID: "cottendns-speed", Note: "speed preset, MIN_DOWNLOAD_MTU=200", Server: R + "/bin/cottendns-server", Client: R + "/bin/cottendns-client",
				ServerConfig: R + "/CottenDNS/server_config.speed.toml", ClientConfig: R + "/CottenDNS/client_config.speed.toml",
				Extra: map[string]string{"DATA_ENCRYPTION_METHOD": "3", "MIN_DOWNLOAD_MTU": "200"}},
			&contenders.MasterDnsVPN{ID: "cottendns-survival", Note: "survival preset, MIN_DOWNLOAD_MTU=200", Server: R + "/bin/cottendns-server", Client: R + "/bin/cottendns-client",
				ServerConfig: R + "/CottenDNS/server_config.survival.toml", ClientConfig: R + "/CottenDNS/client_config.survival.toml",
				Extra: map[string]string{"DATA_ENCRYPTION_METHOD": "3", "MIN_DOWNLOAD_MTU": "200"}},
		)
	}
	want := func(list, name string) bool { return list == "" || slices.Contains(strings.Split(list, ","), name) }

	logDir, err := os.MkdirTemp("", "dnsbench-logs-")
	if err != nil {
		fmt.Fprintln(os.Stderr, "create log dir:", err)
		os.Exit(1)
	}
	fmt.Fprintf(os.Stderr, "subprocess logs: %s\n", logDir)
	var results []bench.Result
	for _, sc := range bench.Scenarios {
		if !want(*scen, sc.Name) {
			continue
		}
		for _, c := range all {
			if !want(*only, c.Name()) {
				continue
			}
			fmt.Fprintf(os.Stderr, "running %s / %s ...\n", sc.Name, c.Name())
			log := bench.LogFile(logDir, sc.Name+"-"+c.Name())
			r := bench.Run(context.Background(), c, sc, *trials, *timeout, log)
			log.Close()
			results = append(results, r)
			fmt.Fprint(os.Stderr, bench.Table([]bench.Result{r}))
		}
	}
	fmt.Println(bench.Table(results))
}

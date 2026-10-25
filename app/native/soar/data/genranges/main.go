// Command genranges writes a country's IPv4 ranges for soar's optional range sampling, from a
// regional internet registry's delegation statistics.
//
//	go run ./data/genranges -cc CN -rir apnic > data/ranges/cn.txt
//	go run ./data/genranges -cc RU -rir ripencc > data/ranges/ru.txt
//
// It keeps the largest allocations (where ISPs' subscriber space lives) up to -max prefixes, so
// the embedded file stays small.
package main

import (
	"bufio"
	"encoding/binary"
	"flag"
	"fmt"
	"log"
	"math/bits"
	"net/http"
	"net/netip"
	"os"
	"sort"
	"strconv"
	"strings"
	"time"
)

var statsURL = map[string]string{
	"afrinic": "https://ftp.afrinic.net/pub/stats/afrinic/delegated-afrinic-latest",
	"apnic":   "https://ftp.apnic.net/stats/apnic/delegated-apnic-latest",
	"arin":    "https://ftp.arin.net/pub/stats/arin/delegated-arin-extended-latest",
	"lacnic":  "https://ftp.lacnic.net/pub/stats/lacnic/delegated-lacnic-latest",
	"ripencc": "https://ftp.ripe.net/pub/stats/ripencc/delegated-ripencc-latest",
}

func main() {
	cc := flag.String("cc", "", "ISO 3166-1 alpha-2 country code")
	rir := flag.String("rir", "", "registry: afrinic, apnic, arin, lacnic, ripencc")
	maxN := flag.Int("max", 2000, "keep at most this many prefixes, largest first")
	flag.Parse()
	url, ok := statsURL[*rir]
	if *cc == "" || !ok {
		flag.Usage()
		os.Exit(2)
	}
	resp, err := (&http.Client{Timeout: 2 * time.Minute}).Get(url)
	if err != nil {
		log.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		log.Fatalf("%s: %s", url, resp.Status) // never overwrite a list with an error page
	}

	var prefixes []netip.Prefix
	sc := bufio.NewScanner(resp.Body)
	for sc.Scan() {
		// registry|cc|type|start|value|date|status
		f := strings.Split(sc.Text(), "|")
		if len(f) < 7 || !strings.EqualFold(f[1], *cc) || f[2] != "ipv4" ||
			(f[6] != "allocated" && f[6] != "assigned") {
			continue
		}
		start, err := netip.ParseAddr(f[3])
		count, err2 := strconv.ParseUint(f[4], 10, 32)
		if err != nil || err2 != nil {
			continue
		}
		prefixes = append(prefixes, split(start, uint32(count))...)
	}
	if err := sc.Err(); err != nil {
		log.Fatal(err)
	}
	sort.SliceStable(prefixes, func(i, j int) bool { return prefixes[i].Bits() < prefixes[j].Bits() })
	prefixes = prefixes[:min(len(prefixes), *maxN)]
	fmt.Printf("# %s IPv4 ranges from %s delegation stats (%s), the %d largest. Regenerate with:\n",
		strings.ToUpper(*cc), *rir, time.Now().UTC().Format("2006-01-02"), len(prefixes))
	fmt.Printf("#   go run ./data/genranges -cc %s -rir %s\n", strings.ToUpper(*cc), *rir)
	for _, p := range prefixes {
		fmt.Println(p)
	}
}

// split covers [start, start+count) with the fewest CIDR prefixes (delegations need not be
// power-of-two sized or aligned).
func split(start netip.Addr, count uint32) []netip.Prefix {
	var out []netip.Prefix
	a := binary.BigEndian.Uint32(start.AsSlice())
	for count > 0 {
		size := uint32(1) << min(bits.TrailingZeros32(a), 31)
		if a == 0 {
			size = 1 << 31
		}
		for size > count {
			size >>= 1
		}
		out = append(out, netip.PrefixFrom(netip.AddrFrom4([4]byte(binary.BigEndian.AppendUint32(nil, a))), 32-bits.TrailingZeros32(size)))
		a += size
		count -= size
	}
	return out
}

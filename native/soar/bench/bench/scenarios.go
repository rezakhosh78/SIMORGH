package bench

import (
	"time"

	"github.com/getlantern/soar/bench/emu"
)

func same(n int, imp emu.Impairment) []emu.Impairment {
	out := make([]emu.Impairment, n)
	for i := range out {
		out[i] = imp
	}
	return out
}

// Scenarios are the emulated networks every contender runs through.
var Scenarios = []Scenario{
	{Name: "clean", Resolvers: same(3, emu.Impairment{Delay: 30 * time.Millisecond})},
	{Name: "realistic", Resolvers: same(3, emu.Impairment{Delay: 60 * time.Millisecond, Jitter: 40 * time.Millisecond,
		LossUp: 0.02, LossDown: 0.02, QPS: 100})},
	{Name: "lossy", Resolvers: same(3, emu.Impairment{Delay: 60 * time.Millisecond, Jitter: 40 * time.Millisecond,
		LossUp: 0.10, LossDown: 0.10})},
	{Name: "ratelimited", Resolvers: same(3, emu.Impairment{Delay: 60 * time.Millisecond, Jitter: 20 * time.Millisecond, QPS: 30})},
	{Name: "truncating", Resolvers: []emu.Impairment{
		{Delay: 60 * time.Millisecond, Jitter: 20 * time.Millisecond, MaxAnswer: 512},
		{Delay: 60 * time.Millisecond, Jitter: 20 * time.Millisecond},
		{Delay: 60 * time.Millisecond, Jitter: 20 * time.Millisecond},
	}},
	{Name: "deadmix", Resolvers: []emu.Impairment{
		{Dead: true},
		{Delay: 60 * time.Millisecond, Jitter: 30 * time.Millisecond, LossUp: 0.03, LossDown: 0.03},
		{Dead: true},
		{Delay: 60 * time.Millisecond, Jitter: 30 * time.Millisecond, LossUp: 0.03, LossDown: 0.03},
		{Delay: 60 * time.Millisecond, Jitter: 30 * time.Millisecond, LossUp: 0.03, LossDown: 0.03},
	}},
	// A hostile mix modeled on reports from Iran: a TXT-filtering resolver, a dead one, and lossy,
	// rate-limited, high-jitter ones.
	{Name: "iranlike", Resolvers: []emu.Impairment{
		{Delay: 80 * time.Millisecond, Jitter: 60 * time.Millisecond, FilterTXT: true},
		{Dead: true},
		{Delay: 80 * time.Millisecond, Jitter: 60 * time.Millisecond, LossUp: 0.05, LossDown: 0.05, QPS: 40},
		{Delay: 120 * time.Millisecond, Jitter: 80 * time.Millisecond, LossUp: 0.05, LossDown: 0.08, QPS: 40, MaxAnswer: 900},
		{Delay: 80 * time.Millisecond, Jitter: 60 * time.Millisecond, LossUp: 0.03, LossDown: 0.03, QPS: 40, MaxQName: 101},
	}},
	// Russia: UDP to public resolvers is intercepted (here: dropped), TCP/53 untouched; the
	// national resolver UDP traffic lands on is slow and rate-limited.
	{Name: "russialike", Resolvers: []emu.Impairment{
		{Delay: 50 * time.Millisecond, Jitter: 30 * time.Millisecond, BlockUDP: true},
		{Delay: 60 * time.Millisecond, Jitter: 30 * time.Millisecond, BlockUDP: true},
		{Delay: 90 * time.Millisecond, Jitter: 60 * time.Millisecond, LossUp: 0.03, LossDown: 0.03, QPS: 20},
	}},
	// China: forged answers race the real ones on UDP/53; domestic resolvers rate-limit per IP.
	{Name: "chinalike", Resolvers: []emu.Impairment{
		{Delay: 40 * time.Millisecond, Jitter: 30 * time.Millisecond, Inject: true, QPS: 20, LossUp: 0.02, LossDown: 0.02},
		{Delay: 50 * time.Millisecond, Jitter: 30 * time.Millisecond, Inject: true, QPS: 20, LossUp: 0.02, LossDown: 0.02},
		{Delay: 45 * time.Millisecond, Jitter: 30 * time.Millisecond, Inject: true, QPS: 20, LossUp: 0.02, LossDown: 0.02},
	}},
	// Large answers policed once there's volume: after 40, over-512-byte DNS responses mostly
	// vanish (no TC), as seen live through Linode's resolvers.
	{Name: "policed", Resolvers: same(3, emu.Impairment{Delay: 40 * time.Millisecond, Jitter: 20 * time.Millisecond,
		LossUp: 0.01, LossDown: 0.01, DropLarge: 0.9, DropLargeAfter: 40})},
}

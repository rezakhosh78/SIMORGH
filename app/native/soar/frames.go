package soar

import (
	"errors"
	"slices"
)

const (
	frPadding   = 0x00
	frPing      = 0x01
	frAck       = 0x02 // ranges of the peer's packet numbers received
	frReset     = 0x03 // stream id, code: abort a stream in both directions
	frMaxStream = 0x04 // stream id, max offset the sender may send to
	frClose     = 0x05 // code: the session is over
	frLost      = 0x06 // client→server: upstream pns whose queries got no usable answer
	frOpened    = 0x07 // server→client: stream id, 0: the stream's destination dial succeeded
	frStream    = 0x08 // 0x08|OFF|LEN|FIN: stream id, [offset], [length], data

	streamFin = 0x01
	streamLen = 0x02
	streamOff = 0x04

	maxAckRanges  = 16
	maxLostPerPkt = 32
)

var errFrame = errors.New("soar: malformed frame")

type frame struct {
	typ    byte
	id     uint64 // stream id (stream, reset, maxStream)
	off    uint64 // stream offset
	data   []byte // stream data
	fin    bool
	val    uint64   // reset/close code, maxStream offset
	ranges rangeSet // ack
	lost   []uint64 // lost
}

// parseFrames calls fn for each frame in b. Stream data aliases b.
func parseFrames(b []byte, fn func(*frame) error) error {
	r := reader{b: b}
	for len(r.b) > 0 {
		var f frame
		f.typ = r.byte()
		switch {
		case f.typ == frPadding:
			// Padding runs to the end of the packet.
			return nil
		case f.typ == frPing:
		case f.typ == frAck:
			n := r.varint()
			if n == 0 || n > maxAckRanges*4 {
				return errFrame
			}
			var lo uint64
			for i := range n {
				var hi uint64
				if i == 0 {
					hi = r.varint() + 1
				} else {
					gap := r.varint()
					if gap+1 >= lo {
						return errFrame
					}
					hi = lo - gap - 1
				}
				ln := r.varint() + 1
				if ln > hi {
					return errFrame
				}
				lo = hi - ln
				f.ranges = append(f.ranges, span{lo, hi})
			}
			slices.Reverse(f.ranges)
		case f.typ == frReset:
			f.id, f.val = r.varint(), r.varint()
		case f.typ == frMaxStream, f.typ == frOpened:
			f.id, f.val = r.varint(), r.varint()
		case f.typ == frClose:
			f.val = r.varint()
		case f.typ == frLost:
			n := r.varint()
			if n > maxLostPerPkt*4 {
				return errFrame
			}
			var prev uint64
			for i := range n {
				v := r.varint()
				if i > 0 {
					if v+1 > prev {
						return errFrame
					}
					v = prev - v - 1
				}
				f.lost = append(f.lost, v)
				prev = v
			}
		case f.typ&0xF8 == frStream:
			f.id = r.varint()
			if f.typ&streamOff != 0 {
				f.off = r.varint()
			}
			n := uint64(len(r.b))
			if f.typ&streamLen != 0 {
				n = r.varint()
			}
			f.data = r.bytes(n)
			f.fin = f.typ&streamFin != 0
		default:
			return errFrame
		}
		if r.err != nil {
			return r.err
		}
		if err := fn(&f); err != nil {
			return err
		}
	}
	return nil
}

// appendAck encodes up to maxAckRanges of the highest spans in rs.
func appendAck(b []byte, rs rangeSet) []byte {
	n := min(len(rs), maxAckRanges)
	b = append(b, frAck)
	b = appendVarint(b, uint64(n))
	var prevLo uint64
	for i := range n {
		s := rs[len(rs)-1-i]
		if i == 0 {
			b = appendVarint(b, s.hi-1)
		} else {
			b = appendVarint(b, prevLo-s.hi-1)
		}
		b = appendVarint(b, s.hi-s.lo-1)
		prevLo = s.lo
	}
	return b
}

func ackLen(rs rangeSet) int {
	return len(appendAck(make([]byte, 0, 64), rs))
}

// appendLost encodes pns, which must be sorted descending and distinct.
func appendLost(b []byte, pns []uint64) []byte {
	b = append(b, frLost)
	b = appendVarint(b, uint64(len(pns)))
	for i, pn := range pns {
		if i == 0 {
			b = appendVarint(b, pn)
		} else {
			b = appendVarint(b, pns[i-1]-pn-1)
		}
	}
	return b
}

// streamHeaderLen is the size of a STREAM frame header that carries an explicit length.
func streamHeaderLen(id, off uint64, n int) int {
	h := 1 + varintLen(id) + varintLen(uint64(n))
	if off > 0 {
		h += varintLen(off)
	}
	return h
}

func appendStream(b []byte, id, off uint64, data []byte, fin, last bool) []byte {
	t := byte(frStream)
	if off > 0 {
		t |= streamOff
	}
	if !last {
		t |= streamLen
	}
	if fin {
		t |= streamFin
	}
	b = append(b, t)
	b = appendVarint(b, id)
	if off > 0 {
		b = appendVarint(b, off)
	}
	if !last {
		b = appendVarint(b, uint64(len(data)))
	}
	return append(b, data...)
}

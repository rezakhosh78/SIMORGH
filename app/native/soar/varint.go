package soar

import "errors"

var errShort = errors.New("soar: short buffer")

// QUIC variable-length integers (RFC 9000 §16): 1, 2, 4 or 8 bytes, length in the top two bits.

const maxVarint = 1<<62 - 1

func varintLen(v uint64) int {
	switch {
	case v < 1<<6:
		return 1
	case v < 1<<14:
		return 2
	case v < 1<<30:
		return 4
	default:
		return 8
	}
}

func appendVarint(b []byte, v uint64) []byte {
	switch varintLen(v) {
	case 1:
		return append(b, byte(v))
	case 2:
		return append(b, byte(v>>8)|0x40, byte(v))
	case 4:
		return append(b, byte(v>>24)|0x80, byte(v>>16), byte(v>>8), byte(v))
	default:
		return append(b, byte(v>>56)|0xC0, byte(v>>48), byte(v>>40), byte(v>>32),
			byte(v>>24), byte(v>>16), byte(v>>8), byte(v))
	}
}

// reader consumes a byte slice.
type reader struct {
	b   []byte
	err error
}

func (r *reader) varint() uint64 {
	if r.err != nil {
		return 0
	}
	if len(r.b) == 0 {
		r.err = errShort
		return 0
	}
	n := 1 << (r.b[0] >> 6)
	if len(r.b) < n {
		r.err = errShort
		return 0
	}
	v := uint64(r.b[0] & 0x3F)
	for i := 1; i < n; i++ {
		v = v<<8 | uint64(r.b[i])
	}
	r.b = r.b[n:]
	return v
}

func (r *reader) byte() byte {
	if r.err != nil {
		return 0
	}
	if len(r.b) == 0 {
		r.err = errShort
		return 0
	}
	c := r.b[0]
	r.b = r.b[1:]
	return c
}

func (r *reader) bytes(n uint64) []byte {
	if r.err != nil {
		return nil
	}
	if uint64(len(r.b)) < n {
		r.err = errShort
		return nil
	}
	s := r.b[:n]
	r.b = r.b[n:]
	return s
}

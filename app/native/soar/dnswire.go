package soar

import (
	"bytes"
	"encoding/binary"
	"errors"
	"strings"
)

const (
	typeTXT  = 16
	typeAAAA = 28
	// An AAAA record carries an index byte and 15 payload bytes: resolvers may shuffle an RRset
	// and drop duplicate records, so each is numbered (and so unique).
	aaaaChunk   = 15
	aaaaRRLen   = 2 + 10 + 16
	aaaaMaxRecs = 255
	typeOPT     = 41
	classIN     = 1
	hdrLen      = 12
	maxLabel    = 63
	// maxName is the longest presentation-format name (RFC 1035: 255 wire bytes).
	maxName = 253

	rcodeOK       = 0
	rcodeServFail = 2
	rcodeNXDomain = 3
	rcodeRefused  = 5

	b32 = "abcdefghijklmnopqrstuvwxyz234567"
)

var errDNS = errors.New("soar: malformed DNS message")

var b32Rev = func() (t [256]byte) {
	for i := range t {
		t[i] = 0xFF
	}
	for i := range len(b32) {
		t[b32[i]] = byte(i)
		if b32[i] >= 'a' {
			t[b32[i]-'a'+'A'] = byte(i)
		}
	}
	return t
}()

func b32EncLen(n int) int { return (n*8 + 4) / 5 }

func b32Append(dst, src []byte) []byte {
	var acc uint64
	var bits uint
	for _, c := range src {
		acc = acc<<8 | uint64(c)
		bits += 8
		for bits >= 5 {
			bits -= 5
			dst = append(dst, b32[(acc>>bits)&0x1F])
		}
	}
	if bits > 0 {
		dst = append(dst, b32[(acc<<(5-bits))&0x1F])
	}
	return dst
}

// b32Decode decodes unpadded base32 case-insensitively: resolvers may randomize case (0x20).
func b32Decode(dst, src []byte) ([]byte, error) {
	var acc uint64
	var bits uint
	for _, c := range src {
		v := b32Rev[c]
		if v == 0xFF {
			return nil, errDNS
		}
		acc = acc<<5 | uint64(v)
		bits += 5
		if bits >= 8 {
			bits -= 8
			dst = append(dst, byte(acc>>bits))
		}
	}
	return dst, nil
}

// zone is a tunnel domain in wire form, lower-cased, without the root label.
type zone struct {
	wire []byte // length-prefixed labels
	text string
}

func parseZone(s string) (zone, error) {
	// Validate the characters as given, then fold ASCII case only: Unicode folding would map
	// some non-ASCII input onto ASCII (the Kelvin sign "\u212a" onto "k") and slip it past the
	// check below.
	s = strings.TrimSuffix(strings.TrimSpace(s), ".")
	for i := range len(s) {
		if s[i] < '!' || s[i] > '~' {
			return zone{}, errors.New("soar: zone must be printable ASCII")
		}
	}
	s = asciiLower(s)
	if s == "" {
		return zone{}, errors.New("soar: empty zone")
	}
	var w []byte
	for _, l := range strings.Split(s, ".") {
		if l == "" || len(l) > maxLabel {
			return zone{}, errors.New("soar: bad zone")
		}
		w = append(w, byte(len(l)))
		w = append(w, l...)
	}
	z := zone{wire: w, text: s}
	if z.maxPayload(maxName) < 2+pubLen {
		return zone{}, errors.New("soar: zone too long to carry a handshake")
	}
	return z, nil
}

// asciiLower folds A-Z to a-z and leaves every other byte alone. Zones are printable ASCII
// (SPEC §3.1), so every implementation folds them the same way.
func asciiLower(s string) string {
	b := []byte(s)
	for i, c := range b {
		if 'A' <= c && c <= 'Z' {
			b[i] = c + 'a' - 'A'
		}
	}
	return string(b)
}

// maxPayload returns how many bytes fit base32-encoded under z in a name of at most nameLen
// presentation characters.
func (z zone) maxPayload(nameLen int) int {
	avail := min(nameLen, maxName) - len(z.text) - 1 // the dot before the zone
	// c chars need ceil(c/63)-1 separating dots.
	c := avail
	for c > 0 && c+(c-1)/maxLabel > avail {
		c--
	}
	return c * 5 / 8
}

// appendQuery appends a qtype (TXT or AAAA) query for payload under z.
func appendQuery(b []byte, txn uint16, payload []byte, z zone, qtype uint16, ednsSize uint16) []byte {
	b = binary.BigEndian.AppendUint16(b, txn)
	b = append(b, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 1) // RD; QD=1, AR=1
	var enc [maxName]byte
	e := b32Append(enc[:0], payload)
	for len(e) > 0 {
		n := min(len(e), maxLabel)
		b = append(b, byte(n))
		b = append(b, e[:n]...)
		e = e[n:]
	}
	b = append(b, z.wire...)
	b = append(b, 0)
	b = binary.BigEndian.AppendUint16(b, qtype)
	b = binary.BigEndian.AppendUint16(b, classIN)
	return appendOPT(b, ednsSize)
}

func appendOPT(b []byte, size uint16) []byte {
	b = append(b, 0)
	b = binary.BigEndian.AppendUint16(b, typeOPT)
	b = binary.BigEndian.AppendUint16(b, size)
	return append(b, 0, 0, 0, 0, 0, 0)
}

// query is a parsed incoming query.
type query struct {
	txn      uint16
	rd       bool
	question []byte // QNAME through QCLASS, as received
	qtype    uint16
	payload  []byte // decoded tunnel payload, or nil if the name isn't under the zone
	edns     int    // the sender's UDP payload size; 512 without OPT
	hasOPT   bool
	size     int  // bytes in the query message, for amplification accounting
	inZone   bool // the name is the zone or under it
	apex     bool // the name is the zone itself
}

// parseQuery parses a query and decodes the tunnel payload under z.
func parseQuery(msg []byte, z zone) (*query, error) {
	if len(msg) < hdrLen || msg[2]&0x80 != 0 || binary.BigEndian.Uint16(msg[4:]) != 1 {
		return nil, errDNS
	}
	q := &query{txn: binary.BigEndian.Uint16(msg), rd: msg[2]&0x01 != 0, edns: 512, size: len(msg)}
	var labels [][]byte
	i := hdrLen
	for {
		if i >= len(msg) {
			return nil, errDNS
		}
		n := int(msg[i])
		if n == 0 {
			i++
			break
		}
		if n > maxLabel || i+1+n > len(msg) {
			return nil, errDNS
		}
		labels = append(labels, msg[i+1:i+1+n])
		i += 1 + n
	}
	if i+4 > len(msg) {
		return nil, errDNS
	}
	q.qtype = binary.BigEndian.Uint16(msg[i:])
	q.question = msg[hdrLen : i+4]
	i += 4
	if binary.BigEndian.Uint16(msg[10:]) > 0 && i+11 <= len(msg) && msg[i] == 0 &&
		binary.BigEndian.Uint16(msg[i+1:]) == typeOPT {
		q.hasOPT = true
		q.edns = max(512, int(binary.BigEndian.Uint16(msg[i+3:])))
	}
	// Match the zone suffix case-insensitively.
	zl := 0
	for w := z.wire; len(w) > 0; w = w[1+int(w[0]):] {
		zl++
	}
	if len(labels) < zl {
		return q, nil
	}
	w := z.wire
	for _, l := range labels[len(labels)-zl:] {
		if int(w[0]) != len(l) || !bytes.EqualFold(w[1:1+len(l)], l) {
			return q, nil
		}
		w = w[1+len(l):]
	}
	q.inZone, q.apex = true, len(labels) == zl
	if q.apex {
		return q, nil
	}
	var enc []byte
	for _, l := range labels[:len(labels)-zl] {
		enc = append(enc, l...)
	}
	p, err := b32Decode(make([]byte, 0, len(enc)*5/8), enc)
	if err != nil {
		return q, nil
	}
	q.payload = p
	return q, nil
}

// answerOverhead is the size of a TXT answer to q, excluding the RDATA.
func answerOverhead(q *query) int {
	n := hdrLen + len(q.question) + 2 + 2 + 2 + 4 + 2
	if q.hasOPT {
		n += 11
	}
	return n
}

// capacity is how many payload bytes fit in an answer of at most limit bytes to q.
func capacity(q *query, limit int) int {
	if q.qtype == typeAAAA {
		recs := min((limit-answerOverhead(q)+12)/aaaaRRLen, aaaaMaxRecs)
		return max(recs*aaaaChunk-2, 0)
	}
	room := limit - answerOverhead(q)
	if room <= 1 {
		return 0
	}
	// Each 255-byte character-string costs one length byte.
	return room - (room+255)/256
}

// appendAnswer appends a response to q carrying payload in records of the query's type, or no
// records if payload is nil.
func appendAnswer(b []byte, q *query, rcode byte, payload []byte) []byte {
	if q.qtype == typeAAAA && payload != nil {
		return appendAAAAAnswer(b, q, rcode, payload)
	}
	b = binary.BigEndian.AppendUint16(b, q.txn)
	flags := byte(0x84) // QR, AA
	if q.rd {
		flags |= 0x01
	}
	an := 0
	if payload != nil {
		an = 1
	}
	ar := 0
	if q.hasOPT {
		ar = 1
	}
	b = append(b, flags, rcode, 0, 1, 0, byte(an), 0, 0, 0, byte(ar))
	b = append(b, q.question...)
	if payload != nil {
		b = append(b, 0xC0, hdrLen)
		b = binary.BigEndian.AppendUint16(b, typeTXT)
		b = binary.BigEndian.AppendUint16(b, classIN)
		b = append(b, 0, 0, 0, 0) // TTL 0: never cache
		rdlen := len(payload) + (len(payload)+254)/255
		if len(payload) == 0 {
			rdlen = 1
		}
		b = binary.BigEndian.AppendUint16(b, uint16(rdlen))
		if len(payload) == 0 {
			b = append(b, 0)
		}
		for len(payload) > 0 {
			n := min(len(payload), 255)
			b = append(b, byte(n))
			b = append(b, payload[:n]...)
			payload = payload[n:]
		}
	}
	if q.hasOPT {
		b = appendOPT(b, 1232)
	}
	return b
}

func appendAnswerHeader(b []byte, q *query, rcode byte, an int) []byte {
	b = binary.BigEndian.AppendUint16(b, q.txn)
	flags := byte(0x84) // QR, AA
	if q.rd {
		flags |= 0x01
	}
	ar := 0
	if q.hasOPT {
		ar = 1
	}
	b = append(b, flags, rcode, 0, 1)
	b = binary.BigEndian.AppendUint16(b, uint16(an))
	b = append(b, 0, 0, 0, byte(ar))
	return append(b, q.question...)
}

// appendAAAAAnswer carries [u16 length][payload] in numbered 15-byte chunks.
func appendAAAAAnswer(b []byte, q *query, rcode byte, payload []byte) []byte {
	data := binary.BigEndian.AppendUint16(make([]byte, 0, 2+len(payload)), uint16(len(payload)))
	data = append(data, payload...)
	recs := (len(data) + aaaaChunk - 1) / aaaaChunk
	b = appendAnswerHeader(b, q, rcode, recs)
	for i := range recs {
		b = append(b, 0xC0, hdrLen)
		b = binary.BigEndian.AppendUint16(b, typeAAAA)
		b = binary.BigEndian.AppendUint16(b, classIN)
		b = append(b, 0, 0, 0, 0, 0, 16, byte(i))
		chunk := data[i*aaaaChunk : min((i+1)*aaaaChunk, len(data))]
		b = append(b, chunk...)
		for range aaaaChunk - len(chunk) {
			b = append(b, 0)
		}
	}
	if q.hasOPT {
		b = appendOPT(b, 1232)
	}
	return b
}

const typeSOA = 6

// appendNoData answers an in-zone query that isn't tunnel traffic: NOERROR with no records and
// the zone's SOA in the authority section (or the SOA itself, for an SOA query at the apex).
// Resolvers doing QNAME minimisation (RFC 9156) walk down one label at a time; NXDOMAIN or
// REFUSED for those partial names would make them abandon the real query.
func appendNoData(b []byte, q *query, z zone) []byte {
	soa := q.apex && q.qtype == typeSOA
	b = binary.BigEndian.AppendUint16(b, q.txn)
	flags := byte(0x84) // QR, AA
	if q.rd {
		flags |= 0x01
	}
	an, ns, ar := 0, 1, 0
	if soa {
		an, ns = 1, 0
	}
	if q.hasOPT {
		ar = 1
	}
	b = append(b, flags, rcodeOK, 0, 1, 0, byte(an), 0, byte(ns), 0, byte(ar))
	b = append(b, q.question...)
	b = append(b, z.wire...)
	b = append(b, 0)
	b = binary.BigEndian.AppendUint16(b, typeSOA)
	b = binary.BigEndian.AppendUint16(b, classIN)
	b = append(b, 0, 0, 0, 60) // TTL
	rd := append(append(append([]byte(nil), z.wire...), 0), append(append([]byte(nil), z.wire...), 0)...)
	for _, v := range []uint32{1, 3600, 600, 86400, 60} { // serial, refresh, retry, expire, negative TTL
		rd = binary.BigEndian.AppendUint32(rd, v)
	}
	b = binary.BigEndian.AppendUint16(b, uint16(len(rd)))
	b = append(b, rd...)
	if q.hasOPT {
		b = appendOPT(b, 1232)
	}
	return b
}

// answer is a parsed response.
type answer struct {
	txn      uint16
	tc       bool
	rcode    int
	question []byte
	txt      []byte // the carried payload (TXT strings, or reassembled AAAA chunks)
	hasTXT   bool   // a carrier record was present
}

func parseAnswer(msg []byte) (*answer, error) {
	if len(msg) < hdrLen || msg[2]&0x80 == 0 {
		return nil, errDNS
	}
	a := &answer{
		txn: binary.BigEndian.Uint16(msg), tc: msg[2]&0x02 != 0, rcode: int(msg[3] & 0x0F),
	}
	if binary.BigEndian.Uint16(msg[4:]) != 1 {
		return nil, errDNS
	}
	an := int(binary.BigEndian.Uint16(msg[6:]))
	i, err := skipName(msg, hdrLen)
	if err != nil || i+4 > len(msg) {
		return nil, errDNS
	}
	a.question = msg[hdrLen : i+4]
	i += 4
	var aaaa [][]byte
	for range an {
		if i, err = skipName(msg, i); err != nil || i+10 > len(msg) {
			return nil, errDNS
		}
		typ := binary.BigEndian.Uint16(msg[i:])
		rdlen := int(binary.BigEndian.Uint16(msg[i+8:]))
		i += 10
		if i+rdlen > len(msg) {
			return nil, errDNS
		}
		if typ == typeAAAA && rdlen == 16 {
			aaaa = append(aaaa, msg[i:i+16])
		}
		if typ == typeTXT {
			a.hasTXT = true
			rd := msg[i : i+rdlen]
			for len(rd) > 0 {
				n := int(rd[0])
				if 1+n > len(rd) {
					return nil, errDNS
				}
				a.txt = append(a.txt, rd[1:1+n]...)
				rd = rd[1+n:]
			}
		}
		i += rdlen
	}
	if len(aaaa) > 0 && !a.hasTXT {
		data := make([]byte, len(aaaa)*aaaaChunk)
		seen := make([]bool, len(aaaa))
		for _, rr := range aaaa {
			k := int(rr[0])
			if k >= len(aaaa) || seen[k] {
				return nil, errDNS
			}
			seen[k] = true
			copy(data[k*aaaaChunk:], rr[1:])
		}
		n := int(binary.BigEndian.Uint16(data))
		if 2+n > len(data) {
			return nil, errDNS
		}
		a.txt, a.hasTXT = data[2:2+n], true
	}
	return a, nil
}

func skipName(msg []byte, i int) (int, error) {
	for {
		if i >= len(msg) {
			return 0, errDNS
		}
		n := int(msg[i])
		switch {
		case n == 0:
			return i + 1, nil
		case n&0xC0 == 0xC0:
			return i + 2, nil
		case n > maxLabel:
			return 0, errDNS
		}
		i += 1 + n
	}
}

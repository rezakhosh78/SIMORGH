# Soar wire protocol, version 1

This document specifies what two Soar implementations must agree on to interoperate: the DNS
carrier, the handshake, packet protection, frames, and the session rules both ends depend on. The
Go code in this repository is the reference implementation. Where this document and the code
disagree, that's a bug in one of them; [`testdata/vectors.json`](testdata/vectors.json) is
generated from the code and decides which.

Everything a client chooses for performance (which resolvers to use, how many queries to keep in
flight, how it probes name and answer sizes) is outside the wire format. Section 9 describes the
reference implementation's choices, which another implementation should copy if it wants the same
behaviour under censorship, but doesn't need for compatibility.

The key words MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119.

**Status:** pre-release. Version 1 may still change until it's declared stable. Every change to the
wire format also regenerates the test vectors (section 10).

## 1. Overview

A **client** carries TCP streams to a **server** through recursive DNS resolvers. The server is
authoritative for a delegated **zone** (say `t.example.com`). The client writes data into query
names under the zone; resolvers forward those queries to the server, and the server answers with
data in TXT records (or AAAA records where TXT is refused).

- Each DNS query carries exactly one **upstream packet**. Each answer to a data query carries
  exactly one **downstream packet**, cryptographically bound to the query it answers.
- A **session** is created by a handshake and named by a 16-bit **session id** (sid). The
  handshake is ephemeral X25519, with the server's long-term Ed25519 key signing the transcript.
- Packets carry **frames**: stream data, acknowledgements, flow control and loss reports.
- The client opens **streams**. A stream's first bytes name its destination; the server dials it
  and relays bytes both ways.

Only the server can produce an authenticated answer, so an answer proves that its query arrived.
The upstream direction therefore needs no acknowledgement frames. Downstream packets are
acknowledged explicitly by the client.

## 2. Conventions

- Multi-byte integers are big-endian unless stated otherwise.
- `||` is concatenation. `x[a:b]` is bytes `a` (inclusive) to `b` (exclusive).
- **varint**: the QUIC variable-length integer (RFC 9000 §16). The top two bits of the first
  byte give the length (1, 2, 4 or 8 bytes) and the remaining bits hold the value, big-endian,
  up to 2^62−1. Encoders MUST use the shortest form. Decoders accept any form.
- Names are measured in **presentation characters**: labels plus the dots between them, without
  a trailing dot. `abc.t.example.com` is 17 characters.

## 3. The DNS carrier

### 3.1 Payload in query names

An upstream payload (the bytes in section 5) is encoded as:

1. **Base32** with the RFC 4648 alphabet in lower case (`abcdefghijklmnopqrstuvwxyz234567`),
   **unpadded**. Bits are taken most-significant first. A final group of fewer than 5 bits is
   padded on the right with zero bits. An encoding of `n` bytes is `ceil(8n/5)` characters.
2. Split into labels of 63 characters. Every label is full except the last.
3. Followed by the zone's labels.

A query name MUST NOT exceed 253 presentation characters. For a zone of `Z` characters and a
name limit of `N`, the most payload that fits is computed as follows (vectors: `name_capacity`):

```
avail = min(N, 253) − Z − 1           # the dot before the zone
c     = largest c ≤ avail with c + floor((c−1)/63) ≤ avail   # chars + the dots between labels
max_payload = floor(5c / 8)
```

Decoders MUST accept either letter case: resolvers may randomize case (DNS 0x20). A decoder
concatenates all the labels before the zone, whatever their lengths, and decodes the result. It
MUST NOT reject leftover bits that don't make a whole byte, whatever their value or number: it drops
them. A name containing a character outside the alphabet carries no payload.

A zone is one or more labels separated by dots, with an optional trailing dot. Each label MUST be
1 to 63 printable ASCII characters (`!` to `~`) other than the dot, which separates labels. Zones are compared ignoring
ASCII case. Real zones use letters, digits and hyphens, but implementations MUST accept the full
printable range, so that every implementation accepts the same configurations. A zone MUST also
leave room for a 34-byte payload within 253 characters (the hello in section 4.2).

### 3.2 Queries

A client query is a standard DNS query:

| field | value |
|---|---|
| ID | random 16 bits, unique among the client's outstanding queries |
| flags | `0x0100`: RD set, everything else zero |
| QDCOUNT, ANCOUNT, NSCOUNT, ARCOUNT | 1, 0, 0, 1 |
| question | the payload name, QTYPE TXT (16) or AAAA (28), QCLASS IN (1) |
| additional | an OPT RR: root name, TYPE 41, CLASS (UDP payload size) 1232, TTL 0, RDLENGTH 0 |

Name compression MUST NOT be used in queries.

### 3.3 How the server classifies a query

The server ignores messages with QR set or QDCOUNT ≠ 1. It reads the question. Its **EDNS size**
is the OPT RR's class value (at least 512) when ARCOUNT > 0 and an OPT RR immediately follows the
question; otherwise it is 512. The server then matches the zone's labels against the end of QNAME,
ignoring ASCII case, and decides:

1. **Not under the zone:** REFUSED, with no records.
2. **The zone apex**; a name that isn't valid base32; a QTYPE other than TXT or AAAA; or a payload
   shorter than 2 bytes: a **no-data** answer (section 3.6).
3. **sid = 0** (the first 2 payload bytes): a hello (section 4.2).
4. **An unknown sid:** the signed unknown-session answer (section 4.5).
5. **A payload shorter than 16 bytes**, or one that fails authentication: no-data.
6. **Otherwise:** a data query (section 7).

### 3.4 Answers

The answer to a query copies its ID. The first flags byte is `0x84` (QR, AA), or `0x85` when
the query had RD set. The second flags byte is the response code: RA, Z, AD and CD are all zero.
QDCOUNT is 1, and the question is copied **byte for byte** from the query, so the case the resolver
chose is preserved. If the query had an OPT RR, the answer ends with one: UDP payload size 1232,
TTL 0, RDLENGTH 0. A carried payload goes in the answer section in the record type of the query:

**TXT.** One record: name `0xC00C` (a pointer to the question), TYPE TXT, CLASS IN, TTL 0. The
RDATA is the payload split into character-strings of up to 255 bytes, every one full except the
last. An empty payload is a single empty string (RDLENGTH 1).

**AAAA.** The bytes `len(payload) as u16 || payload` are split into 15-byte chunks, with the last
chunk zero-padded. Chunk `i` (from 0) becomes a record: name `0xC00C`, TYPE AAAA, CLASS IN, TTL 0,
RDLENGTH 16, RDATA `i (1 byte) || chunk`. There are at most 255 records. Each record is numbered
because resolvers may shuffle an RRset and drop duplicate records.

A receiver parses the answer section as follows:

- When any TXT record is present, the payload is the concatenation of all TXT records'
  character-strings in order, and AAAA records are ignored.
- Otherwise, every AAAA record with RDLENGTH 16 is a chunk. Their index bytes MUST be distinct and
  less than the number of chunks, or the answer is malformed. Chunks are placed by index, in any
  order. The leading u16 MUST NOT claim more bytes than the chunks hold.
- If there are neither, no payload was carried.

A client matches an answer to its query by ID **and** by question, comparing the question's bytes
with ASCII case ignored. Anything else is dropped.

### 3.5 Answer sizes

Every upstream data packet names an **answer size class** `c` (section 5.1). The server keeps each
answer within `limit = min(512 + 48c, EDNS size)` bytes. These are the payload bytes that fit
(vectors: `dns`, kind `capacity`):

```
overhead = 12 + len(question) + 10 + 2 + (11 if the query had an OPT RR)
TXT:   room = limit − overhead;  capacity = room − ceil(room / 256)   (0 if room ≤ 1)
AAAA:  records = min(floor((limit − overhead + 12) / 28), 255);  capacity = max(15·records − 2, 0)
```

### 3.6 No-data answers

The server answers in-zone queries that aren't tunnel traffic (case 2 above) with a no-data
answer. Resolvers doing QNAME minimisation (RFC 9156) send queries like these, and an error answer
would make them give up on the real name. The no-data answer is NOERROR with an SOA record for the
zone. For an SOA query at the apex, the SOA goes in the answer section; otherwise it goes in the
authority section.

The SOA record's owner name, MNAME and RNAME are all the zone, written out uncompressed. It has
TTL 60, serial 1, refresh 3600, retry 600, expire 86400 and minimum 60. The OPT rule from section
3.4 applies. The `dns` vectors include two.

### 3.7 TCP

A client MAY send queries to a resolver over TCP (RFC 7766): each message is prefixed with its
length as a u16, and many queries are pipelined on one connection. Over TCP the messages and the
size limits are the same as over UDP. The reference server listens on UDP only.

## 4. Handshake and keys

### 4.1 Server key

The server has a long-term Ed25519 key pair (RFC 8032). Clients are given its 32-byte public key
out of band.

### 4.2 Hello

The client generates an ephemeral X25519 key pair (RFC 7748) and sends the upstream payload:

```
hello = 0x0000 (sid 0) || client ephemeral public key (32)
```

Servers MUST ignore any bytes beyond these 34. The client MAY send the same hello down several
resolvers at once, and MAY repeat it.

The server:

1. Picks an unused sid in 1–65535, and an ephemeral X25519 key pair.
2. Computes the **transcript**
   `T = "soar v1 hello" (13 ASCII bytes) || client eph pub || server eph pub || sid (u16)`.
3. Answers NOERROR with the 98-byte payload
   `server eph pub (32) || sid (u16) || Ed25519 signature over T (64)`.

The server SHOULD answer every copy of a hello (the same client public key) with the same reply
while it keeps the reply cached. The reference server caches a reply for a minute, or until its
session ends if that comes first. A copy arriving later may start a second session; that's
harmless, since the client takes the first valid reply and the unused session idles out. If the
server can't create a session (it's overloaded, or out of sids), it answers REFUSED.

The client verifies the signature with the server's public key. It MUST ignore a reply whose
signature doesn't verify, since forged answers are expected on hostile networks, and keep waiting.
The first valid reply establishes the session. Both sides compute the X25519 shared secret `ee`
and MUST abort if it is all zeros (a low-order point).

### 4.3 Key schedule

With HKDF using SHA-256 (RFC 5869):

```
PRK = HKDF-Extract(salt = T, IKM = ee)
for dir in "up", "down":
    key_dir = HKDF-Expand(PRK, "soar v1 " + dir + " key", 32)
    iv_dir  = HKDF-Expand(PRK, "soar v1 " + dir + " iv",  12)
    hp_dir  = HKDF-Expand(PRK, "soar v1 " + dir + " hp",  32)
```

The `info` strings are ASCII with single spaces, for example `soar v1 up key`. **up** protects
client→server packets and **down** protects server→client packets.

### 4.4 Packet protection

**AEAD.** ChaCha20-Poly1305 as in RFC 8439 §2.8, except that the 16-byte tag is **truncated to its
first 8 bytes**. To seal `plaintext` with associated data `ad` and packet number `pn`:

```
nonce      = iv XOR (0x00000000 || pn as u64)            # 12 bytes
polykey    = ChaCha20(key, nonce, counter 0) keystream[0:32]
ciphertext = plaintext XOR ChaCha20(key, nonce, counter 1…)
tag        = Poly1305(polykey, ad || pad16 || ciphertext || pad16 ||
                      len(ad) as u64 LE || len(ciphertext) as u64 LE)[0:8]
sealed     = ciphertext || tag
```

This is exactly RFC 8439's AEAD with the tag cut short. A library AEAD that only produces 16-byte
tags can't be used directly. Compute the tag from the ChaCha20 and Poly1305 primitives instead.
Receivers MUST compare the 8-byte tag in constant time.

**Packet-number protection.** The truncated packet number on the wire is masked:

```
sample = sealed[0:12]
mask   = ChaCha20(hp, nonce = sample, counter 0) keystream[0:2]
```

This is **not** QUIC's ChaCha20 header protection (RFC 9001 §5.4.4). QUIC takes the counter from
the first 4 bytes of a 16-byte sample. Here the whole 12-byte sample is the nonce and the counter
is 0. Because `sealed` must be at least 12 bytes, every plaintext is at least 4 bytes.

### 4.5 Unknown session

When a data query names a sid the server doesn't have, the server answers **NXDOMAIN** with a
64-byte payload: an Ed25519 signature over

```
"soar v1 unknown session" (23 ASCII bytes) || lower(question)
```

`question` is the query's QNAME (wire form), QTYPE and QCLASS, as received. `lower` maps the ASCII
bytes A–Z to a–z and leaves every other byte alone. The server SHOULD rate-limit this signing (the
reference allows 200 per second). Past that limit it sends a no-data answer instead.

A client that receives NXDOMAIN to a data query of an established session checks for a 64-byte
payload that verifies over its own question. If it verifies, the session is over. An NXDOMAIN
without a valid signature MUST be ignored, since anyone can forge one.

## 5. Packets

### 5.1 Upstream (client → server)

```
payload   = sid (u16) || protected_pn (2) || sealed
ad        = sid (u16) || pn mod 2^16 (u16)          # unmasked
plaintext = header (1) || frames
header    = hold_class << 4 | size_class
```

- `protected_pn = (pn mod 2^16) XOR mask`, with `mask` computed from `sealed` (section 4.4) under
  the **up** keys.
- `hold_class` (0–15) asks the server to hold the query up to `100 ms × hold_class` when it has
  nothing to send (section 7.2). The server applies its own cap (1.5 s in the reference).
- `size_class` (0–15) sets the answer size limit `512 + 48 × size_class` bytes (section 3.5).
- `frames` MUST be at least 4 bytes. Pad with PADDING if necessary.

The sid is associated data, so a packet moved to another session fails authentication.

### 5.2 Downstream (server → client)

```
payload   = protected_pn (2) || sealed
ad        = pn mod 2^16 (u16) || up_pn (u64)        # up_pn: full number of the answered upstream packet
plaintext = held (1) || backlog (1) || frames
```

- `protected_pn` is masked as above, under the **down** keys.
- `up_pn` binds the answer to the query it answers. A valid answer replayed onto a different query
  fails authentication, so it can't falsely acknowledge that query's data. The client knows `up_pn`
  because it sent that packet.
- `held` is how long the server held the query, in 10 ms units, saturating at 255. The client
  subtracts it from round-trip measurements.
- `backlog` is the server's estimate of how many more full packets it has queued, saturating at
  255. The client uses it to decide how many polls to keep outstanding.
- `frames` MUST be at least 4 bytes, padded with PADDING if necessary. So the plaintext is at least 6
  bytes.

Receivers enforce only what they need: an upstream payload of at least 16 bytes (section 3.3), a
downstream payload of at least 14 bytes (a 2-byte packet number and a 12-byte sample), and, after
opening, at least 1 upstream or 2 downstream plaintext bytes. The 4-byte floor on frames is a rule
for senders.

### 5.3 Packet numbers

Each direction of a session has its own packet-number space. Packet numbers start at 0, go up by
one for each packet sealed, and are never reused, including for retransmissions: lost content goes
out again in a new packet. The wire carries the low 16 bits, and the receiver recovers the full
number from the largest number it has received so far, as in RFC 9000 §A.3 with a 16-bit window
(vectors: `packet_number`):

```
expected = largest + 1 if any packet has been received, else 0
cand     = (expected & ~0xFFFF) | truncated
if cand + 0x8000 ≤ expected and cand < 2^62 − 0x10000:  pn = cand + 0x10000
elif cand > expected + 0x8000 and cand ≥ 0x10000:       pn = cand − 0x10000
else:                                                     pn = cand
```

Only authenticated packets update `largest`. A sender MUST keep its outstanding packet numbers
within 2^15 of the largest the receiver has seen.

**Duplicates and replays.** A receiver remembers which packet numbers it has received, at least
within a window of 2^14 below the largest. It MUST NOT process the frames of a packet it has already
received, or of one more than 2^14 below the largest. A server still answers a data query whose
packet is a duplicate (resolvers retry), as described in section 7.2.

## 6. Frames

A packet's frames are concatenated. The first byte of each frame is its type. A receiver that
finds an unknown type, a truncated frame, or a frame breaking the rules below MUST treat the
session as failed and end it (vectors: `bad_frames`).

| type | name | sent by | contents |
|---|---|---|---|
| `0x00` | PADDING | both | none: the rest of the packet is padding |
| `0x01` | PING | both | none |
| `0x02` | ACK | client | ranges of downstream packet numbers received |
| `0x03` | RESET_STREAM | both | stream id, code |
| `0x04` | MAX_STREAM_DATA | both | stream id, maximum offset |
| `0x05` | CLOSE | both | code |
| `0x06` | LOST | client | upstream packet numbers that got no usable answer |
| `0x07` | OPENED | server | stream id, reserved |
| `0x08`–`0x0F` | STREAM | both | stream id, [offset], [length], data |

**PADDING** (`0x00`). The receiver stops parsing at the first PADDING byte. Everything after it is
padding, whatever its value.

**PING** (`0x01`). Asks for an acknowledgement and carries nothing else. The reference
implementation parses PING but never sends it.

**ACK** (`0x02`). `count (varint) || largest (varint) || first_length (varint) || {gap (varint)
|| length (varint)} × (count − 1)`. The ranges are listed highest first. The first range is
`[largest − first_length, largest]`, inclusive. Each later range ends `gap + 2` below the start of
the range before it, and covers `length + 1` numbers. A decoder computes half-open ranges
`[lo, hi)` like this:

```
hi = largest + 1;            lo = hi − (first_length + 1)
for each later range:  hi = lo − gap − 1;  lo = hi − (length + 1)
```

A decoder MUST reject `count` = 0 or > 64, any range extending below 0, and any `gap + 1 ≥ lo`
(where `lo` is the previous range's start). Encoders send at most 16 ranges, the highest ones.

**RESET_STREAM** (`0x03`). `stream id (varint) || code (varint)`. The sender will neither send nor
read any more on the stream (section 8.4). Code 0 means the stream was closed or refused. Code 1
means the server couldn't reach the stream's destination. Receivers don't otherwise interpret the
code.

**MAX_STREAM_DATA** (`0x04`). `stream id (varint) || maximum (varint)`. The peer may send stream
data up to (not including) offset `maximum`. A limit only ever rises. A smaller value than the
current one is ignored.

**CLOSE** (`0x05`). `code (varint)`. The session is over and its streams are aborted. The reference
implementation parses CLOSE but never sends it.

**LOST** (`0x06`). `count (varint) || first (varint) || delta (varint) × (count − 1)`. The first
packet number is given directly, and each later one is `previous − delta − 1`, so the numbers are
distinct and descending by construction. A decoder MUST reject `count` > 128 and any number that
would fall below 0. Encoders send at
most 32. See section 7.3.

**OPENED** (`0x07`). `stream id (varint) || reserved (varint)`. The server reached the stream's
destination. Senders MUST set `reserved` to 0, and receivers MUST ignore it.

**STREAM** (`0x08`–`0x0F`). The type's low three bits are flags: `0x04` OFF, `0x02` LEN, `0x01` FIN.

```
stream id (varint) || [offset (varint), if OFF] || [length (varint), if LEN] || data
```

Without OFF, the offset is 0. Without LEN, the data runs to the end of the packet. FIN marks the
end of the stream at `offset + length`. A frame may have FIN and no data. The reference encoder
always sets LEN, and sets OFF only for a nonzero offset.

Every frame except ACK, LOST and PADDING is **ack-eliciting**.

## 7. Session behaviour

### 7.1 One packet per query, one answer per query

A client data query carries one upstream packet. For each data query it receives, the server sends
exactly one answer, carrying one new downstream packet, with `up_pn` set to the number of the
upstream packet in that query. This rule also covers duplicates: the server answers them, but
doesn't process their frames again. If the query's packet number is one the client has reported
LOST (section 7.3), the answer carries no frames (just the header and padding).

When the client receives an authenticated answer, everything in the upstream packet `up_pn` was
delivered. That's how upstream data is acknowledged.

### 7.2 Holding polls

The server answers a data query **at once** if any of these holds:

- its `hold_class` is 0;
- its packet carried any frame other than ACK, LOST, PING and PADDING. This includes a duplicate
  packet, such as a resolver's retry: its frames aren't reapplied (§5.3), but they still decide
  how it's answered;
- the session is closed.

Otherwise the server **holds** the query, up to `min(100 ms × hold_class, server cap)`. It answers
held queries **oldest first**, whenever it has data or control frames to send. That happens at once
if it already has some, including data that the new query's own frames made pending (a LOST frame
re-queues lost data). An unanswered held query is answered when its hold time runs out.

Holding gives the client a way to receive downstream data as soon as it exists: the client keeps
some polls held at the server, and any data the server produces leaves at once.

Releasing polls in arrival order is part of the contract, not an optimization. The client relies on
it to tell a lost poll from a held one (§7.3). If a newer poll were answered while an older one is
held, the client would conclude the older one was lost.

The reference server holds at most 128 queries per session; past that it answers the oldest.

### 7.3 Acknowledgement and loss

- **Upstream** loss is detected by the client: a query that doesn't get an authenticated answer
  in time is lost. Data queries are answered at once, so a data query overtaken by later answered
  queries is lost. A poll may legitimately be held, so a poll is judged lost early only when a
  *later poll* has been answered, which §7.2's release order makes conclusive. The client
  retransmits a lost packet's content in new packets. It also reports the packet number in a LOST
  frame, because the query might have reached the server and only the answer got lost.
- When the server receives LOST for an upstream packet:
  - If it already answered that packet, it treats the downstream packet in the answer as lost and
    retransmits its content.
  - If not, it drops the query if it's held, and answers any later copy of it with an empty packet
    (section 7.1). The client has given up on that query, so an answer lost now would never be
    reported.
- **Downstream** delivery is acknowledged by the client's ACK frames. The client acknowledges every
  downstream packet that carried an ack-eliciting frame, and MUST NOT acknowledge packets that
  carried none. Otherwise acknowledging an empty answer would need a query, whose empty answer would
  need an acknowledgement, and an idle session would never go quiet.
- When to declare a downstream packet lost is the server's choice. The reference declares a packet
  lost when one sent later has been acknowledged and it has been outstanding more than 9/8 of the
  smoothed acknowledgement RTT. It also declares lost any packet unacknowledged for 3 s.

### 7.4 Ending a session

Version 1 has no handshake for ending a session:

- The server ends a session that has sent no new packet for its idle timeout (2 minutes in the
  reference). It also ends a session that never sent a valid data packet within 15 s of the hello.
- A client learns its session is gone from the signed unknown-session answer (section 4.5). As a
  backstop, the reference client also gives up after 15 s with no authenticated answer while it has
  queries outstanding. Then it handshakes again.
- An idle client sends nothing, so the server ends its session after the idle timeout without the
  client knowing. A client SHOULD NOT open a stream on a session that has had no streams and no
  authenticated answer for close to that long; it handshakes again instead. The reference client
  does so after 90 s. Otherwise the stream fails as soon as the unknown-session answer arrives.
  The same goes for a session that has never carried a stream, against the 15 s limit above: such
  a session is left behind when a dial is abandoned mid-handshake. The reference client retires
  one after 10 s.
- Receiving CLOSE, or any frame error, ends the session at once.

## 8. Streams

### 8.1 Opening

Only the client opens streams. It numbers them 0, 1, 2, … in the order it opens them, and never
reuses a number. On the server, a stream opens when its id first appears in a STREAM or
MAX_STREAM_DATA frame. The reference client sends MAX_STREAM_DATA as it opens a stream, ahead of
any data, so that's usually the frame that opens it. RESET_STREAM for an unknown id is ignored.
Since packets can arrive out of order, the server accepts a new id up to `max_streams` beyond the
highest it has seen, and remembers which lower ids were skipped so they can still open later.

- **Frames for other ids are ignored, without a reply:** ids at or above that window, and lower
  ids that aren't skipped ones (they belong to streams that already finished).
- **A new id within the window is refused with RESET_STREAM (code 0)** when the server is at its
  stream limit, either for the session or across all sessions.

The reference server allows 128 streams per session. Clients SHOULD stay within it.

### 8.2 Destination

The first bytes of the client's data on a stream name its destination: a 1-byte length (1–255),
then that many bytes of `host:port` in ASCII (an IPv6 host in brackets). Everything after is
application data (vectors: `stream_open`). The server dials the destination over TCP:

- If the dial succeeds, the server sends **OPENED** and relays bytes both ways.
- If the dial fails or the destination isn't allowed, the server sends **RESET_STREAM** with code 1.

The client may send application data before OPENED arrives.

### 8.3 Flow control

Each direction of each stream has a limit: the sender MUST NOT send data at or beyond the offset its
peer allows. Until the peer says otherwise, the limit is **32768 bytes**, in both directions. A
receiver raises its peer's limit with MAX_STREAM_DATA. The reference implementations send one as
soon as a stream opens: the client allows 256 KiB and the server 64 KiB. After that, each sends
another whenever half its window has been read. A receiver that gets data beyond the limit it
granted MUST end the session. There is no session-wide limit.

### 8.4 Finishing and resetting

- **FIN** ends one direction. It fixes the stream's final size. A receiver MUST end the session if
  data arrives beyond a known final size, if a second FIN gives a different final size, or if a FIN
  puts the final size below data already received.
- **RESET_STREAM** means the sender is done with the stream in both directions. The receiver stops
  sending on it and drops what it hasn't sent. If the receiver has already received all of the
  peer's data up to its FIN, that data stays readable. Otherwise the stream is aborted.
- A stream is gone once both directions are finished: everything sent has been acknowledged
  (including the FIN) and everything received has been read, or it was reset. Frames for an id
  that's gone are ignored.

## 9. The reference implementation's choices (non-normative)

None of this affects compatibility, but it's most of why Soar performs as it does. Read
`client.go` and `server.go` for the details.

- **Every resolver is a path,** with its own RTT estimate, loss record and congestion window. The
  window grows like TCP's and shrinks on *runs* of losses, so rate limits slow a path but random
  loss doesn't.
- **Name length** starts at 101 characters on every path, since some networks drop longer names.
  After 3 successes it probes 253 with a fully padded packet.
- **Answer size** starts at class 0 (512 bytes) and is trialled upward against a 512-byte baseline.
  A size is kept only if its loss isn't significantly worse than the baseline's.
- **The tail of a transfer is sent twice,** on different paths when there are any, so the last
  packet doesn't cost a timeout.
- **AAAA fallback:** a path whose TXT queries are REFUSED switches to AAAA.
- **TCP/53 fallback:** a resolver whose UDP path never answers gets a TCP path.
- **Hold:** the client asks for 500 ms. It keeps `2 + backlog` polls outstanding while it has
  streams open, and none when idle.
- **Server safety:** the server dials only public addresses on ports 80 and 443. It caps sessions
  and streams, rate-limits the bytes answered to each source /24 or /48 (amplification), and runs
  hellos on a bounded worker pool.

## 10. Test vectors

[`testdata/vectors.json`](testdata/vectors.json) is generated from the reference code by
`vectors_test.go`. Every vector is checked by decoding it back with the reference code, and CI fails
if the file is out of date. Byte strings are lower-case hex. The sections:

| key | what an implementation checks |
|---|---|
| `varint` | encode `value` → `encoded`, and decode it back |
| `base32` | encode `data` → `encoded`; decode it, also in upper case |
| `name_capacity` | `max_payload` for a zone and name limit (section 3.1) |
| `packet_number` | decode `truncated` given `largest` → `pn` (section 5.3) |
| `frames` | parse `encoded` → `frames`, and re-encode those frames to the same bytes (except as noted below) |
| `bad_frames` | parsing `encoded` fails |
| `stream_open` | the destination prefix for `address` |
| `handshake` | from the fixed keys: `shared_secret`, `transcript`, `prk`, the `up`/`down` keys, and the exact `hello_reply` (Ed25519 signatures are deterministic) |
| `packets` | seal `plaintext` → `payload` (with `nonce` and `mask` as intermediates), and open it back, decoding the packet number with `largest_received` (null: nothing received yet) |
| `bad_packets` | opening `payload` fails (with `largest_received` for packet-number decoding, and `up_pn` downstream) |
| `dns` | `kind` `query`: parse `message` to `payload`, and when `build` is true, build `message` from `txn`, `qtype`, `edns_size` and `payload`. `kind` `answer`: parse `message`, and when `build` is true, build it as the answer to `query`. `subkind` says which: `carrier` (rcode NOERROR, `payload` carried), `empty` (rcode only, no records), `unknown_session` (section 4.5, with the `signed_message`), or `no_data` (section 3.6). `kind` `capacity`: the payload capacity of an answer to `query` within `limit` |
| `exchange` | a whole session in DNS messages, using the `handshake` keys and sid: a hello (`hello_payload`, `hello_reply`), then steps with `up_pn`/`up_plaintext` and `down_pn`/`down_plaintext`. Packet-number decoding state carries over from step to step |

The `exchange` messages are built with fixed IDs and keys. An implementation checks it by parsing
each query and answer, and opening each packet with the `handshake` keys to get the listed
plaintexts. Which frames a server puts in an answer is its own choice, so `exchange` is not a script
a server must reproduce byte for byte. Use it to check a server's decoding, and use the interop
tests against the reference server to check its behaviour.

JSON conventions:

- ACK `ranges` are half-open `[lo, hi)` pairs listed in ascending order. On the wire they go
  highest first.
- STREAM `explicit_length` is the LEN bit.
- OPENED leaves out its reserved field, which is 0.

Two notes on the `frames` vectors:

- `stream without length runs to the end of the packet` can only be re-encoded by an encoder that
  omits LEN.
- In `padding runs to the end of the packet`, everything after the PADDING byte is ignored, even the
  nonzero last byte. Re-encoding the parsed frames gives only the leading `01`.

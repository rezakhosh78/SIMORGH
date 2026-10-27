#include "packets.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <strings.h>

#ifdef _WIN32
    #include <winsock2.h>
#else
    #include <arpa/inet.h>
#endif

#define ANTOHS(data, i) \
    (uint16_t)(((uint16_t)(uint8_t)(data)[i] << 8) | (uint8_t)(data)[i + 1])

#define SHTONA(data, i, x) do { \
    uint16_t shtona_value = (uint16_t)(x); \
    ((uint8_t *)(data))[i] = (uint8_t)(shtona_value >> 8); \
    ((uint8_t *)(data))[i + 1] = (uint8_t)(shtona_value & 0xff); \
} while (0)


char tls_data[TLS_DATA_CAPACITY] = {
    "\x16\x03\x01\x02\x00\x01\x00\x01\xfc\x03\x03\x03\x5f"
    "\x6f\x2c\xed\x13\x22\xf8\xdc\xb2\xf2\x60\x48\x2d\x72"
    "\x66\x6f\x57\xdd\x13\x9d\x1b\x37\xdc\xfa\x36\x2e\xba"
    "\xf9\x92\x99\x3a\x20\xf9\xdf\x0c\x2e\x8a\x55\x89\x82"
    "\x31\x63\x1a\xef\xa8\xbe\x08\x58\xa7\xa3\x5a\x18\xd3"
    "\x96\x5f\x04\x5c\xb4\x62\xaf\x89\xd7\x0f\x8b\x00\x3e"
    "\x13\x02\x13\x03\x13\x01\xc0\x2c\xc0\x30\x00\x9f\xcc"
    "\xa9\xcc\xa8\xcc\xaa\xc0\x2b\xc0\x2f\x00\x9e\xc0\x24"
    "\xc0\x28\x00\x6b\xc0\x23\xc0\x27\x00\x67\xc0\x0a\xc0"
    "\x14\x00\x39\xc0\x09\xc0\x13\x00\x33\x00\x9d\x00\x9c"
    "\x00\x3d\x00\x3c\x00\x35\x00\x2f\x00\xff\x01\x00\x01"
    "\x75\x00\x00\x00\x16\x00\x14\x00\x00\x11\x77\x77\x77"
    "\x2e\x77\x69\x6b\x69\x70\x65\x64\x69\x61\x2e\x6f\x72"
    "\x67\x00\x0b\x00\x04\x03\x00\x01\x02\x00\x0a\x00\x16"
    "\x00\x14\x00\x1d\x00\x17\x00\x1e\x00\x19\x00\x18\x01"
    "\x00\x01\x01\x01\x02\x01\x03\x01\x04\x00\x10\x00\x0e"
    "\x00\x0c\x02\x68\x32\x08\x68\x74\x74\x70\x2f\x31\x2e"
    "\x31\x00\x16\x00\x00\x00\x17\x00\x00\x00\x31\x00\x00"
    "\x00\x0d\x00\x2a\x00\x28\x04\x03\x05\x03\x06\x03\x08"
    "\x07\x08\x08\x08\x09\x08\x0a\x08\x0b\x08\x04\x08\x05"
    "\x08\x06\x04\x01\x05\x01\x06\x01\x03\x03\x03\x01\x03"
    "\x02\x04\x02\x05\x02\x06\x02\x00\x2b\x00\x09\x08\x03"
    "\x04\x03\x03\x03\x02\x03\x01\x00\x2d\x00\x02\x01\x01"
    "\x00\x33\x00\x26\x00\x24\x00\x1d\x00\x20\x11\x8c\xb8"
    "\x8c\xe8\x8a\x08\x90\x1e\xee\x19\xd9\xdd\xe8\xd4\x06"
    "\xb1\xd1\xe2\xab\xe0\x16\x63\xd6\xdc\xda\x84\xa4\xb8"
    "\x4b\xfb\x0e\x00\x15\x00\xac\x00\x00\x00\x00\x00\x00"
};

char http_data[43] = {
    "GET / HTTP/1.1\r\n"
    "Host: www.wikipedia.org\r\n\r\n"
};

char udp_data[64] = { 0 };


char *strncasestr(char *a, size_t as, char *b, size_t bs)
{
    for (char *p = a; ; p++) {
        p = memchr(p, *b, as - (p - a));
        if (!p) {
            return 0;
        }
        if ((p + bs) > (a + as)) {
            return 0;
        }
        if (!strncasecmp(p, b, bs)) {
            return p;
        }
    }
    return 0;
}


size_t find_tls_ext_offset(uint16_t type, 
        char *data, size_t size, size_t skip) 
{
    if (size <= (skip + 2)) {
        return 0;
    }
    uint16_t ext_len = ANTOHS(data, skip);
    skip += 2;

    if (ext_len > size - skip) {
        return 0;
    }
    size = ext_len + skip;
    while ((skip + 4) <= size) {
        uint16_t epyt = ANTOHS(data, skip);
        if (type == epyt) {
            return skip;
        }
        uint16_t len = ANTOHS(data, skip + 2);
        if (len > size - skip - 4) {
            return 0;
        }
        skip += (len + 4);
    }
    return 0;
}


size_t chello_ext_offset(uint16_t type, char *data, size_t size)
{
    if (size < 44) {
        return 0;
    }
    uint8_t sid_len = data[43];
    if (size < (size_t)44 + sid_len + 2) {
        return 0;
    }
    uint16_t cip_len = ANTOHS(data, 44 + sid_len);

    size_t skip = 44 + sid_len + 2 + cip_len + 2;
    return find_tls_ext_offset(type, data, size, skip);
}


static size_t chello_extensions_length_offset(const char *buffer, size_t size)
{
    if (size < 45) {
        return 0;
    }

    size_t sid_length_offset = 43;
    size_t cipher_length_offset = 44 + (uint8_t)buffer[sid_length_offset];
    if (cipher_length_offset + 2 > size) {
        return 0;
    }

    uint16_t cipher_length = ANTOHS(buffer, cipher_length_offset);
    size_t compression_length_offset = cipher_length_offset + 2 + cipher_length;
    if (compression_length_offset >= size) {
        return 0;
    }

    size_t extensions_length_offset = compression_length_offset + 1 +
        (uint8_t)buffer[compression_length_offset];
    if (extensions_length_offset + 2 > size) {
        return 0;
    }
    return extensions_length_offset;
}

int change_tls_sni(const char *host, char *buffer, size_t capacity, size_t *packet_size)
{
    if (!host || !buffer || !packet_size) {
        return -1;
    }

    size_t bsize = *packet_size;
    size_t new_sz = strlen(host);
    if (new_sz == 0 || new_sz > TLS_MAX_SNI_LENGTH || bsize > capacity || bsize < 9 ||
            (uint8_t)buffer[0] != 0x16 || (uint8_t)buffer[5] != 0x01 ||
            (size_t)ANTOHS(buffer, 3) + 5 != bsize) {
        return -1;
    }

    size_t handshake_size = ((size_t)(uint8_t)buffer[6] << 16) |
        ((size_t)(uint8_t)buffer[7] << 8) | (uint8_t)buffer[8];
    if (handshake_size + 9 != bsize) {
        return -1;
    }

    size_t sni_offs = chello_ext_offset(0x00, buffer, bsize);
    size_t pad_offs = chello_ext_offset(0x15, buffer, bsize);
    size_t extensions_length_offset =
        chello_extensions_length_offset(buffer, bsize);
    if (!sni_offs || !pad_offs || !extensions_length_offset ||
            sni_offs + 9 > bsize || pad_offs + 4 > bsize) {
        return -1;
    }

    size_t sni_extension_length = ANTOHS(buffer, sni_offs + 2);
    if (sni_extension_length < 5 || sni_offs + 4 + sni_extension_length > bsize) {
        return -1;
    }
    size_t old_sz = sni_extension_length - 5;
    if (old_sz == 0 || ANTOHS(buffer, sni_offs + 4) != old_sz + 3 ||
            (uint8_t)buffer[sni_offs + 6] != 0 ||
            ANTOHS(buffer, sni_offs + 7) != old_sz) {
        return -1;
    }

    size_t free_sz = ANTOHS(buffer, pad_offs + 2);
    if (pad_offs <= sni_offs || pad_offs + 4 + free_sz != bsize) {
        return -1;
    }

    ssize_t diff = (ssize_t)new_sz - (ssize_t)old_sz;
    size_t host_end_offset = sni_offs + 9 + old_sz;
    if (pad_offs < host_end_offset) {
        return -1;
    }

    size_t new_padding_length;
    size_t new_packet_size = bsize;
    if ((size_t)(diff > 0 ? diff : 0) > free_sz) {
        new_padding_length = 0;
        new_packet_size += (size_t)diff - free_sz;
    } else {
        new_padding_length = (size_t)((ssize_t)free_sz - diff);
    }
    if (new_packet_size > capacity || new_padding_length > UINT16_MAX) {
        return -1;
    }

    size_t new_sni_extension_length = (size_t)((ssize_t)sni_extension_length + diff);
    size_t new_sni_list_length = (size_t)((ssize_t)(old_sz + 3) + diff);
    if (new_sni_extension_length > UINT16_MAX || new_sni_list_length > UINT16_MAX) {
        return -1;
    }

    /* The padding extension is last, so its existing padding absorbs SNI
     * growth first. If it runs out, the output grows by the remainder. */
    uint16_t tls_record_length = ANTOHS(buffer, 3);
    uint16_t extensions_length = ANTOHS(buffer, extensions_length_offset);
    ssize_t net_growth = (ssize_t)new_packet_size - (ssize_t)bsize;
    if (net_growth > 0 &&
            ((size_t)tls_record_length + (size_t)net_growth > UINT16_MAX ||
             (size_t)extensions_length + (size_t)net_growth > UINT16_MAX ||
             handshake_size + (size_t)net_growth > 0xFFFFFFU)) {
        return -1;
    }

    size_t new_host_end_offset = sni_offs + 9 + new_sz;
    size_t middle_size = pad_offs - host_end_offset;
    size_t new_pad_offs = new_host_end_offset + middle_size;
    if (new_pad_offs + 4 + new_padding_length != new_packet_size) {
        return -1;
    }

    /* Move only the extensions between SNI and padding. The padding is the
     * final extension, so rebuild it in place instead of shifting its old
     * contents through a potentially larger temporary buffer. */
    memmove(buffer + new_host_end_offset, buffer + host_end_offset, middle_size);
    memmove(buffer + sni_offs + 9, host, new_sz);

    SHTONA(buffer, new_pad_offs, 0x15);
    SHTONA(buffer, sni_offs + 2, new_sni_extension_length);
    SHTONA(buffer, sni_offs + 4, new_sni_list_length);
    SHTONA(buffer, sni_offs + 7, new_sz);
    SHTONA(buffer, new_pad_offs + 2, new_padding_length);
    memset(buffer + new_pad_offs + 4, 0, new_padding_length);
    if (net_growth > 0) {
        SHTONA(buffer, 3, tls_record_length + net_growth);
        SHTONA(buffer, extensions_length_offset,
            extensions_length + net_growth);
        handshake_size += (size_t)net_growth;
        buffer[6] = (char)(handshake_size >> 16);
        buffer[7] = (char)(handshake_size >> 8);
        buffer[8] = (char)handshake_size;
    }

    *packet_size = new_packet_size;
    return 0;
}

int randomize_tls_padding(char *buffer, size_t bsize)
{
    size_t pad_offs = chello_ext_offset(0x15, buffer, bsize);
    if (!pad_offs || pad_offs + 4 > bsize) {
        return -1;
    }

    uint16_t padding_len = ANTOHS(buffer, pad_offs + 2);
    if (pad_offs + 4 + padding_len > bsize) {
        return -1;
    }

    for (uint16_t i = 0; i < padding_len; i++) {
        buffer[pad_offs + 4 + i] = (char)(rand() & 0xff);
    }
    return 0;
}


bool is_tls_chello(char *buffer, size_t bsize)
{
    return (bsize > 5 &&
        ANTOHS(buffer, 0) == 0x1603 &&
        buffer[5] == 0x01);
}


int parse_tls(char *buffer, size_t bsize, char **hs)
{
    if (!is_tls_chello(buffer, bsize)) {
        return 0;
    }
    size_t sni_offs = chello_ext_offset(0x00, buffer, bsize);
    
    if (!sni_offs || (sni_offs + 12) >= bsize) {
        return 0;
    }
    uint16_t len = ANTOHS(buffer, sni_offs + 7);
    
    if ((sni_offs + 9 + len) > bsize) {
        return 0;
    }
    *hs = &buffer[sni_offs + 9];
    return len;
}


bool is_http(char *buffer, size_t bsize)
{
    if (bsize < 16 || *buffer > 'T' || *buffer < 'C') {
        return 0;
    }
    const char *methods[] = {
        "HEAD", "GET", "POST", "PUT", "DELETE",
        "OPTIONS", "CONNECT", "TRACE", "PATCH", 0
    };
    for (const char **m = methods; *m; m++) {
        if (strncmp(buffer, *m, strlen(*m)) == 0) {
            return 1;
        }
    }
    return 0;
}

    
int parse_http(char *buffer, size_t bsize, char **hs, uint16_t *port)
{
    char *host = buffer, *h_end;
    char *buff_end = buffer + bsize;
    
    if (!is_http(buffer, bsize)) {
        return 0;
    }
    host = strncasestr(buffer, bsize, "\nHost:", 6);
    if (!host) {
        return 0;
    }
    host += 6;
    
    while ((buff_end - host) > 0 && isblank((unsigned char) *host)) {
        host++;
    }
    char *l_end = memchr(host, '\n', buff_end - host);
    if (!l_end) {
        return 0;
    }
    for (; isspace((unsigned char) *(l_end - 1)); l_end--) {}
    
    if (!(isdigit((unsigned char) *(l_end - 1))))
        h_end = 0;
    else {
        char *h = host;
        h_end = 0;
        do {
            h = memchr(h, ':', l_end - h);
            if (h) {
                h_end = h;
                h++;
            }
        } while (h && h < l_end);
    }
    
    if (!h_end) {
        if (port) *port = 80;
        h_end = l_end;
    }
    else if (port) {
        char *end;
        long i = strtol(h_end + 1, &end, 10);
        if (i <= 0 || end != l_end || i > 0xffff)
            return 0;
        *port = i;
    }
    *hs = host;
    return h_end - host;
}


int get_http_code(char *b, size_t n)
{
    if (n < 13) return 0;
    if (strncmp(b, "HTTP/1.", 7)) {
        return 0;
    }
    if (!memchr(b + 13, '\n', n)) {
        return 0;
    }
    char *e;
    long num = strtol(b + 9, &e, 10);
    if (num < 100 || num > 511 || !isspace((unsigned char) *e)) {
        return 0;
    }
    return (int )num;
}


bool is_http_redirect(char *req, size_t qn, char *resp, size_t sn)
{
    char *host = 0;
    int len = parse_http(req, qn, &host, 0);
    
    if (len <= 0 || sn < 29) {
        return 0;
    }
    int code = get_http_code(resp, sn);
    if (code > 308 || code < 300) {
        return 0;
    }
    char *location = strncasestr(resp, sn, "\nLocation:", 10);
    if (!location) {
        return 0;
    }
    location += 11;
    
    if ((location + 8) >= (resp + sn)) {
        return 0;
    }
    char *l_end = memchr(location, '\n', sn - (location - resp));
    if (!l_end) {
        return 0;
    }
    for (; isspace((unsigned char) *(l_end - 1)); l_end--) {}
    
    if ((l_end - location) > 7) {
        if (!strncmp(location, "http://", 7)) {
            location += 7;
        }
        else if (!strncmp(location, "https://", 8)) {
            location += 8;
        }
    }
    char *e = memchr(location, '/', l_end - location);
    if (!e) e = l_end;
    
    for (; (e - location) > len; location++) {
        location = memchr(location, '.', e - location);
        if (!location) {
            return 1;
        }
    }
    for (; len > (e - location); host++) {
        char *p = memchr(host, '.', len);
        if (!p) {
            return 1;
        }
        len -= (host - p) + 1;
        host = p;
    }
    return (((e - location) != len) 
        || strncmp(host, location, len));
}


bool neq_tls_sid(char *req, size_t qn, char *resp, size_t sn)
{
    if (qn < 75 || sn < 75) {
        return 0;
    }
    if (!is_tls_chello(req, qn)
            || ANTOHS(resp, 0) != 0x1603) {
        return 0;
    }
    uint8_t sid_len = req[43];
    size_t skip = 44 + sid_len + 3;
    
    if (!find_tls_ext_offset(0x2b, resp, sn, skip)) {
        return 0;
    }
    if (req[43] != resp[43]) {
        return 1;
    }
    return memcmp(req + 44, resp + 44, sid_len);
}


bool is_tls_shello(char *buffer, size_t bsize)
{
    return (bsize > 5 &&
        ANTOHS(buffer, 0) == 0x1603 &&
        buffer[5] == 0x02);
}

/*
bool is_dns_req(char *buffer, size_t n)
{
    if (n < 12) {
        return 0;
    }
    return !memcmp(buffer + 2, "\1\0\0\1\0\0\0\0\0\0", 10);
}


bool is_quic_initial(char *buffer, size_t bsize)
{
    return (bsize > 64 && (buffer[0] & 0xc0) == 0xc0);
}
*/

int mod_http(char *buffer, size_t bsize, int m)
{
    char *host = 0, *par;
    int hlen = parse_http(buffer, bsize, &host, 0);
    if (!hlen)
        return -1;
    for (par = host - 1; *par != ':'; par--) {}
    par -= 4;
    if (m & MH_HMIX) {
        par[0] = tolower((unsigned char) par[0]);
        par[1] = toupper((unsigned char) par[1]);
        par[3] = toupper((unsigned char) par[3]);
    }
    if (m & MH_DMIX) {
        for (int i = 0; i < hlen; i += 2) {
            host[i] = toupper((unsigned char)host[i]);
        }
    }
    if (m & MH_SPACE) {
        for (; !isspace((unsigned char) *(host + hlen)); hlen++) {}
        int sc = host - (par + 5);
        memmove(par + 5, host, hlen);
        memset(par + 5 + hlen, '\t', sc);
    }
    return 0;
}


int part_tls(char *buffer, size_t bsize, ssize_t n, long pos)
{
    if ((n < 3) || (bsize - n < 5) || 
            (pos < 0) || (pos + 5 > n)) {
        return 0;
    }
    uint16_t r_sz = ANTOHS(buffer, 3);
    if (r_sz < pos) {
        return n;
    }
    memmove(buffer + 5 + pos + 5, buffer + 5 + pos, n - (5 + pos));
    memcpy(buffer + 5 + pos, buffer, 3);
    
    SHTONA(buffer, 3, pos);
    SHTONA(buffer, 5 + pos + 3, r_sz - pos);
    return 5;
}

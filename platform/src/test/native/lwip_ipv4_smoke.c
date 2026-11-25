// SPDX-License-Identifier: GPL-3.0-only
// Packet-only smoke test: no real socket, VPN, USB device or external host.
#include <assert.h>
#include <stdio.h>
#include <string.h>

#include "ipv4_tunnel.h"
#include "lwip/tcp.h"
#include "lwip/udp.h"

static const uint8_t car[] = {192, 168, 42, 1};
static const uint8_t phone[] = {192, 168, 42, 2};
static const uint8_t offlink[] = {192, 168, 43, 2};

static void set16(uint8_t *out, unsigned n) { out[0] = (uint8_t)(n >> 8); out[1] = (uint8_t)n; }
static unsigned get16(const uint8_t *in) { return ((unsigned)in[0] << 8) | in[1]; }

static unsigned checksum(const uint8_t *data, size_t length) {
    unsigned value = 0;
    while (length >= 2) {
        value += get16(data);
        data += 2;
        length -= 2;
    }
    if (length) value += (unsigned)*data << 8;
    while (value > 0xffff) value = (value & 0xffff) + (value >> 16);
    return ~value & 0xffff;
}

static void ip_header(uint8_t *out, unsigned length, uint8_t protocol, const uint8_t *from) {
    memset(out, 0, length);
    out[0] = 0x45;
    set16(out + 2, length);
    out[8] = 64;
    out[9] = protocol;
    memcpy(out + 12, from, 4);
    memcpy(out + 16, car, 4);
    set16(out + 10, checksum(out, 20));
}

static void echo_request(uint8_t *p, const uint8_t *from) {
    ip_header(p, 32, 1, from);
    p[20] = 8;
    memcpy(p + 28, "test", 4);
    set16(p + 22, checksum(p + 20, 12));
}

static void require_reply(const uint8_t *p, uint8_t protocol) {
    assert(p[0] == 0x45 && p[9] == protocol);
    assert(memcmp(p + 12, car, 4) == 0 && memcmp(p + 16, phone, 4) == 0);
    assert(checksum(p, 20) == 0);
}

static void udp_echo(void *arg, struct udp_pcb *pcb, struct pbuf *p, const ip_addr_t *from, u16_t port) {
    (void)arg;
    assert(udp_sendto(pcb, p, from, port) == ERR_OK);
    pbuf_free(p);
}

static void tcp_segment(uint8_t *p, uint8_t flags, unsigned seq) {
    uint8_t pseudo[32] = {0};
    ip_header(p, 40, 6, phone);
    set16(p + 20, 17001);
    set16(p + 22, 17002);
    set16(p + 26, seq);
    p[32] = 0x50;
    p[33] = flags;
    set16(p + 34, 4096);
    memcpy(pseudo, p + 12, 8);
    pseudo[9] = 6;
    set16(pseudo + 10, 20);
    memcpy(pseudo + 12, p + 20, 20);
    set16(p + 36, checksum(pseudo, sizeof(pseudo)));
}

int main(void) {
    uint8_t frame[OPENHC_LWIP_MTU], out[OPENHC_LWIP_MTU];
    assert(openhc_lwip_ipv4_open(car, 32) == 0);
    uint64_t token = openhc_lwip_ipv4_open(car, 24);
    assert(token != 0 && openhc_lwip_ipv4_open(car, 24) == 0);
    assert(openhc_lwip_ipv4_read(token, out, sizeof(out)) == 0);

    echo_request(frame, offlink);
    assert(!openhc_lwip_ipv4_input(token, frame, 32));
    echo_request(frame, phone);
    assert(!openhc_lwip_ipv4_input(token, frame, 31));
    for (unsigned i = 0; i < OPENHC_LWIP_OUTBOUND_SLOTS + 1; ++i) {
        assert(openhc_lwip_ipv4_input(token, frame, 32));
    }
    for (unsigned i = 0; i < OPENHC_LWIP_OUTBOUND_SLOTS; ++i) {
        assert(openhc_lwip_ipv4_read(token, out, sizeof(out)) == 32);
        require_reply(out, 1);
        assert(out[20] == 0 && checksum(out + 20, 12) == 0 && memcmp(out + 28, "test", 4) == 0);
    }
    assert(openhc_lwip_ipv4_read(token, out, sizeof(out)) == 0);
    assert(openhc_lwip_ipv4_input(token, frame, 32));
    assert(openhc_lwip_ipv4_read(token, out, 20) == 0);  // Do not truncate an oversized packet.

    struct udp_pcb *udp = udp_new();
    assert(udp != NULL && udp_bind(udp, IP_ADDR_ANY, 17004) == ERR_OK);
    udp_recv(udp, udp_echo, NULL);
    ip_header(frame, 32, 17, phone);
    set16(frame + 20, 17003);
    set16(frame + 22, 17004);
    set16(frame + 24, 12);
    memcpy(frame + 28, "test", 4);
    assert(openhc_lwip_ipv4_input(token, frame, 32));
    assert(openhc_lwip_ipv4_read(token, out, sizeof(out)) == 32);
    require_reply(out, 17);
    assert(get16(out + 20) == 17004 && get16(out + 22) == 17003 && memcmp(out + 28, "test", 4) == 0);
    udp_remove(udp);

    struct tcp_pcb *tcp = tcp_new();
    assert(tcp != NULL && tcp_bind(tcp, IP_ADDR_ANY, 17002) == ERR_OK);
    tcp = tcp_listen(tcp);
    assert(tcp != NULL);
    tcp_segment(frame, 0x02, 105);
    assert(openhc_lwip_ipv4_input(token, frame, 40));
    assert(openhc_lwip_ipv4_read(token, out, sizeof(out)) >= 40);
    require_reply(out, 6);
    assert(get16(out + 20) == 17002 && get16(out + 22) == 17001);
    assert((out[33] & 0x12) == 0x12 && get16(out + 30) == 106);
    tcp_segment(frame, 0x04, 106);
    assert(openhc_lwip_ipv4_input(token, frame, 40));
    assert(tcp_close(tcp) == ERR_OK);

    openhc_lwip_ipv4_close(token);
    assert(openhc_lwip_ipv4_read(token, out, sizeof(out)) < 0);
    uint64_t next = openhc_lwip_ipv4_open(car, 24);
    assert(next != 0 && next != token && openhc_lwip_ipv4_read(next, out, sizeof(out)) == 0);
    openhc_lwip_ipv4_close(token);  // A stale session must not close the new one.
    echo_request(frame, phone);
    assert(!openhc_lwip_ipv4_input(token, frame, 32));
    assert(openhc_lwip_ipv4_input(next, frame, 32));
    assert(openhc_lwip_ipv4_read(next, out, sizeof(out)) == 32);
    openhc_lwip_ipv4_close(next);
    puts("lwIP IPv4: network isolation, ICMP, UDP, TCP SYN-ACK, bounded queue and reopen OK");
    return 0;
}

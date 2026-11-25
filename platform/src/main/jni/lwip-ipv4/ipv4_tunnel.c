// SPDX-License-Identifier: GPL-3.0-only
#include "ipv4_tunnel.h"

#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "lwip/init.h"
#include "lwip/ip4.h"
#include "lwip/netif.h"
#include "lwip/pbuf.h"
#include "lwip/timeouts.h"

struct output_packet {
    uint16_t length;
    uint8_t data[OPENHC_LWIP_MTU];
};

// One isolated in-process interface. No Android network configuration is changed.
static struct {
    struct netif iface;
    uint32_t address;
    uint32_t mask;
    uint64_t token;
    unsigned front;
    unsigned count;
    struct output_packet queue[OPENHC_LWIP_OUTBOUND_SLOTS];
} stack;
static uint64_t generation;
static int initialized;

u32_t sys_now(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return (u32_t)((uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000);
}

uint32_t openhc_lwip_random(void) {
    uint32_t result = 0;
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd >= 0) {
        size_t done = 0;
        while (done < sizeof(result)) {
            ssize_t n = read(fd, (uint8_t *)&result + done, sizeof(result) - done);
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) break;
            done += (size_t)n;
        }
        close(fd);
        if (done == sizeof(result)) return result;
    }
    // Used for transport randomness only; never used for HiCar credentials or session crypto.
    static uint32_t fallback = UINT32_C(0x6d2b79f5);
    fallback ^= sys_now();
    if (!fallback) fallback = UINT32_C(0x6d2b79f5);
    fallback ^= fallback << 13;
    fallback ^= fallback >> 17;
    fallback ^= fallback << 5;
    return fallback;
}

static uint32_t ipv4_number(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}

static int unicast(uint32_t address, uint32_t mask) {
    uint32_t host = address & ~mask;
    unsigned first = address >> 24;
    return first > 0 && first < 224 && first != 127 && host != 0 && host != ~mask;
}

static err_t queue_output(struct netif *iface, struct pbuf *p, const ip4_addr_t *to) {
    if (!stack.token || iface != &stack.iface || !ip4_addr_netcmp(to, netif_ip4_addr(iface), netif_ip4_netmask(iface))) {
        return ERR_RTE;
    }
    if (p->tot_len < 20 || p->tot_len > OPENHC_LWIP_MTU) return ERR_BUF;
    if (stack.count == OPENHC_LWIP_OUTBOUND_SLOTS) return ERR_MEM;
    struct output_packet *item = &stack.queue[(stack.front + stack.count) % OPENHC_LWIP_OUTBOUND_SLOTS];
    if (pbuf_copy_partial(p, item->data, p->tot_len, 0) != p->tot_len) return ERR_BUF;
    item->length = p->tot_len;
    stack.count++;
    return ERR_OK;
}

static err_t setup_interface(struct netif *iface) {
    iface->name[0] = 'h';
    iface->name[1] = 'c';
    iface->mtu = OPENHC_LWIP_MTU;
    iface->output = queue_output;
    return ERR_OK;
}

uint64_t openhc_lwip_ipv4_open(const uint8_t address[4], unsigned prefix) {
    if (!address || prefix < 1 || prefix > 30 || stack.token) return 0;
    uint32_t mask = UINT32_MAX << (32 - prefix);
    uint32_t number = ipv4_number(address);
    if (!unicast(number, mask)) return 0;
    if (!initialized) {
        lwip_init();
        initialized = 1;
    }
    ip4_addr_t ip, subnet, gateway;
    IP4_ADDR(&ip, address[0], address[1], address[2], address[3]);
    ip4_addr_set_u32(&subnet, lwip_htonl(mask));
    ip4_addr_set_zero(&gateway);
    memset(&stack.iface, 0, sizeof(stack.iface));
    if (netif_add(&stack.iface, &ip, &subnet, &gateway, NULL, setup_interface, ip4_input) == NULL) return 0;
    stack.address = number;
    stack.mask = mask;
    stack.front = 0;
    stack.count = 0;
    if (++generation == 0) ++generation;
    stack.token = generation;
    netif_set_up(&stack.iface);
    netif_set_link_up(&stack.iface);
    // No default route, and queue_output also rejects traffic outside this USB subnet.
    return stack.token;
}

int openhc_lwip_ipv4_input(uint64_t token, const uint8_t *bytes, size_t length) {
    if (!token || token != stack.token || !bytes || length < 20 || length > OPENHC_LWIP_MTU) return 0;
    size_t header = (size_t)(bytes[0] & 0xf) * 4;
    size_t total = ((size_t)bytes[2] << 8) | bytes[3];
    uint32_t from = ipv4_number(bytes + 12);
    // NCM's Ethernet endpoint already handles ARP. This interface accepts only IPv4 unicast for itself.
    if ((bytes[0] >> 4) != 4 || header < 20 || header > total || total != length ||
        ipv4_number(bytes + 16) != stack.address || from == stack.address ||
        !unicast(from, stack.mask) || (from & stack.mask) != (stack.address & stack.mask)) return 0;
    sys_check_timeouts();
    struct pbuf *p = pbuf_alloc(PBUF_RAW, (u16_t)length, PBUF_RAM);
    if (p == NULL) return 0;
    if (pbuf_take(p, bytes, (u16_t)length) != ERR_OK) {
        pbuf_free(p);
        return 0;
    }
    // ip4_input owns the pbuf once called; packets rejected by lwIP are freed there.
    return stack.iface.input(p, &stack.iface) == ERR_OK;
}

int openhc_lwip_ipv4_read(uint64_t token, uint8_t *out, size_t capacity) {
    if (!token || token != stack.token || !out || !capacity) return -1;
    sys_check_timeouts();
    if (!stack.count) return 0;
    struct output_packet *item = &stack.queue[stack.front];
    stack.front = (stack.front + 1) % OPENHC_LWIP_OUTBOUND_SLOTS;
    stack.count--;
    if (item->length > capacity) return 0;  // Don't deliver a truncated IP packet.
    memcpy(out, item->data, item->length);
    return item->length;
}

void openhc_lwip_ipv4_close(uint64_t token) {
    if (!token || token != stack.token) return;
    netif_set_link_down(&stack.iface);
    netif_set_down(&stack.iface);
    netif_remove(&stack.iface);
    stack.token = 0;
    stack.count = 0;
}

// SPDX-License-Identifier: GPL-3.0-only
#ifndef OPENHC_LWIP_IPV4_TUNNEL_H
#define OPENHC_LWIP_IPV4_TUNNEL_H

#include <stddef.h>
#include <stdint.h>

#define OPENHC_LWIP_MTU 1500
#define OPENHC_LWIP_OUTBOUND_SLOTS 64

/* The raw lwIP API requires serialized calls. No callback leaves this layer.
 * Tokens, not pointers, identify the one currently open stack. */
uint64_t openhc_lwip_ipv4_open(const uint8_t address[4], unsigned prefix);
int openhc_lwip_ipv4_input(uint64_t token, const uint8_t *bytes, size_t length);
int openhc_lwip_ipv4_read(uint64_t token, uint8_t *out, size_t capacity);
void openhc_lwip_ipv4_close(uint64_t token);

#endif

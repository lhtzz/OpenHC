// SPDX-License-Identifier: GPL-3.0-only
#ifndef OPENHC_LWIPOPTS_H
#define OPENHC_LWIPOPTS_H

// Only one serialized caller; no Android routing, device files, pthread port or lwIP sockets.
#define NO_SYS                       1
#define SYS_LIGHTWEIGHT_PROT          0
#define LWIP_NETCONN                  0
#define LWIP_SOCKET                   0
#define LWIP_IPV4                     1
#define LWIP_IPV6                     0
#define LWIP_ETHERNET                 0
#define LWIP_ARP                      0
#define LWIP_ICMP                     1
#define LWIP_RAW                      0
#define LWIP_TCP                      1
#define LWIP_UDP                      1
#define LWIP_DHCP                     0
#define LWIP_DNS                      0
#define LWIP_AUTOIP                   0
#define LWIP_IGMP                     0
#define LWIP_NETIF_LOOPBACK           0
#define IP_FORWARD                    0
#define IP_REASSEMBLY                 0
#define IP_FRAG                       0
#define LWIP_STATS                    0
#define MEM_ALIGNMENT                 8
#define MEM_SIZE                     (128 * 1024)
#define PBUF_POOL_SIZE               32
#define TCP_MSS                    1460
#define TCP_WND                    (8 * TCP_MSS)
#define TCP_SND_BUF                (8 * TCP_MSS)
#define MEMP_NUM_TCP_SEG             48

#endif

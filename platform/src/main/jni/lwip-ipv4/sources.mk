# lwIP 2.2.1, upstream commit 77dcd25a72509eb83f72b033d219b1d40cd8eb95.
# Explicit file list shared with the host smoke test.
OPENHC_LWIP_SOURCES := \
    src/core/def.c src/core/inet_chksum.c src/core/init.c src/core/ip.c \
    src/core/mem.c src/core/memp.c src/core/netif.c src/core/pbuf.c \
    src/core/raw.c src/core/stats.c src/core/sys.c src/core/tcp.c \
    src/core/tcp_in.c src/core/tcp_out.c src/core/timeouts.c src/core/udp.c \
    src/core/ipv4/icmp.c src/core/ipv4/ip4.c src/core/ipv4/ip4_addr.c src/core/ipv4/ip4_frag.c

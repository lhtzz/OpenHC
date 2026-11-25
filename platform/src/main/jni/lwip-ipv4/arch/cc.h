// SPDX-License-Identifier: GPL-3.0-only
#ifndef OPENHC_LWIP_ARCH_CC_H
#define OPENHC_LWIP_ARCH_CC_H

#include <stdint.h>

uint32_t openhc_lwip_random(void);
#define LWIP_RAND() openhc_lwip_random()

#endif

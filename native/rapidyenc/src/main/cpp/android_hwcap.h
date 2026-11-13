// Put in front of rapidyenc's platform.cc and crc.cc on Android ARM (see CMakeLists.txt), so their
// AT_HWCAP/AT_HWCAP2 branches compile and they ask the kernel what the processor has.
#pragma once
#include <sys/auxv.h>
#include <asm/hwcap.h>

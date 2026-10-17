/*
 * socket()/connect() wrappers for the -Wl,--wrap link of this library; see
 * sip_socket_guard.h. The linker provides __real_socket/__real_connect (the
 * libc functions) and sends this library's calls to the __wrap_ versions.
 */
#include "sip_socket_guard.h"

#include <errno.h>
#include <sys/socket.h>

#include <mutex>
#include <unordered_set>

#if defined(__ANDROID__)
#include <android/log.h>
#define GUARD_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "HamelinPortsIms-JNI", __VA_ARGS__)
#else
#include <stdio.h>
#define GUARD_LOGE(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif

extern "C" int __real_socket(int domain, int type, int protocol);
extern "C" int __real_connect(int fd, const struct sockaddr* addr, socklen_t len);

namespace hamelinports {
namespace {

std::mutex gPinnedMu;
std::unordered_set<int> gPinned;

void forgetSocket(int fd) {
    std::lock_guard<std::mutex> g(gPinnedMu);
    gPinned.erase(fd);
}

}  // namespace

void markSocketPinned(int fd) {
    std::lock_guard<std::mutex> g(gPinnedMu);
    gPinned.insert(fd);
}

bool isSocketPinned(int fd) {
    std::lock_guard<std::mutex> g(gPinnedMu);
    return gPinned.count(fd) != 0;
}

}  // namespace hamelinports

/* A new socket starts unpinned, whatever an earlier socket with the same
 * number was. Hidden: only this library's own calls are wrapped. */
extern "C" __attribute__((visibility("hidden")))
int __wrap_socket(int domain, int type, int protocol) {
    const int fd = __real_socket(domain, type, protocol);
    if (fd >= 0) hamelinports::forgetSocket(fd);
    return fd;
}

/* AF_UNSPEC (dissolving a UDP association) and non-IP sockets pass. */
extern "C" __attribute__((visibility("hidden")))
int __wrap_connect(int fd, const struct sockaddr* addr, socklen_t len) {
    const bool ipTarget = addr != nullptr
            && (addr->sa_family == AF_INET || addr->sa_family == AF_INET6);
    if (ipTarget && !hamelinports::isSocketPinned(fd)) {
        GUARD_LOGE("SIP socket fd=%d connect refused: not pinned to an IMS network", fd);
        errno = ENETUNREACH;
        return -1;
    }
    return __real_connect(fd, addr, len);
}

/*
 * socket()/connect()/sendto()/sendmsg() wrappers for the -Wl,--wrap link of
 * this library; see sip_socket_guard.h. The linker provides the __real_
 * symbols (the libc functions; no header declares them) and sends this
 * library's calls to the __wrap_ versions.
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
extern "C" ssize_t __real_sendto(int fd, const void* buf, size_t len, int flags,
                                 const struct sockaddr* addr, socklen_t addrLen);
extern "C" ssize_t __real_sendmsg(int fd, const struct msghdr* msg, int flags);

namespace hamelinports {
namespace {

std::mutex gPinnedMu;
std::unordered_set<int> gPinned;

void forgetSocket(int fd) {
    std::lock_guard<std::mutex> g(gPinnedMu);
    gPinned.erase(fd);
}

/*
 * True (errno set, logged) if fd may not reach the IP address addr: it was
 * not pinned. No address (a connected socket's send), AF_UNSPEC (dissolving
 * a UDP association) and non-IP addresses pass.
 */
bool refuseUnpinned(int fd, const struct sockaddr* addr, const char* call) {
    const bool ipTarget = addr != nullptr
            && (addr->sa_family == AF_INET || addr->sa_family == AF_INET6);
    if (!ipTarget || isSocketPinned(fd)) return false;
    GUARD_LOGE("SIP socket fd=%d %s refused: not pinned to an IMS network", fd, call);
    errno = ENETUNREACH;
    return true;
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

/* Hidden: only this library's own calls are wrapped. */
#define GUARD_WRAPPER extern "C" __attribute__((visibility("hidden")))

/* A new socket starts unpinned, whatever an earlier socket with the same
 * number was. */
GUARD_WRAPPER int __wrap_socket(int domain, int type, int protocol) {
    const int fd = __real_socket(domain, type, protocol);
    if (fd >= 0) hamelinports::forgetSocket(fd);
    return fd;
}

GUARD_WRAPPER int __wrap_connect(int fd, const struct sockaddr* addr, socklen_t len) {
    if (hamelinports::refuseUnpinned(fd, addr, "connect")) return -1;
    return __real_connect(fd, addr, len);
}

/* An unconnected UDP socket sends to the address it is given: same rule. */
GUARD_WRAPPER ssize_t __wrap_sendto(int fd, const void* buf, size_t len, int flags,
                                    const struct sockaddr* addr, socklen_t addrLen) {
    if (hamelinports::refuseUnpinned(fd, addr, "sendto")) return -1;
    return __real_sendto(fd, buf, len, flags, addr, addrLen);
}

GUARD_WRAPPER ssize_t __wrap_sendmsg(int fd, const struct msghdr* msg, int flags) {
    const struct sockaddr* addr = msg != nullptr
            ? static_cast<const struct sockaddr*>(msg->msg_name) : nullptr;
    if (hamelinports::refuseUnpinned(fd, addr, "sendmsg")) return -1;
    return __real_sendmsg(fd, msg, flags);
}

/*
 * Host test of jni/sip_socket_guard.cpp, linked with -Wl,--wrap for socket,
 * connect, sendto and sendmsg like the JNI library: this file's calls reach
 * the guard exactly as reSIProcate's and c-ares' do there.
 * Exit status 0 = every check passed.
 */
#include "sip_socket_guard.h"

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <unistd.h>

namespace {

int gChecks = 0;
int gFailures = 0;

#define CHECK(cond)                                                              \
    do {                                                                         \
        ++gChecks;                                                               \
        if (!(cond)) {                                                           \
            fprintf(stderr, "FAIL %s:%d: %s (errno=%d %s)\n", __FILE__, __LINE__, \
                    #cond, errno, strerror(errno));                              \
            ++gFailures;                                                         \
        }                                                                        \
    } while (0)

using hamelinports::isSocketPinned;
using hamelinports::markSocketPinned;

sockaddr_in loopback4(in_port_t port) {
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    a.sin_port = port;
    return a;
}

/* A TCP listener on 127.0.0.1; it is never connect()ed, so the guard ignores it. */
int openListener(sockaddr_in* addr) {
    const int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    *addr = loopback4(0);
    socklen_t len = sizeof(*addr);
    if (fd < 0 || bind(fd, reinterpret_cast<sockaddr*>(addr), sizeof(*addr)) != 0 ||
        listen(fd, 4) != 0 || getsockname(fd, reinterpret_cast<sockaddr*>(addr), &len) != 0) {
        perror("listener");
        return -1;
    }
    return fd;
}

bool readableWithin(int fd, int timeoutMs) {
    pollfd p{fd, POLLIN, 0};
    return poll(&p, 1, timeoutMs) == 1;
}

void unpinnedTcpSocketCannotConnect() {
    sockaddr_in server;
    const int listener = openListener(&server);
    const int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    CHECK(!isSocketPinned(fd));
    errno = 0;
    CHECK(connect(fd, reinterpret_cast<sockaddr*>(&server), sizeof(server)) == -1);
    CHECK(errno == ENETUNREACH);
    CHECK(!readableWithin(listener, 200));  // no SYN reached the server
    close(fd);
    close(listener);
}

void pinnedTcpSocketConnects() {
    sockaddr_in server;
    const int listener = openListener(&server);
    const int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    markSocketPinned(fd);
    CHECK(connect(fd, reinterpret_cast<sockaddr*>(&server), sizeof(server)) == 0);
    CHECK(readableWithin(listener, 1000));
    close(fd);
    close(listener);
}

/* The route probe: a UDP socket connect()ed only to ask the kernel for a
 * source address (no packet is sent), then dissolved with AF_UNSPEC. */
void unpinnedUdpProbeCannotConnect() {
    const int fd = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, IPPROTO_UDP);
    if (fd < 0) {
        printf("SKIP unpinnedUdpProbeCannotConnect: no IPv6\n");
        return;
    }
    sockaddr_in6 target{};
    target.sin6_family = AF_INET6;
    target.sin6_addr = in6addr_loopback;
    target.sin6_port = htons(5060);
    errno = 0;
    CHECK(connect(fd, reinterpret_cast<sockaddr*>(&target), sizeof(target)) == -1);
    CHECK(errno == ENETUNREACH);
    markSocketPinned(fd);
    CHECK(connect(fd, reinterpret_cast<sockaddr*>(&target), sizeof(target)) == 0);
    sockaddr unspec{};
    unspec.sa_family = AF_UNSPEC;
    CHECK(connect(fd, &unspec, sizeof(unspec)) == 0);
    close(fd);
}

void dissolvingAnUnpinnedSocketPasses() {
    const int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, IPPROTO_UDP);
    sockaddr unspec{};
    unspec.sa_family = AF_UNSPEC;
    CHECK(connect(fd, &unspec, sizeof(unspec)) == 0);
    close(fd);
}

void reusedDescriptorStartsUnpinned() {
    const int first = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    markSocketPinned(first);
    close(first);
    const int second = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    CHECK(second == first);  // the lowest free number comes back
    CHECK(!isSocketPinned(second));
    sockaddr_in server;
    const int listener = openListener(&server);
    errno = 0;
    CHECK(connect(second, reinterpret_cast<sockaddr*>(&server), sizeof(server)) == -1);
    CHECK(errno == ENETUNREACH);
    close(second);
    close(listener);
}

/* The IMS family: a reused AF_INET6 number must not inherit the old pin. */
void reusedIpv6DescriptorStartsUnpinned() {
    const int first = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, IPPROTO_UDP);
    if (first < 0) {
        printf("SKIP reusedIpv6DescriptorStartsUnpinned: no IPv6\n");
        return;
    }
    markSocketPinned(first);
    close(first);
    const int second = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, IPPROTO_UDP);
    CHECK(second == first);
    CHECK(!isSocketPinned(second));
    sockaddr_in6 target{};
    target.sin6_family = AF_INET6;
    target.sin6_addr = in6addr_loopback;
    target.sin6_port = htons(5060);
    errno = 0;
    CHECK(connect(second, reinterpret_cast<sockaddr*>(&target), sizeof(target)) == -1);
    CHECK(errno == ENETUNREACH);
    close(second);
}

/* A UDP receiver on [::1] (or 127.0.0.1 without IPv6); its address goes to *addr. */
int openUdpReceiver(sockaddr_storage* addr, socklen_t* len) {
    int fd = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, IPPROTO_UDP);
    sockaddr_in6 a6{};
    a6.sin6_family = AF_INET6;
    a6.sin6_addr = in6addr_loopback;
    if (fd >= 0 && bind(fd, reinterpret_cast<sockaddr*>(&a6), sizeof(a6)) == 0) {
        *len = sizeof(sockaddr_in6);
    } else {
        if (fd >= 0) close(fd);
        fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, IPPROTO_UDP);
        sockaddr_in a4 = loopback4(0);
        if (bind(fd, reinterpret_cast<sockaddr*>(&a4), sizeof(a4)) != 0) return -1;
        *len = sizeof(sockaddr_in);
    }
    getsockname(fd, reinterpret_cast<sockaddr*>(addr), len);
    return fd;
}

void unpinnedSendtoIsRefused() {
    sockaddr_storage to{};
    socklen_t toLen = 0;
    const int receiver = openUdpReceiver(&to, &toLen);
    const int fd = socket(to.ss_family, SOCK_DGRAM | SOCK_CLOEXEC, IPPROTO_UDP);
    errno = 0;
    CHECK(sendto(fd, "x", 1, 0, reinterpret_cast<sockaddr*>(&to), toLen) == -1);
    CHECK(errno == ENETUNREACH);
    CHECK(!readableWithin(receiver, 200));  // nothing was sent
    markSocketPinned(fd);
    CHECK(sendto(fd, "x", 1, 0, reinterpret_cast<sockaddr*>(&to), toLen) == 1);
    CHECK(readableWithin(receiver, 1000));
    close(fd);
    close(receiver);
}

void unpinnedSendmsgIsRefused() {
    sockaddr_storage to{};
    socklen_t toLen = 0;
    const int receiver = openUdpReceiver(&to, &toLen);
    const int fd = socket(to.ss_family, SOCK_DGRAM | SOCK_CLOEXEC, IPPROTO_UDP);
    char byte = 'x';
    iovec iov{&byte, 1};
    msghdr msg{};
    msg.msg_name = &to;
    msg.msg_namelen = toLen;
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    errno = 0;
    CHECK(sendmsg(fd, &msg, 0) == -1);
    CHECK(errno == ENETUNREACH);
    CHECK(!readableWithin(receiver, 200));
    markSocketPinned(fd);
    CHECK(sendmsg(fd, &msg, 0) == 1);
    CHECK(readableWithin(receiver, 1000));
    close(fd);
    close(receiver);
}

/* A send without an address goes to the connected peer (or fails in the
 * kernel): the guard does not decide it. */
void sendWithoutAddressPasses() {
    const int fd = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, IPPROTO_UDP);
    if (fd < 0) {
        printf("SKIP sendWithoutAddressPasses: no IPv6\n");
        return;
    }
    errno = 0;
    CHECK(sendto(fd, "x", 1, 0, nullptr, 0) == -1);
    CHECK(errno == EDESTADDRREQ);  // the kernel's answer, not the guard's
    char byte = 'x';
    iovec iov{&byte, 1};
    msghdr msg{};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    errno = 0;
    CHECK(sendmsg(fd, &msg, 0) == -1);
    CHECK(errno == EDESTADDRREQ);
    close(fd);
}

void localSocketsPass() {
    const int listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    const char name[] = "hamelinports-guard-test";
    memcpy(addr.sun_path + 1, name, sizeof(name) - 1);  // abstract namespace
    const socklen_t len = offsetof(sockaddr_un, sun_path) + 1 + sizeof(name) - 1;
    CHECK(bind(listener, reinterpret_cast<sockaddr*>(&addr), len) == 0);
    CHECK(listen(listener, 1) == 0);
    const int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    CHECK(connect(fd, reinterpret_cast<sockaddr*>(&addr), len) == 0);
    close(fd);
    close(listener);
}

}  // namespace

int main() {
    signal(SIGPIPE, SIG_IGN);
    unpinnedTcpSocketCannotConnect();
    pinnedTcpSocketConnects();
    unpinnedUdpProbeCannotConnect();
    dissolvingAnUnpinnedSocketPasses();
    reusedDescriptorStartsUnpinned();
    reusedIpv6DescriptorStartsUnpinned();
    unpinnedSendtoIsRefused();
    unpinnedSendmsgIsRefused();
    sendWithoutAddressPasses();
    localSocketsPass();
    printf("%s: %d checks, %d failed\n", gFailures ? "FAIL" : "PASS", gChecks, gFailures);
    return gFailures ? 1 : 0;
}

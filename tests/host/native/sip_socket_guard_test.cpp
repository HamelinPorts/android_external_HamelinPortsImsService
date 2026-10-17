/*
 * Host test of jni/sip_socket_guard.cpp, linked with -Wl,--wrap=socket and
 * -Wl,--wrap=connect like the JNI library: this file's socket()/connect()
 * calls reach the guard exactly as reSIProcate's and c-ares' do there.
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
    localSocketsPass();
    printf("%s: %d checks, %d failed\n", gFailures ? "FAIL" : "PASS", gChecks, gFailures);
    return gFailures ? 1 : 0;
}

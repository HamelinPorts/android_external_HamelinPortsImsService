/*
 * Host test of discardSocketKeepingDescriptor (jni/sip_socket_discard.h) on
 * the three sockets the SIP socket hook sees: outgoing (bound, before
 * connect), listening (bound, before listen) and accepted.
 * Exit status 0 = every check passed.
 */
#include "sip_socket_discard.h"

#include <arpa/inet.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
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

using hamelinports::discardSocketKeepingDescriptor;

int openDescriptorCount() {
    DIR* dir = opendir("/proc/self/fd");
    if (dir == nullptr) return -1;
    int n = 0;
    while (readdir(dir) != nullptr) ++n;
    closedir(dir);
    return n;
}

sockaddr_in loopback(in_port_t port) {
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    a.sin_port = port;
    return a;
}

/* A listening socket on 127.0.0.1; its address goes to *addr. */
int openListener(sockaddr_in* addr) {
    const int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    *addr = loopback(0);
    socklen_t len = sizeof(*addr);
    if (fd < 0 || bind(fd, reinterpret_cast<sockaddr*>(addr), sizeof(*addr)) != 0 ||
        listen(fd, 4) != 0 || getsockname(fd, reinterpret_cast<sockaddr*>(addr), &len) != 0) {
        perror("listener");
        return -1;
    }
    return fd;
}

int socketDomain(int fd) {
    int domain = -1;
    socklen_t len = sizeof(domain);
    return getsockopt(fd, SOL_SOCKET, SO_DOMAIN, &domain, &len) == 0 ? domain : -1;
}

bool readableWithin(int fd, int timeoutMs) {
    pollfd p{fd, POLLIN, 0};
    return poll(&p, 1, timeoutMs) == 1;
}

void outgoingSocketNeverConnects() {
    sockaddr_in server;
    const int listener = openListener(&server);
    CHECK(listener >= 0);
    const int fd = socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    sockaddr_in local = loopback(0);
    /* reSIProcate binds port-c before it runs the hook. */
    CHECK(bind(fd, reinterpret_cast<sockaddr*>(&local), sizeof(local)) == 0);

    CHECK(discardSocketKeepingDescriptor(fd));
    CHECK(socketDomain(fd) == AF_UNIX);
    errno = 0;
    const int rc = connect(fd, reinterpret_cast<sockaddr*>(&server), sizeof(server));
    CHECK(rc == -1 && errno != EINPROGRESS && errno != EAGAIN);
    CHECK(!readableWithin(listener, 200));  // no SYN reached the server
    close(fd);
    close(listener);
}

void outgoingIpv6SocketNeverConnects() {
    const int listener = socket(AF_INET6, SOCK_STREAM | SOCK_CLOEXEC, 0);
    sockaddr_in6 server{};
    server.sin6_family = AF_INET6;
    server.sin6_addr = in6addr_loopback;
    socklen_t len = sizeof(server);
    if (listener < 0 || bind(listener, reinterpret_cast<sockaddr*>(&server), sizeof(server)) != 0 ||
        listen(listener, 4) != 0 ||
        getsockname(listener, reinterpret_cast<sockaddr*>(&server), &len) != 0) {
        printf("SKIP outgoingIpv6SocketNeverConnects: no IPv6 loopback\n");
        if (listener >= 0) close(listener);
        return;
    }
    const int fd = socket(AF_INET6, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    CHECK(discardSocketKeepingDescriptor(fd));
    errno = 0;
    const int rc = connect(fd, reinterpret_cast<sockaddr*>(&server), sizeof(server));
    CHECK(rc == -1 && errno != EINPROGRESS && errno != EAGAIN);
    CHECK(!readableWithin(listener, 200));
    close(fd);
    close(listener);
}

void listeningSocketCannotListen() {
    const int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    sockaddr_in local = loopback(0);
    /* reSIProcate binds the listener before it runs the hook. */
    CHECK(bind(fd, reinterpret_cast<sockaddr*>(&local), sizeof(local)) == 0);

    CHECK(discardSocketKeepingDescriptor(fd));
    CHECK(listen(fd, 4) == -1);
    close(fd);
}

void acceptedSocketDropsTheConnection() {
    sockaddr_in server;
    const int listener = openListener(&server);
    const int client = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    CHECK(connect(client, reinterpret_cast<sockaddr*>(&server), sizeof(server)) == 0);
    const int accepted = accept4(listener, nullptr, nullptr, SOCK_NONBLOCK | SOCK_CLOEXEC);
    CHECK(accepted >= 0);

    CHECK(discardSocketKeepingDescriptor(accepted));
    char byte = 0;
    errno = 0;
    /* reSIProcate's read fails at once (it never blocks), so it closes fd. */
    CHECK(read(accepted, &byte, 1) == -1 && errno != EAGAIN);
    CHECK(write(accepted, "x", 1) == -1);
    /* The peer sees the connection closed. */
    CHECK(readableWithin(client, 1000));
    CHECK(read(client, &byte, 1) <= 0);
    close(accepted);
    close(client);
    close(listener);
}

void descriptorStaysOwnedByTheCaller() {
    const int before = openDescriptorCount();
    const int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    CHECK(discardSocketKeepingDescriptor(fd));
    CHECK(fcntl(fd, F_GETFD) != -1);
    CHECK(openDescriptorCount() == before + 1);  // the helper leaks nothing
    CHECK(close(fd) == 0);
    CHECK(openDescriptorCount() == before);
}

void invalidDescriptorIsReported() {
    CHECK(!discardSocketKeepingDescriptor(-1));
}

}  // namespace

int main() {
    /* A write to a closed TCP socket must fail a check, not kill the test. */
    signal(SIGPIPE, SIG_IGN);
    outgoingSocketNeverConnects();
    outgoingIpv6SocketNeverConnects();
    listeningSocketCannotListen();
    acceptedSocketDropsTheConnection();
    descriptorStaysOwnedByTheCaller();
    invalidDescriptorIsReported();
    printf("%s: %d checks, %d failed\n", gFailures ? "FAIL" : "PASS", gChecks, gFailures);
    return gFailures ? 1 : 0;
}

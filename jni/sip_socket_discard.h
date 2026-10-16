/*
 * Discards a socket that reSIProcate owns, from inside its socket-creation
 * hook. The hook returns void, so it cannot fail the socket call, and
 * closing the descriptor would leave reSIProcate holding a number that the
 * next open() may reuse.
 */
#pragma once

#include <fcntl.h>
#include <sys/socket.h>
#include <unistd.h>

namespace hamelinports {

/*
 * Replaces the socket behind fd with a fresh unconnected AF_UNIX socket.
 * dup3() swaps it atomically and closes the original socket; the caller's
 * next listen(), connect() or read() on fd fails, so no packet leaves on
 * that socket and reSIProcate closes fd itself. Returns false if fd could
 * not be replaced.
 */
inline bool discardSocketKeepingDescriptor(int fd) {
    if (fd < 0) return false;
    const int dead = ::socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (dead < 0) return false;
    const bool replaced = ::dup3(dead, fd, O_CLOEXEC) == fd;
    ::close(dead);
    return replaced;
}

}  // namespace hamelinports

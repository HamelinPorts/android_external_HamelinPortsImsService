/*
 * Refuses connect(), and sendto()/sendmsg() to an IP address, on an IP
 * socket that the SIP socket hook has not pinned to an IMS network. The
 * library links with -Wl,--wrap for socket, connect, sendto and sendmsg, so
 * every such call of reSIProcate, c-ares and this bridge goes through
 * sip_socket_guard.cpp: a path that creates a socket without running the hook
 * fails at once instead of sending on the default network.
 */
#pragma once

namespace hamelinports {

/* The hook pinned fd to an IMS network: it may connect. */
void markSocketPinned(int fd);

/* True if fd was pinned since socket() last returned that number. */
bool isSocketPinned(int fd);

}  // namespace hamelinports

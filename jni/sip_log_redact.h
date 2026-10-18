#pragma once

/* Formatting for diagnostic SIP log lines that must never carry a phone
 * number, an IMSI, a SIP user part or the UE's own address. Plain C++ on
 * std::string (no reSIProcate types), so the host test drives it directly. */

#include <cctype>
#include <string>

namespace hamelinports {

/** Runs of this many digits (an MSISDN or IMSI, with or without '+') are masked in free text. */
constexpr size_t kMaskDigitRun = 7;

/** Masks the user part of sip:/sips:/tel: URIs and every run of kMaskDigitRun+ digits. */
inline std::string scrubIdentities(const std::string& in) {
    std::string out;
    out.reserve(in.size());
    size_t i = 0;
    while (i < in.size()) {
        bool atWordStart = i == 0 || !std::isalnum(static_cast<unsigned char>(in[i - 1]));
        if (atWordStart && in.compare(i, 4, "tel:") == 0) {
            out += "tel:*";
            i += 4;
            while (i < in.size() && in[i] != ';' && in[i] != '>' && in[i] != ',' && in[i] != '"'
                    && !std::isspace(static_cast<unsigned char>(in[i]))) {
                i++;
            }
            continue;
        }
        size_t scheme = 0;
        if (atWordStart && in.compare(i, 5, "sips:") == 0) scheme = 5;
        else if (atWordStart && in.compare(i, 4, "sip:") == 0) scheme = 4;
        if (scheme) {
            size_t end = i + scheme;
            while (end < in.size() && in[end] != '@' && in[end] != ';' && in[end] != '>'
                    && in[end] != ',' && in[end] != '"'
                    && !std::isspace(static_cast<unsigned char>(in[end]))) {
                end++;
            }
            out.append(in, i, scheme);
            if (end < in.size() && in[end] == '@') {
                out += "*@";
                i = end + 1;
            } else {
                i += scheme;
            }
            continue;
        }
        if (std::isdigit(static_cast<unsigned char>(in[i]))) {
            size_t end = i;
            while (end < in.size() && std::isdigit(static_cast<unsigned char>(in[end]))) end++;
            if (end - i >= kMaskDigitRun) {
                if (!out.empty() && out.back() == '+') out.pop_back();
                out += '#';
            } else {
                out.append(in, i, end - i);
            }
            i = end;
            continue;
        }
        out += in[i++];
    }
    return out;
}

/** "scheme:host[:port]" of a network node's URI (PAI), without the user part; tel: has no host. */
inline std::string uriHostOnly(const std::string& scheme, const std::string& host, int port) {
    if (scheme.empty() || host.empty() || scheme == "tel") return (scheme.empty() ? "?" : scheme) + ":*";
    std::string h = host.find(':') != std::string::npos && host.front() != '[' ? "[" + host + "]" : host;
    return scheme + ":" + scrubIdentities(h) + (port > 0 ? ":" + std::to_string(port) : "");
}

/** Address family of a host, the only part of the UE's own address that is logged. */
inline const char* hostKind(const std::string& host) {
    if (host.find(':') != std::string::npos) return "v6";
    if (host.empty()) return "none";
    for (char c : host) {
        if (!std::isdigit(static_cast<unsigned char>(c)) && c != '.') return "name";
    }
    return "v4";
}

/** One Contact of a REGISTER 200 OK, user and host masked: "#1 sip:*@v6:5060 smsip expires=3600". */
inline std::string maskedContact(int index, const std::string& scheme, const std::string& host,
                                 int port, bool smsip, int expires) {
    std::string s = "#" + std::to_string(index) + " " + (scheme.empty() ? "?" : scheme) + ":*@"
            + hostKind(host);
    if (port > 0) s += ":" + std::to_string(port);
    s += smsip ? " smsip" : " no-smsip";
    if (expires >= 0) s += " expires=" + std::to_string(expires);
    return s;
}

}  // namespace hamelinports

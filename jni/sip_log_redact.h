#pragma once

/* Formatting for diagnostic SIP log lines that must never carry a phone
 * number, an IMSI, a SIP user part or the UE's own address. Plain C++ on
 * std::string (no reSIProcate types), so the host test drives it directly. */

#include <cctype>
#include <regex>
#include <string>

namespace hamelinports {

/** Runs of this many digits (an MSISDN or IMSI, with or without '+') are masked in free text. */
constexpr size_t kMaskDigitRun = 7;

/** Masks the user part of sip:/sips:/tel: URIs and every run of kMaskDigitRun+ digits. */
inline std::string scrubIdentities(const std::string& in) {
    // Mask entire device URNs, including their short serial and check-digit fields.
    static const std::regex imei("urn:gsma:imei:[^\\s;>,\"]+", std::regex::icase);
    static const std::regex ipv4("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
    static const std::regex ipv6(
            "([0-9a-f]{0,4}:){2,}[0-9a-f:.]*(%[a-z0-9_.-]+)?", std::regex::icase);
    std::string safe = std::regex_replace(in, imei, "urn:gsma:imei:*");
    safe = std::regex_replace(safe, ipv6, "[ip]");
    safe = std::regex_replace(safe, ipv4, "[ip]");
    std::string lower = safe;
    for (char& c : lower) c = std::tolower(static_cast<unsigned char>(c));
    std::string out;
    out.reserve(safe.size());
    size_t i = 0;
    while (i < safe.size()) {
        bool atWordStart = i == 0 || !std::isalnum(static_cast<unsigned char>(safe[i - 1]));
        if (atWordStart && lower.compare(i, 4, "tel:") == 0) {
            out += "tel:*";
            i += 4;
            while (i < safe.size() && safe[i] != ';' && safe[i] != '>' && safe[i] != ',' && safe[i] != '"'
                    && !std::isspace(static_cast<unsigned char>(safe[i]))) {
                i++;
            }
            continue;
        }
        size_t scheme = 0;
        if (atWordStart && lower.compare(i, 5, "sips:") == 0) scheme = 5;
        else if (atWordStart && lower.compare(i, 4, "sip:") == 0) scheme = 4;
        if (scheme) {
            size_t end = i + scheme;
            while (end < safe.size() && safe[end] != '@' && safe[end] != ';' && safe[end] != '>'
                    && safe[end] != ',' && safe[end] != '"'
                    && !std::isspace(static_cast<unsigned char>(safe[end]))) {
                end++;
            }
            out.append(safe, i, scheme);
            if (end < safe.size() && safe[end] == '@') {
                out += "*@";
                i = end + 1;
            } else {
                i += scheme;
            }
            continue;
        }
        if (std::isdigit(static_cast<unsigned char>(safe[i]))) {
            size_t end = i;
            size_t digits = 0;
            size_t lastDigit = i;
            while (end < safe.size()) {
                unsigned char c = safe[end];
                if (std::isdigit(c)) {
                    digits++;
                    lastDigit = end + 1;
                } else if (c != ' ' && c != '-' && c != '.' && c != '(' && c != ')') {
                    break;
                }
                end++;
            }
            if (digits >= kMaskDigitRun) {
                if (!out.empty() && out.back() == '+') out.pop_back();
                out += '#';
                i = lastDigit;
            } else {
                out.append(safe, i, end - i);
                i = end;
            }
            continue;
        }
        out += safe[i++];
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

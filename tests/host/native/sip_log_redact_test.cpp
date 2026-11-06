/*
 * Host test of the SIP diagnostic log formatting (jni/sip_log_redact.h): no
 * phone number, IMSI, SIP user part or UE address survives into a log
 * fragment. Exit status 0 = every check passed.
 */
#include "sip_log_redact.h"

#include <stdio.h>

#include <string>

namespace {

int gChecks = 0;
int gFailures = 0;

#define CHECK(cond)                                                     \
    do {                                                                \
        ++gChecks;                                                      \
        if (!(cond)) {                                                  \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++gFailures;                                                \
        }                                                               \
    } while (0)

#define CHECK_EQ(got, want)                                                       \
    do {                                                                          \
        ++gChecks;                                                                \
        if ((got) != (want)) {                                                    \
            fprintf(stderr, "FAIL %s:%d: got \"%s\" want \"%s\"\n", __FILE__,     \
                    __LINE__, std::string(got).c_str(), std::string(want).c_str()); \
            ++gFailures;                                                          \
        }                                                                         \
    } while (0)

using hamelinports::maskedContact;
using hamelinports::scrubIdentities;
using hamelinports::uriHostOnly;

/* Documentation values only: a fictional NANP number (555-01xx), an IMSI of the
 * test network (MCC 001, MNC 01), RFC 3849 / RFC 5737 addresses. */
const char* const kMsisdn = "12025550123";
const char* const kImsi = "001010123456789";

bool hasNoIdentity(const std::string& s) {
    return s.find(kMsisdn) == std::string::npos && s.find(kImsi) == std::string::npos
            && s.find("5550123") == std::string::npos && s.find("alice") == std::string::npos
            && s.find("2001:db8:1:2") == std::string::npos && s.find("198.51.100.") == std::string::npos;
}

void userPartsAreMasked() {
    CHECK_EQ(scrubIdentities("sip:+12025550123@ims.mnc001.mcc001.3gppnetwork.org"),
             "sip:*@ims.mnc001.mcc001.3gppnetwork.org");
    CHECK_EQ(scrubIdentities("<sips:alice@example.com>;tag=1"), "<sips:*@example.com>;tag=1");
    CHECK_EQ(scrubIdentities("tel:+12025550123;phone-context=x"), "tel:*;phone-context=x");
    CHECK_EQ(scrubIdentities("sip:192.0.2.10:5060"), "sip:[ip]:5060");
    CHECK_EQ(scrubIdentities("msip:alice@x"), "msip:alice@x");  /* not a URI scheme */
    CHECK_EQ(scrubIdentities("xsips:alice@x"), "xsips:alice@x");
    CHECK_EQ(scrubIdentities("xtel:abc"), "xtel:abc");
}

void digitRunsAreMasked() {
    CHECK_EQ(scrubIdentities("399 smsc \"barred for +12025550123\""), "399 smsc \"barred for #\"");
    CHECK_EQ(scrubIdentities("imsi=001010123456789;cause=10"), "imsi=#;cause=10");
    CHECK_EQ(scrubIdentities("SIP;cause=503;text=\"Service Unavailable\""),
             "SIP;cause=503;text=\"Service Unavailable\"");
    CHECK_EQ(scrubIdentities("123456"), "123456");
    CHECK_EQ(scrubIdentities("1234567"), "#");
}

void warningReasonDebugInfoCarryNoIdentity() {
    const std::string headers[] = {
        "399 pcscf.example \"user sip:+12025550123@ims.example not allowed\"",
        "Q.850;cause=21;text=\"tel:+12025550123 barred\"",
        "SMS-over-IP barred; imsi=001010123456789; msisdn=12025550123",
        "sip:001010123456789@ims.mnc001.mcc001.3gppnetwork.org",
    };
    for (const auto& h : headers) {
        const std::string s = scrubIdentities(h);
        CHECK(hasNoIdentity(s));
    }
}

void paiKeepsOnlyTheNode() {
    CHECK_EQ(uriHostOnly("sip", "192.0.2.10", 5060), "sip:[ip]:5060");
    CHECK_EQ(uriHostOnly("sip", "example.net", 0), "sip:example.net");
    CHECK_EQ(uriHostOnly("sip", "2001:db8::1", 5060), "sip:[[ip]]:5060");
    CHECK_EQ(uriHostOnly("tel", "", 0), "tel:*");
    CHECK_EQ(uriHostOnly("tel", "12025550123", 0), "tel:*");
    CHECK_EQ(uriHostOnly("", "", 0), "?:*");
    CHECK_EQ(uriHostOnly("sip", "12025550123.example", 0), "sip:#.example");
    CHECK(hasNoIdentity(uriHostOnly("sip", "12025550123.example", 0)));
}

void freeTextDoesNotLeakSeparatedOrUppercaseIdentities() {
    CHECK_EQ(scrubIdentities("+1 202 555 0123"), "#");
    CHECK_EQ(scrubIdentities("1202-555-0123"), "#");
    CHECK_EQ(scrubIdentities("1202.555.0123"), "#");
    CHECK_EQ(scrubIdentities("SIP:alice@example.com"), "SIP:*@example.com");
    CHECK_EQ(scrubIdentities("SiPs:alice@example.com"), "SiPs:*@example.com");
    CHECK_EQ(scrubIdentities("TEL:+1-202-555-0123"), "tel:*");
    CHECK_EQ(scrubIdentities("urn:gsma:imei:00000000-123456-0"), "urn:gsma:imei:*");
    CHECK_EQ(scrubIdentities("URN:GSMA:IMEI:00000000-123456-0"), "urn:gsma:imei:*");
    CHECK_EQ(scrubIdentities("UE 192.0.2.9 / 2001:db8::12 / ::1"), "UE [ip] / [ip] / [ip]");
    CHECK_EQ(scrubIdentities("UE [fe80::1%wlan0]"), "UE [[ip]]");
    CHECK_EQ(scrubIdentities("SIP;cause=503;text=\"barred +1 (202) 555-0123\""),
             "SIP;cause=503;text=\"barred #\"");
}

void contactsShowFamilyPortAndFeatureTagOnly() {
    CHECK_EQ(maskedContact(1, "sip", "2001:db8:1:2::5", 5060, true, 3600),
             "#1 sip:*@v6:5060 smsip expires=3600");
    CHECK_EQ(maskedContact(2, "sip", "198.51.100.2", 0, false, -1), "#2 sip:*@v4 no-smsip");
    CHECK_EQ(maskedContact(3, "sip", "ue.example", 6000, true, 0), "#3 sip:*@name:6000 smsip expires=0");
    CHECK_EQ(maskedContact(4, "", "", 0, false, 600), "#4 ?:*@none no-smsip expires=600");
    CHECK(hasNoIdentity(maskedContact(1, "sip", "2001:db8:1:2::5", 5060, true, 3600)));
    CHECK(hasNoIdentity(maskedContact(1, "sip", "198.51.100.2", 5060, true, 3600)));
}

}  // namespace

int main() {
    userPartsAreMasked();
    digitRunsAreMasked();
    warningReasonDebugInfoCarryNoIdentity();
    paiKeepsOnlyTheNode();
    contactsShowFamilyPortAndFeatureTagOnly();
    freeTextDoesNotLeakSeparatedOrUppercaseIdentities();
    printf("%s: %d checks, %d failed\n", gFailures ? "FAIL" : "PASS", gChecks, gFailures);
    return gFailures ? 1 : 0;
}

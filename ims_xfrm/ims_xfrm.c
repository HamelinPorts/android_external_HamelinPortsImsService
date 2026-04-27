#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/wait.h>
#include <ctype.h>
#include <sys/system_properties.h>

static int run_ip(char *const argv[]) {
    pid_t pid = fork();
    if (pid == 0) {
        execv("/system/bin/ip", argv);
        _exit(127);
    }
    int status;
    waitpid(pid, &status, 0);
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

static int valid_ipv6(const char *s) {
    if (!s || !*s) return 0;
    for (; *s; s++) {
        if (!isxdigit(*s) && *s != ':' && *s != '.') return 0;
    }
    return 1;
}

static int valid_spi(const char *s) {
    if (!s || s[0] != '0' || s[1] != 'x') return 0;
    for (s += 2; *s; s++) {
        if (!isxdigit(*s)) return 0;
    }
    return 1;
}

static int valid_port(const char *s) {
    if (!s || !*s) return 0;
    int n = 0;
    for (; *s; s++) {
        if (!isdigit(*s)) return 0;
        n = n * 10 + (*s - '0');
        if (n > 65535) return 0;
    }
    return n > 0;
}

/*
 * Delete a 5-tuple policy if it exists. Ignore errors (likely "not found" on
 * first run). Must use the exact same selectors that add used, otherwise the
 * kernel keeps the stale policy alive and we double-book.
 */
static void del_policy(const char *src, const char *dst,
                       const char *sport, const char *dport, const char *dir) {
    char src_cidr[256], dst_cidr[256];
    snprintf(src_cidr, sizeof(src_cidr), "%s/128", src);
    snprintf(dst_cidr, sizeof(dst_cidr), "%s/128", dst);
    /* `ip xfrm policy delete` does NOT accept `priority` (unlike `add`) —
     * per iproute2 help, only { SELECTOR | index } + dir + ctx + mark +
     * ptype. Including priority makes the parser reject the whole
     * command with "argument priority is wrong: unknown". */
    char *argv[] = {"ip", "-6", "xfrm", "policy", "delete",
        "src", src_cidr, "dst", dst_cidr,
        "proto", "6",
        "sport", (char*)sport, "dport", (char*)dport,
        "dir", (char*)dir, NULL};
    run_ip(argv);
}

/*
 * Add an outbound xfrm policy with SPI pinned. The kernel will select
 * the SA whose (dst, spi, proto) matches this template; reqid 1000
 * matches AOSP IpSecManager's default allocation.
 */
static int add_out_policy(const char *local, const char *pcscf,
                          const char *sport, const char *dport,
                          const char *spi) {
    char src_cidr[256], dst_cidr[256];
    snprintf(src_cidr, sizeof(src_cidr), "%s/128", local);
    snprintf(dst_cidr, sizeof(dst_cidr), "%s/128", pcscf);
    /* bp4a's iproute2 requires numeric proto (6=TCP) before sport/dport
     * in the selector UPSPEC; plain "sport/dport" gets rejected as
     * "argument priority is wrong: unknown" because the parser bails
     * mid-SELECTOR. */
    char *argv[] = {"ip", "-6", "xfrm", "policy", "add",
        "src", src_cidr, "dst", dst_cidr,
        "proto", "6",
        "sport", (char*)sport, "dport", (char*)dport,
        "dir", "out", "priority", "2000",
        "tmpl",
        "src", (char*)local, "dst", (char*)pcscf,
        "proto", "esp", "spi", (char*)spi,
        "reqid", "1000", "mode", "transport", NULL};
    return run_ip(argv);
}

/*
 * Add an inbound xfrm policy. No SPI in the template — the kernel picks
 * the SA by matching the incoming ESP header's SPI against its state db.
 */
static int add_in_policy(const char *pcscf, const char *local,
                         const char *sport, const char *dport) {
    char src_cidr[256], dst_cidr[256];
    snprintf(src_cidr, sizeof(src_cidr), "%s/128", pcscf);
    snprintf(dst_cidr, sizeof(dst_cidr), "%s/128", local);
    char *argv[] = {"ip", "-6", "xfrm", "policy", "add",
        "src", src_cidr, "dst", dst_cidr,
        "proto", "6",
        "sport", (char*)sport, "dport", (char*)dport,
        "dir", "in", "priority", "2000",
        "tmpl",
        "src", (char*)pcscf, "dst", (char*)local,
        "proto", "esp",
        "reqid", "1000", "mode", "transport", NULL};
    return run_ip(argv);
}

static int do_install(const char *pcscf, const char *local,
                      const char *ue_portc, const char *ue_ports,
                      const char *pcscf_portc, const char *pcscf_ports,
                      const char *server_spic, const char *server_spis) {
    /* Wipe any stale versions of the four policies first (ignore errors). */
    del_policy(local,  pcscf, ue_portc, pcscf_ports, "out");
    del_policy(pcscf, local,  pcscf_ports, ue_portc, "in");
    del_policy(local,  pcscf, ue_ports, pcscf_portc, "out");
    del_policy(pcscf, local,  pcscf_portc, ue_ports, "in");

    /* SA1 out: UE:portC -> PCSCF:portS, spi = server's spi-s */
    int rc = add_out_policy(local, pcscf, ue_portc, pcscf_ports, server_spis);
    if (rc != 0) { fprintf(stderr, "SA1 out failed rc=%d\n", rc); return 10; }

    /* SA2 in: PCSCF:portS -> UE:portC */
    rc = add_in_policy(pcscf, local, pcscf_ports, ue_portc);
    if (rc != 0) { fprintf(stderr, "SA2 in failed rc=%d\n", rc); return 11; }

    /* SA3 out: UE:portS -> PCSCF:portC, spi = server's spi-c */
    rc = add_out_policy(local, pcscf, ue_ports, pcscf_portc, server_spic);
    if (rc != 0) { fprintf(stderr, "SA3 out failed rc=%d\n", rc); return 12; }

    /* SA4 in: PCSCF:portC -> UE:portS */
    rc = add_in_policy(pcscf, local, pcscf_portc, ue_ports);
    if (rc != 0) { fprintf(stderr, "SA4 in failed rc=%d\n", rc); return 13; }

    return 0;
}

static int do_flush(const char *pcscf, const char *local,
                    const char *ue_portc, const char *ue_ports,
                    const char *pcscf_portc, const char *pcscf_ports) {
    del_policy(local,  pcscf, ue_portc, pcscf_ports, "out");
    del_policy(pcscf, local,  pcscf_ports, ue_portc, "in");
    del_policy(local,  pcscf, ue_ports, pcscf_portc, "out");
    del_policy(pcscf, local,  pcscf_portc, ue_ports, "in");
    return 0;
}

int main(int argc, char *argv[]) {
    if (argc < 2) {
        fprintf(stderr, "usage: ims_xfrm addprop | add ... | del ... | flush ...\n");
        return 1;
    }

    if (setuid(0) != 0) {
        perror("setuid");
        return 1;
    }

    if (strcmp(argv[1], "addprop") == 0) {
        char pcscf[PROP_VALUE_MAX]      = {0};
        char local[PROP_VALUE_MAX]      = {0};
        char ue_portc[PROP_VALUE_MAX]   = {0};
        char ue_ports[PROP_VALUE_MAX]   = {0};
        char pc_portc[PROP_VALUE_MAX]   = {0};
        char pc_ports[PROP_VALUE_MAX]   = {0};
        char server_spic[PROP_VALUE_MAX] = {0};
        char server_spis[PROP_VALUE_MAX] = {0};
        __system_property_get("lineage.ims.xfrm.pcscf",       pcscf);
        __system_property_get("lineage.ims.xfrm.local",       local);
        __system_property_get("lineage.ims.xfrm.ue_portc",    ue_portc);
        __system_property_get("lineage.ims.xfrm.ue_ports",    ue_ports);
        __system_property_get("lineage.ims.xfrm.pcscf_portc", pc_portc);
        __system_property_get("lineage.ims.xfrm.pcscf_ports", pc_ports);
        __system_property_get("lineage.ims.xfrm.server_spic", server_spic);
        __system_property_get("lineage.ims.xfrm.server_spis", server_spis);

        if (!valid_ipv6(pcscf) || !valid_ipv6(local)) {
            fprintf(stderr, "invalid ipv6\n"); return 1;
        }
        if (!valid_port(ue_portc) || !valid_port(ue_ports)
         || !valid_port(pc_portc) || !valid_port(pc_ports)) {
            fprintf(stderr, "invalid port\n"); return 1;
        }
        if (!valid_spi(server_spic) || !valid_spi(server_spis)) {
            fprintf(stderr, "invalid spi\n"); return 1;
        }
        return do_install(pcscf, local, ue_portc, ue_ports,
                          pc_portc, pc_ports, server_spic, server_spis);
    }

    if (strcmp(argv[1], "add") == 0 && argc >= 10) {
        if (!valid_ipv6(argv[2]) || !valid_ipv6(argv[3])) return 1;
        if (!valid_port(argv[4]) || !valid_port(argv[5])
         || !valid_port(argv[6]) || !valid_port(argv[7])) return 1;
        if (!valid_spi(argv[8]) || !valid_spi(argv[9])) return 1;
        return do_install(argv[2], argv[3], argv[4], argv[5],
                          argv[6], argv[7], argv[8], argv[9]);
    }

    if (strcmp(argv[1], "del") == 0 && argc >= 8) {
        if (!valid_ipv6(argv[2]) || !valid_ipv6(argv[3])) return 1;
        if (!valid_port(argv[4]) || !valid_port(argv[5])
         || !valid_port(argv[6]) || !valid_port(argv[7])) return 1;
        return do_flush(argv[2], argv[3], argv[4], argv[5], argv[6], argv[7]);
    }

    if (strcmp(argv[1], "flush") == 0 && argc >= 8) {
        if (!valid_ipv6(argv[2]) || !valid_ipv6(argv[3])) return 1;
        if (!valid_port(argv[4]) || !valid_port(argv[5])
         || !valid_port(argv[6]) || !valid_port(argv[7])) return 1;
        return do_flush(argv[2], argv[3], argv[4], argv[5], argv[6], argv[7]);
    }

    fprintf(stderr,
        "usage: ims_xfrm addprop\n"
        "       ims_xfrm add <pcscf> <local> <ue_portc> <ue_ports> <pc_portc> <pc_ports> <server_spic> <server_spis>\n"
        "       ims_xfrm del <pcscf> <local> <ue_portc> <ue_ports> <pc_portc> <pc_ports>\n"
        "       ims_xfrm flush <pcscf> <local> <ue_portc> <ue_ports> <pc_portc> <pc_ports>\n");
    return 1;
}

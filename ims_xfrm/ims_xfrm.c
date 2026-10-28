#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/wait.h>
#include <ctype.h>
#include <fcntl.h>
#include <arpa/inet.h>
#include <sys/file.h>
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
    struct in6_addr addr;
    return inet_pton(AF_INET6, s, &addr) == 1;
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

static int valid_slot(const char *s) {
    return s && (s[0] == '0' || s[0] == '1') && s[1] == '\0';
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
 * Reqid that binds our policy templates to the SAs that AOSP
 * IpSecManager (via IpSecService → netd XfrmController) installs.
 * On bp4a the framework currently passes reqid 1000 for every
 * resource-id transform; the kernel's xfrm_tmpl_resolve_one does a
 * strict equality check (tmpl->reqid != x->props.reqid → no match),
 * so dropping this value or setting it to 0 silently breaks outbound
 * encapsulation (REGISTER 2 leaves cleartext, TAS times us out at
 * 408). If a future AOSP bump changes the constant, the symptom is
 * the same: revisit by reading the actual reqid off the SA via the
 * netlink XFRM_MSG_GETSA dump and threading it into the prop bag.
 */
#define IMS_XFRM_REQID "1000"

/*
 * Add an outbound xfrm policy with SPI pinned. The kernel will select
 * the SA whose (dst, spi, proto, reqid) matches this template.
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
        "reqid", IMS_XFRM_REQID, "mode", "transport", NULL};
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
        "reqid", IMS_XFRM_REQID, "mode", "transport", NULL};
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

/*
 * Read a per-slot property. Slot is "0" or "1"; the resulting key is
 * "lineage.ims.xfrm.<slot>.<suffix>". Returns 1 on hit, 0 otherwise.
 * The key buffer is larger than the legacy PROP_NAME_MAX (32): the flush-bag
 * keys need 33 bytes, and property names are no longer length-limited.
 */
static int get_slot_prop(const char *slot, const char *suffix, char *out) {
    char key[64];
    int n = snprintf(key, sizeof(key), "lineage.ims.xfrm.%s.%s", slot, suffix);
    if (n < 0 || (size_t)n >= sizeof(key)) return 0;
    return __system_property_get(key, out) > 0;
}

/*
 * One slot's prop bag: the install bag (trigger) or the flush bag (f_*,
 * flush). The stamp is the value of the trigger/flush property; the
 * service writes it from a monotonic clock, so the larger stamp is the
 * later request.
 */
struct xfrm_bag {
    char pcscf[PROP_VALUE_MAX];
    char local[PROP_VALUE_MAX];
    char ue_portc[PROP_VALUE_MAX];
    char ue_ports[PROP_VALUE_MAX];
    char pc_portc[PROP_VALUE_MAX];
    char pc_ports[PROP_VALUE_MAX];
    char server_spic[PROP_VALUE_MAX];   /* install bag only */
    char server_spis[PROP_VALUE_MAX];   /* install bag only */
    unsigned long long stamp;
    int valid;
};

static unsigned long long parse_stamp(const char *s) {
    if (!*s) return 0;
    for (const char *c = s; *c; c++) {
        if (!isdigit(*c)) return 0;
    }
    return strtoull(s, NULL, 10);
}

static void get_bag_prop(const char *slot, int flush_bag, const char *name, char *out) {
    char suffix[32];
    snprintf(suffix, sizeof(suffix), "%s%s", flush_bag ? "f_" : "", name);
    get_slot_prop(slot, suffix, out);
}

static void read_bag(const char *slot, int flush_bag, struct xfrm_bag *b) {
    char stamp[PROP_VALUE_MAX] = {0};

    memset(b, 0, sizeof(*b));
    /* Stamp first: the service writes it after the fields, so the fields
     * read below are at least as new as the stamp. */
    get_slot_prop(slot, flush_bag ? "flush" : "trigger", stamp);
    b->stamp = parse_stamp(stamp);

    get_bag_prop(slot, flush_bag, "pcscf",       b->pcscf);
    get_bag_prop(slot, flush_bag, "local",       b->local);
    get_bag_prop(slot, flush_bag, "ue_portc",    b->ue_portc);
    get_bag_prop(slot, flush_bag, "ue_ports",    b->ue_ports);
    get_bag_prop(slot, flush_bag, "pcscf_portc", b->pc_portc);
    get_bag_prop(slot, flush_bag, "pcscf_ports", b->pc_ports);
    if (!flush_bag) {
        get_slot_prop(slot, "server_spic", b->server_spic);
        get_slot_prop(slot, "server_spis", b->server_spis);
    }
    b->valid = valid_ipv6(b->pcscf) && valid_ipv6(b->local)
            && valid_port(b->ue_portc) && valid_port(b->ue_ports)
            && valid_port(b->pc_portc) && valid_port(b->pc_ports)
            && (flush_bag || (valid_spi(b->server_spic) && valid_spi(b->server_spis)));
}

/* Selector of one of the four policies; spi is set for outbound only. */
struct xfrm_sel {
    const char *src, *dst, *sport, *dport, *dir, *spi;
};

static void bag_policies(const struct xfrm_bag *b, struct xfrm_sel p[4]) {
    /* SA1 out: UE:portC -> PCSCF:portS, spi = server's spi-s */
    p[0] = (struct xfrm_sel){b->local, b->pcscf, b->ue_portc, b->pc_ports, "out", b->server_spis};
    /* SA2 in: PCSCF:portS -> UE:portC */
    p[1] = (struct xfrm_sel){b->pcscf, b->local, b->pc_ports, b->ue_portc, "in", NULL};
    /* SA3 out: UE:portS -> PCSCF:portC, spi = server's spi-c */
    p[2] = (struct xfrm_sel){b->local, b->pcscf, b->ue_ports, b->pc_portc, "out", b->server_spic};
    /* SA4 in: PCSCF:portC -> UE:portS */
    p[3] = (struct xfrm_sel){b->pcscf, b->local, b->pc_portc, b->ue_ports, "in", NULL};
}

static int same_selector(const struct xfrm_sel *a, const struct xfrm_sel *b) {
    return !strcmp(a->src, b->src) && !strcmp(a->dst, b->dst)
        && !strcmp(a->sport, b->sport) && !strcmp(a->dport, b->dport)
        && !strcmp(a->dir, b->dir);
}

/*
 * True if bag `other` names policy `p` and was requested after `mine`.
 * Runs take the lock in any order (see lock_helper()) and read the current
 * bags, so each run acts only on the policies whose latest request is its
 * own; the result then matches the request order. Checked per policy: a
 * re-registration may keep ports, so the old and new quartets can share
 * single policies.
 */
static int superseded(const struct xfrm_sel *p, const struct xfrm_bag *mine,
                      const struct xfrm_bag *other) {
    if (!other->valid || other->stamp <= mine->stamp) return 0;
    struct xfrm_sel q[4];
    bag_policies(other, q);
    for (int i = 0; i < 4; i++) {
        if (same_selector(p, &q[i])) return 1;
    }
    return 0;
}

static int install_slot(const char *slot) {
    struct xfrm_bag inst, flush;
    read_bag(slot, 0, &inst);
    read_bag(slot, 1, &flush);
    if (!inst.valid) { fprintf(stderr, "invalid install bag\n"); return 1; }

    struct xfrm_sel p[4];
    bag_policies(&inst, p);
    for (int i = 0; i < 4; i++) {
        if (superseded(&p[i], &inst, &flush)) continue;
        /* Wipe a stale version first (ignore errors). */
        del_policy(p[i].src, p[i].dst, p[i].sport, p[i].dport, p[i].dir);
        int rc = p[i].spi
            ? add_out_policy(p[i].src, p[i].dst, p[i].sport, p[i].dport, p[i].spi)
            : add_in_policy(p[i].src, p[i].dst, p[i].sport, p[i].dport);
        if (rc != 0) { fprintf(stderr, "SA%d %s failed rc=%d\n", i + 1, p[i].dir, rc); return 10 + i; }
    }
    return 0;
}

static int flush_slot(const char *slot) {
    struct xfrm_bag inst, flush;
    read_bag(slot, 1, &flush);
    read_bag(slot, 0, &inst);
    /* Empty bag: nothing to flush. */
    if (!flush.valid) return 0;

    struct xfrm_sel p[4];
    bag_policies(&flush, p);
    for (int i = 0; i < 4; i++) {
        if (superseded(&p[i], &flush, &inst)) continue;
        del_policy(p[i].src, p[i].dst, p[i].sport, p[i].dport, p[i].dir);
    }
    return 0;
}

/*
 * Init starts each run with exec_background so a slow or hung `ip` cannot
 * stall its action queue; runs may therefore overlap. Serialise them on an
 * exclusive lock of this executable, which the kernel drops when the
 * process exits.
 */
static int lock_helper(void) {
    int fd = open("/system/bin/ims_xfrm", O_RDONLY | O_CLOEXEC);
    if (fd < 0) { perror("open lock"); return -1; }
    if (flock(fd, LOCK_EX) != 0) { perror("flock"); close(fd); return -1; }
    return fd;
}

int main(int argc, char *argv[]) {
    if (argc < 2) {
        fprintf(stderr, "usage: ims_xfrm addprop <slot> | flushprop <slot> | add ... | flush ...\n");
        return 1;
    }

    if (setuid(0) != 0) {
        perror("setuid");
        return 1;
    }

    /* Held until exit. */
    if (lock_helper() < 0) return 1;

    if (strcmp(argv[1], "addprop") == 0 && argc >= 3) {
        if (!valid_slot(argv[2])) { fprintf(stderr, "invalid slot\n"); return 1; }
        return install_slot(argv[2]);
    }

    if (strcmp(argv[1], "flushprop") == 0 && argc >= 3) {
        if (!valid_slot(argv[2])) { fprintf(stderr, "invalid slot\n"); return 1; }
        return flush_slot(argv[2]);
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
        "usage: ims_xfrm addprop <slot>\n"
        "       ims_xfrm flushprop <slot>\n"
        "       ims_xfrm add <pcscf> <local> <ue_portc> <ue_ports> <pc_portc> <pc_ports> <server_spic> <server_spis>\n"
        "       ims_xfrm del <pcscf> <local> <ue_portc> <ue_ports> <pc_portc> <pc_ports>\n"
        "       ims_xfrm flush <pcscf> <local> <ue_portc> <ue_ports> <pc_portc> <pc_ports>\n");
    return 1;
}

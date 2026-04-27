#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/wait.h>
#include <sys/system_properties.h>

static int run(const char *cmd) {
    int rc = system(cmd);
    if (WIFEXITED(rc)) return WEXITSTATUS(rc);
    return -1;
}

int main(int argc, char *argv[]) {
    if (argc < 2) {
        fprintf(stderr, "usage: ims_ipsec_setup readprop | <local> <pcscf> <ue_portS> <pcscf_portS> "
                "<spiC> <spiS> <srv_spiC> <srv_spiS> <ik_hex>\n");
        return 1;
    }

    if (setuid(0) != 0) { perror("setuid"); return 1; }

    const char *local, *pcscf, *ue_port, *pcscf_port;
    const char *spi_c, *spi_s, *srv_spi_c, *srv_spi_s, *ik;

    char p_local[PROP_VALUE_MAX], p_pcscf[PROP_VALUE_MAX];
    char p_ueport[PROP_VALUE_MAX], p_pcscfport[PROP_VALUE_MAX];
    char p_spis[PROP_VALUE_MAX], p_srvspis[PROP_VALUE_MAX];
    char p_ik[PROP_VALUE_MAX];

    if (strcmp(argv[1], "readprop") == 0) {
        __system_property_get("lineage.ims.ipsec.local", p_local);
        __system_property_get("lineage.ims.ipsec.pcscf", p_pcscf);
        __system_property_get("lineage.ims.ipsec.ueport", p_ueport);
        __system_property_get("lineage.ims.ipsec.pcscfport", p_pcscfport);
        __system_property_get("lineage.ims.ipsec.spis", p_spis);
        __system_property_get("lineage.ims.ipsec.srvspis", p_srvspis);
        __system_property_get("lineage.ims.ipsec.ik", p_ik);
        local = p_local; pcscf = p_pcscf;
        ue_port = p_ueport; pcscf_port = p_pcscfport;
        spi_c = "0"; spi_s = p_spis;
        srv_spi_c = "0"; srv_spi_s = p_srvspis;
        ik = p_ik;
    } else if (argc >= 10) {
        local = argv[1]; pcscf = argv[2];
        ue_port = argv[3]; pcscf_port = argv[4];
        spi_c = argv[5]; spi_s = argv[6];
        srv_spi_c = argv[7]; srv_spi_s = argv[8];
        ik = argv[9];
    } else {
        fprintf(stderr, "bad args\n"); return 1;
    }

    char cmd[1024];
    int rc;

    printf("IPsec setup: %s:%s <-> %s:%s\n", local, ue_port, pcscf, pcscf_port);
    printf("  spiC=%s spiS=%s srvSpiC=%s srvSpiS=%s\n", spi_c, spi_s, srv_spi_c, srv_spi_s);

    /* SA1: outbound UE:portS → PCSCF:portS, SPI=serverSpiS */
    snprintf(cmd, sizeof(cmd),
        "ip xfrm state add src %s dst %s proto esp spi %s mode transport "
        "reqid 2000 auth-trunc 'hmac(md5)' 0x%s 96 enc 'ecb(cipher_null)' ''",
        local, pcscf, srv_spi_s, ik);
    rc = run(cmd);
    printf("SA outbound (srvSpiS=%s): rc=%d\n", srv_spi_s, rc);

    /* SA2: inbound PCSCF → UE:portS, SPI=spiS */
    snprintf(cmd, sizeof(cmd),
        "ip xfrm state add src %s dst %s proto esp spi %s mode transport "
        "reqid 2000 auth-trunc 'hmac(md5)' 0x%s 96 enc 'ecb(cipher_null)' ''",
        pcscf, local, spi_s, ik);
    rc = run(cmd);
    printf("SA inbound (spiS=%s): rc=%d\n", spi_s, rc);

    /* Policy: outbound from UE:portS to PCSCF:portS */
    snprintf(cmd, sizeof(cmd),
        "ip xfrm policy add "
        "src %s/128 dst %s/128 proto 6 sport %s dport %s "
        "dir out priority 100 "
        "tmpl src %s dst %s proto esp spi %s reqid 2000 mode transport",
        local, pcscf, ue_port, pcscf_port,
        local, pcscf, srv_spi_s);
    rc = run(cmd);
    printf("Policy outbound: rc=%d\n", rc);

    /* Policy: inbound from PCSCF:portS to UE:portS */
    snprintf(cmd, sizeof(cmd),
        "ip xfrm policy add "
        "src %s/128 dst %s/128 proto 6 sport %s dport %s "
        "dir in priority 100 "
        "tmpl src %s dst %s proto esp reqid 2000 mode transport",
        pcscf, local, pcscf_port, ue_port,
        pcscf, local);
    rc = run(cmd);
    printf("Policy inbound: rc=%d\n", rc);

    return 0;
}

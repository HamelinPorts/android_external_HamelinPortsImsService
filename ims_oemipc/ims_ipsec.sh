#!/system/bin/sh
# ims_ipsec.sh — set up IPsec SAs and policies using ip xfrm
# with port selectors, mimicking Samsung's AF_KEY approach.
#
# Usage: ims_ipsec.sh <local_ip> <pcscf_ip> <ue_portS> <pcscf_portS> \
#                      <spiC> <spiS> <server_spiC> <server_spiS> <ik_hex>
#
# This creates 2 SAs + 2 policies for UE:portS <-> PCSCF:portS
# with specific port selectors so they don't affect port 5060.

LOCAL=$1
PCSCF=$2
UE_PORT=$3
PCSCF_PORT=$4
SPI_C=$5
SPI_S=$6
SRV_SPI_C=$7
SRV_SPI_S=$8
IK=$9

if [ -z "$IK" ]; then
    echo "usage: ims_ipsec.sh <local> <pcscf> <ue_portS> <pcscf_portS> <spiC> <spiS> <srv_spiC> <srv_spiS> <ik_hex>"
    exit 1
fi

echo "Setting up IPsec: $LOCAL:$UE_PORT <-> $PCSCF:$PCSCF_PORT"

# Outbound SA: UE:portS → PCSCF:portS, SPI = server_spiS
ip xfrm state add \
    src $LOCAL dst $PCSCF \
    proto esp spi $SRV_SPI_S \
    mode transport \
    auth-trunc 'hmac(md5)' 0x${IK} 96 \
    enc 'ecb(cipher_null)' '' \
    sel src $LOCAL/128 dst $PCSCF/128 sport $UE_PORT dport $PCSCF_PORT

# Inbound SA: PCSCF → UE:portS, SPI = spiS
ip xfrm state add \
    src $PCSCF dst $LOCAL \
    proto esp spi $SPI_S \
    mode transport \
    auth-trunc 'hmac(md5)' 0x${IK} 96 \
    enc 'ecb(cipher_null)' '' \
    sel src $PCSCF/128 dst $LOCAL/128

# Outbound policy: traffic from UE:portS to PCSCF:portS must use ESP
ip xfrm policy add \
    src $LOCAL/128 dst $PCSCF/128 \
    sport $UE_PORT dport $PCSCF_PORT proto tcp \
    dir out priority 1000 \
    tmpl src $LOCAL dst $PCSCF proto esp spi $SRV_SPI_S mode transport

# Inbound policy: traffic from PCSCF:portS to UE:portS must use ESP
ip xfrm policy add \
    src $PCSCF/128 dst $LOCAL/128 \
    sport $PCSCF_PORT dport $UE_PORT proto tcp \
    dir in priority 1000 \
    tmpl src $PCSCF dst $LOCAL proto esp mode transport

echo "IPsec SAs and policies created with port selectors"

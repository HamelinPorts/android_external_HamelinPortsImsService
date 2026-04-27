#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <errno.h>
int main(int argc, char *argv[]) {
    if (argc < 5) { printf("usage: portS_test local port pcscf port\n"); return 1; }
    struct sockaddr_in6 la={0}, ra={0};
    la.sin6_family=AF_INET6; la.sin6_port=htons(atoi(argv[2]));
    ra.sin6_family=AF_INET6; ra.sin6_port=htons(atoi(argv[4]));
    inet_pton(AF_INET6, argv[1], &la.sin6_addr);
    inet_pton(AF_INET6, argv[3], &ra.sin6_addr);
    int fd=socket(AF_INET6,SOCK_STREAM,0);
    int o=1; setsockopt(fd,SOL_SOCKET,SO_REUSEADDR,&o,sizeof(o));
    if(bind(fd,(struct sockaddr*)&la,sizeof(la))<0){printf("bind: %s\n",strerror(errno));return 1;}
    printf("bound to %s:%s\n",argv[1],argv[2]);
    struct timeval tv={5,0}; setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&tv,sizeof(tv));
    if(connect(fd,(struct sockaddr*)&ra,sizeof(ra))<0){printf("connect: %s\n",strerror(errno));return 1;}
    printf("CONNECTED from portS!\n");
    sleep(1); close(fd); return 0;
}

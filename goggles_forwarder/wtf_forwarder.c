#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <signal.h>
#include <errno.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/ip.h>
#include <netinet/udp.h>
#include <arpa/inet.h>
#include <linux/if_packet.h>
#include <linux/if_ether.h>
#include <net/ethernet.h>
#include <sys/prctl.h>
#include <fcntl.h>
#include <poll.h>
#include <time.h>

#define MAGIC_0 'W'
#define MAGIC_1 'T'
#define MAGIC_2 'F'
#define MAGIC_3 'V'

static volatile int s_running = 1;

static void sig_handler(int sig) {
    (void)sig;
    s_running = 0;
}

static uint32_t read_gls_battery_mv(void) {
    int fd = open("/sys/devices/platform/soc/f0a00000.apb/f0a71000.omc/voltage5", O_RDONLY);
    if (fd < 0) return 0;
    char buf[32];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 0;
    buf[n] = '\0';
    int adc = atoi(buf);
    if (adc <= 0) return 0;
    float v = (adc * 0.0222f) - 0.6502f;
    if (v < 0.0f) return 0;
    return (uint32_t)(v * 1000.0f + 0.5f);
}

static int32_t read_gls_temp(void) {
    int fd = open("/sys/devices/platform/soc/f0a00000.apb/f0a71000.omc/temp1", O_RDONLY);
    if (fd < 0) return 0;
    char buf[16];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 0;
    buf[n] = '\0';
    return atoi(buf);
}

static void send_telemetry_packet(uint32_t mv, int32_t temp) {
    uint8_t pkt[16];
    pkt[0] = MAGIC_0;
    pkt[1] = MAGIC_1;
    pkt[2] = MAGIC_2;
    pkt[3] = MAGIC_3;
    pkt[4] = (uint8_t)(7650 & 0xFF);
    pkt[5] = (uint8_t)((7650 >> 8) & 0xFF);
    pkt[6] = 8;
    pkt[7] = 0;
    pkt[8] = (uint8_t)(mv & 0xFF);
    pkt[9] = (uint8_t)((mv >> 8) & 0xFF);
    pkt[10] = (uint8_t)((mv >> 16) & 0xFF);
    pkt[11] = (uint8_t)((mv >> 24) & 0xFF);
    pkt[12] = (uint8_t)(temp & 0xFF);
    pkt[13] = (uint8_t)((temp >> 8) & 0xFF);
    pkt[14] = (uint8_t)((temp >> 16) & 0xFF);
    pkt[15] = (uint8_t)((temp >> 24) & 0xFF);

    ssize_t written = 0;
    while (written < (ssize_t)sizeof(pkt) && s_running) {
        ssize_t w = write(STDOUT_FILENO, pkt + written, sizeof(pkt) - written);
        if (w <= 0) {
            if (errno == EINTR) continue;
            s_running = 0;
            break;
        }
        written += w;
    }
}

int main(int argc, char *argv[]) {
    (void)argc;
    (void)argv;

    prctl(PR_SET_NAME, "wtf_fwd", 0, 0, 0);

    nice(19);

    signal(SIGINT, sig_handler);
    signal(SIGTERM, sig_handler);
    signal(SIGPIPE, sig_handler);

    int sock = socket(AF_PACKET, SOCK_DGRAM, htons(ETH_P_ALL));
    if (sock < 0) {
        sock = socket(AF_PACKET, SOCK_DGRAM, htons(ETH_P_IP));
        if (sock < 0) {
            sock = socket(AF_INET, SOCK_RAW, IPPROTO_UDP);
            if (sock < 0) {
                return 1;
            }
        }
    }

    int rcvbuf = 65536;
    setsockopt(sock, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));

    uint8_t rx_buf[8192];
    uint8_t out_buf[8192 + 8];

    out_buf[0] = MAGIC_0;
    out_buf[1] = MAGIC_1;
    out_buf[2] = MAGIC_2;
    out_buf[3] = MAGIC_3;

    time_t last_telemetry_time = 0;

    while (s_running) {
        time_t now = time(NULL);
        if (now != last_telemetry_time) {
            last_telemetry_time = now;
            uint32_t mv = read_gls_battery_mv();
            int32_t temp = read_gls_temp();
            if (mv > 0 || temp > 0) {
                send_telemetry_packet(mv, temp);
            }
        }

        struct pollfd pfd;
        pfd.fd = sock;
        pfd.events = POLLIN;
        pfd.revents = 0;

        int pr = poll(&pfd, 1, 500);
        if (pr <= 0) {
            if (pr < 0 && errno == EINTR) continue;
            continue;
        }

        struct sockaddr_ll sll;
        socklen_t sll_len = sizeof(sll);
        ssize_t n = recvfrom(sock, rx_buf, sizeof(rx_buf), 0, (struct sockaddr *)&sll, &sll_len);
        if (n <= 0) {
            if (errno == EINTR) continue;
            break;
        }

        if (sll.sll_pkttype == PACKET_OUTGOING) {
            continue;
        }

        if (n < (ssize_t)sizeof(struct iphdr)) continue;

        struct iphdr *iph = (struct iphdr *)rx_buf;
        if (iph->version != 4 || iph->protocol != IPPROTO_UDP) continue;

        int ip_hl = iph->ihl * 4;
        if (n < ip_hl + (ssize_t)sizeof(struct udphdr)) continue;

        struct udphdr *udph = (struct udphdr *)(rx_buf + ip_hl);
        uint16_t dport = ntohs(udph->uh_dport);
        uint16_t sport = ntohs(udph->uh_sport);

        if (dport != 7654 && dport != 7655 && dport != 7656 &&
            sport != 7654 && sport != 7655 && sport != 7656) {
            continue;
        }

        uint16_t target_port = (dport == 7654 || dport == 7655 || dport == 7656) ? dport : sport;
        int udp_len = ntohs(udph->uh_ulen);
        int payload_len = udp_len - sizeof(struct udphdr);

        if (payload_len <= 0 || ip_hl + (int)sizeof(struct udphdr) + payload_len > n) {
            continue;
        }

        const uint8_t *payload = rx_buf + ip_hl + sizeof(struct udphdr);

        out_buf[4] = (uint8_t)(target_port & 0xFF);
        out_buf[5] = (uint8_t)((target_port >> 8) & 0xFF);
        out_buf[6] = (uint8_t)(payload_len & 0xFF);
        out_buf[7] = (uint8_t)((payload_len >> 8) & 0xFF);

        memcpy(out_buf + 8, payload, payload_len);
        size_t total_out = 8 + payload_len;

        ssize_t written = 0;
        while (written < (ssize_t)total_out && s_running) {
            ssize_t w = write(STDOUT_FILENO, out_buf + written, total_out - written);
            if (w <= 0) {
                if (errno == EINTR) continue;
                s_running = 0;
                break;
            }
            written += w;
        }
    }

    close(sock);
    return 0;
}

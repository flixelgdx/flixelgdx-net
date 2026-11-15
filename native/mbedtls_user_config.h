/*
 * Extra settings for the copy of Mbed TLS that is built into the native WebRTC library. They are
 * added on top of Mbed TLS's defaults.
 */

/* libdatachannel configures this part of DTLS even when media support is left out. */
#define MBEDTLS_SSL_DTLS_SRTP

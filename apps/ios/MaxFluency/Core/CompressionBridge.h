#ifndef MAXFLUENCY_COMPRESSION_BRIDGE_H
#define MAXFLUENCY_COMPRESSION_BRIDGE_H
#include <stddef.h>
#include <stdint.h>

int mf_inflate(const uint8_t *input, size_t input_length, uint8_t *output,
               size_t output_capacity, size_t *written, int raw, int allow_incomplete);
#endif

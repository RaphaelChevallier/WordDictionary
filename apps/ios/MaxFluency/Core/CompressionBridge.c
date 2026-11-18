#include "CompressionBridge.h"
#include <limits.h>
#include <string.h>
#include <zlib.h>

int mf_inflate(const uint8_t *input, size_t input_length, uint8_t *output,
               size_t output_capacity, size_t *written, int raw, int allow_incomplete) {
    if (!written || input_length > UINT_MAX || output_capacity > UINT_MAX ||
        (!input && input_length) || !output || !output_capacity) return Z_STREAM_ERROR;
    *written = 0;
    z_stream stream;
    memset(&stream, 0, sizeof(stream));
    int status = inflateInit2(&stream, raw ? -MAX_WBITS : MAX_WBITS);
    if (status != Z_OK) return status;
    stream.next_in = (Bytef *)input;
    stream.avail_in = (uInt)input_length;
    stream.next_out = output;
    stream.avail_out = (uInt)output_capacity;
    for (;;) {
        uLong before_in = stream.total_in, before_out = stream.total_out;
        status = inflate(&stream, Z_SYNC_FLUSH);
        if (status == Z_STREAM_END) {
            if (stream.avail_in != 0) status = Z_DATA_ERROR;
            break;
        }
        if (status != Z_OK && status != Z_BUF_ERROR) break;
        if (stream.avail_out == 0) { status = Z_BUF_ERROR; break; }
        if (stream.avail_in == 0) {
            status = allow_incomplete ? Z_OK : Z_DATA_ERROR;
            break;
        }
        if (stream.total_in == before_in && stream.total_out == before_out) {
            status = Z_DATA_ERROR;
            break;
        }
    }
    *written = (size_t)stream.total_out;
    inflateEnd(&stream);
    return status == Z_STREAM_END ? Z_OK : status;
}

#ifndef ACVR_RVZ_READER_H
#define ACVR_RVZ_READER_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
void* acvr_rvz_open(const char* path);
int acvr_rvz_read(void* handle, uint64_t offset, void* destination, uint32_t size);
uint64_t acvr_rvz_size(void* handle);
void acvr_rvz_close(void* handle);
#ifdef __cplusplus
}
#endif
#endif
